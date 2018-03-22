// Implementation of RobotComm command client. Only called from RobotComm's ChannelImplementation.
// Created by Joseph M. Joy (https://github.com/josephmjoy)
package com.rinworks.robotutils;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.rinworks.robotutils.RobotComm.DatagramTransport.Address;
import com.rinworks.robotutils.RobotComm.ClientRtStatistics;
import com.rinworks.robotutils.RobotComm.ClientStatistics;
import com.rinworks.robotutils.RobotComm.DatagramTransport;
import com.rinworks.robotutils.RobotComm.MessageHeader;
import com.rinworks.robotutils.RobotComm.MessageHeader.CmdStatus;
import com.rinworks.robotutils.RobotComm.SentCommand;
import com.rinworks.robotutils.RobotComm.SentCommand.COMMAND_STATUS;
import com.rinworks.robotutils.RobotComm.DatagramTransport.RemoteNode;

class CommClientImplementation {
    private final StructuredLogger.Log log;
    private final String channelName;
    private DatagramTransport.RemoteNode remoteNode;
    private final Random rand; // For generating command Ids and random delays

    // Initialized to a random value and then incremented for each new command on
    // this channel.
    private final AtomicLong nextCmdId;

    private final ConcurrentHashMap<Long, SentCommandImplementation> pendingCommands;
    private final ConcurrentHashMap<Long, SentCommandImplementation> pendingRtCommands;
    private final ConcurrentLinkedQueue<SentCommandImplementation> completedCommands;
    private final Object ackLock; // To serialize access to the next three
    private long[] ackBuffer; // Buffer of command ids of unsent CMDRESPACKs
    private int ackBufferCount; // Count of ids in the buffer (rest will be 0 / unused)
    private static final int CMDRESP_BUFFER_SIZE = 25; // 25; // Size of buffer - all these must go into a single
                                                       // packet. 25 => msg body size of just under 500 bytes,
                                                       // as ids are encoded as 16-digit hex numbers delimited
                                                       // by the newline char.

    // Client retransmit-related constants
    // The client will retransmit CMD packets for non real-time commands starting
    // with
    // a random delay on the low end and backing off exponentially to a maximum that
    // is
    // between the high range.
    private static final int INITIAL_RETRANSMIT_TIME_LOW = 100;
    private static final int INITIAL_RETRANSMIT_TIME_HIGH = 200;
    private static final int FINAL_RETRANSMIT_TIME_LOW = 10000;
    private static final int FINAL_RETRANSMIT_TIME_HIGH = 20000;

    // Logging strings used more than once.
    private static final String CMDTYPE_TAG = "cmdType: ";
    private static final String CMDID_TAG = "cmdId: ";
    private boolean closed;

    // These are purely for statistics reporting
    // They are not incremented atomically, so are approximate
    private volatile long approxSentCommands;
    private volatile long approxSendCMDs;
    private volatile long approxSentRtCommands;
    private volatile long approxSendRTCMDs;
    private volatile long approxRcvdCMDRESPs;
    private volatile long approxSentCMDRESPACKs;
    private volatile long approxRcvdRTCMDRESPs;
    private volatile long approxRtTimeouts; // #times a response was not received in time or never received.

    CommClientImplementation(String channelName, StructuredLogger.Log log) {
        this.log = log;
        this.rand = new Random();
        this.channelName = channelName;
        this.remoteNode = null;
        this.nextCmdId = new AtomicLong(rand.nextLong());

        this.pendingCommands = new ConcurrentHashMap<>();
        this.pendingRtCommands = new ConcurrentHashMap<>();
        this.completedCommands = new ConcurrentLinkedQueue<>();
        this.ackLock = new Object();

    }

    // Client side
    private class SentCommandImplementation implements SentCommand {

        private final long cmdId;
        private final String cmdType;
        private final String cmd;
        private final long submittedTime;
        private final Object clientContext;
        private final boolean addToCompletionQueue;
        private final Consumer<SentCommand> rtCallback;
        private final boolean rt; // RT == real time
        private final int timeout; // RT-only
        private COMMAND_STATUS stat;
        private String resp;
        private String respType;
        private long respTime;
        private long nextRetransmitTime;
        private int transmitCount;

        SentCommandImplementation(long cmdId, String cmdType, String cmd, Object clientContext,
                boolean addToCompletionQueue) {
            this.cmdId = cmdId;
            this.cmdType = cmdType;
            this.cmd = cmd;
            this.clientContext = clientContext;
            this.addToCompletionQueue = addToCompletionQueue;
            this.rtCallback = null;
            this.rt = false;
            this.timeout = Integer.MAX_VALUE;
            this.submittedTime = System.currentTimeMillis();
            this.stat = COMMAND_STATUS.STATUS_SUBMITTED;
            this.transmitCount = 0;
            this.nextRetransmitTime = Long.MAX_VALUE;
        }

        // Real time command constructor
        SentCommandImplementation(long cmdId, String cmdType, String cmd, int timeout, Consumer<SentCommand> callback) {
            this.cmdId = cmdId;
            this.cmdType = cmdType;
            this.cmd = cmd;
            this.timeout = timeout;
            this.clientContext = null; // No client context for RT commands.
            this.addToCompletionQueue = false;
            this.rt = true;
            this.rtCallback = callback;
            this.submittedTime = System.currentTimeMillis();
            this.stat = COMMAND_STATUS.STATUS_SUBMITTED;
            this.transmitCount = 0;
            this.nextRetransmitTime = Long.MAX_VALUE;
        }

        @Override
        public Object clientContext() {
            return this.clientContext;
        }

        @Override
        public long cmdId() {
            return this.cmdId;
        }

        @Override
        public String cmdType() {
            return cmdType;
        }

        @Override
        public String command() {
            return cmd;
        }

        @Override
        public long submittedTime() {
            return submittedTime;
        }

        @Override
        public COMMAND_STATUS status() {
            return stat;
        }

        @Override
        public String respType() {
            return respType;
        }

        @Override
        public String response() {
            return resp;
        }

        @Override
        public long respondedTime() {
            return respTime;
        }

        @Override
        public void cancel() {

            if (this.rt) {
                rtCancel(COMMAND_STATUS.STATUS_CLIENT_CANCELED, false); // false: remove from map
                return; // ***** EARLY RETURN
            }

            // non-RT case...
            boolean notifyCompletion = false;
            // Remove all tracking of this command.
            // If necessary post to completion queue.
            if (pendingCommands.remove(this.cmdId, this)) {
                synchronized (this) {
                    // if we removed this from the pending queue, it MUST be pending.
                    log.loggedAssert(pending(), "Removed cmd from pending queue but it's status is not pending!");
                    this.stat = COMMAND_STATUS.STATUS_CLIENT_CANCELED;
                    if (this.addToCompletionQueue) {
                        notifyCompletion = true;
                    }
                }
            }

            if (notifyCompletion) {
                CommClientImplementation.this.completedCommands.add(this);
            }
        }

        private void rtCancel(COMMAND_STATUS status, boolean alreadyRemoved) {
            // Set state to the appropriate final state and if necessary notify client.
            // If necessary post to completion queue.
            if (alreadyRemoved || pendingRtCommands.remove(this.cmdId, this)) {
                boolean notify = false;
                synchronized (this) {
                    // if we removed this from the pending queue, it MUST be pending.
                    log.loggedAssert(pending(), "Removed cmd from pending queue but it's status is not pending!");
                    this.stat = status;
                    assert !this.pending();
                    notify = true;
                }
                if (notify) {
                    this.rtCallback.accept(this);
                }
            }
        }

        // *Sync ==> method is synchronized on the object
        public synchronized void updateSync(MessageHeader header, String respBody) {
            if (localStatusOrder() < remoteStatusOrder(header)) {
                this.stat = mapRemoteStatus(header.status);
                if (this.stat == COMMAND_STATUS.STATUS_COMPLETED) {
                    this.respType = header.msgType;
                    this.resp = respBody;
                }
            }
        }

        private int localStatusOrder() {
            switch (this.stat) {
            case STATUS_SUBMITTED:
                return 0;
            case STATUS_REMOTE_QUEUED:
                return 1;
            case STATUS_REMOTE_COMPUTING:
                return 2;
            default:
                assert !this.pending(); // can't get here as we should have handled all the cases.
                return 100;
            }
        }

        private int remoteStatusOrder(MessageHeader header) {
            switch (header.status) {
            case STATUS_PENDING_QUEUED:
                return 1;
            case STATUS_PENDING_COMPUTING:
                return 2;
            default:
                assert header.isComplete(); // can't get here as we should have handled all the cases.
                return 50;
            }
        }

        COMMAND_STATUS mapRemoteStatus(MessageHeader.CmdStatus rStatus) {
            switch (rStatus) {
            case STATUS_PENDING_QUEUED:
                return COMMAND_STATUS.STATUS_REMOTE_QUEUED;
            case STATUS_PENDING_COMPUTING:
                return COMMAND_STATUS.STATUS_REMOTE_COMPUTING;
            case STATUS_COMPLETED:
                return COMMAND_STATUS.STATUS_COMPLETED;
            case STATUS_REJECTED:
                return COMMAND_STATUS.STATUS_REMOTE_REJECTED;
            default:
                assert false; // should never get here.
                return this.stat;
            }
        }

        // Whether or not we should re-send a command at this point in time.
        public boolean shouldResend(long curTime) {
            return curTime > this.nextRetransmitTime;
        }

        public void updateTransmitStats() {
            // Warning - these increments are not protected by any lock. That's ok as its
            // highly that this code is reentered
            // for a particular command instance and even if so, it's ok if it is not
            // updated properly.
            this.transmitCount++;
            int minValue = INITIAL_RETRANSMIT_TIME_HIGH
                    + rand.nextInt(INITIAL_RETRANSMIT_TIME_HIGH - INITIAL_RETRANSMIT_TIME_LOW);
            int maxValue = FINAL_RETRANSMIT_TIME_HIGH
                    + rand.nextInt(FINAL_RETRANSMIT_TIME_HIGH - FINAL_RETRANSMIT_TIME_LOW);
            int delay = randExpDelay(minValue, maxValue, this.transmitCount);
            this.nextRetransmitTime = System.currentTimeMillis() + delay;

        }

        private int randExpDelay(int minValue, int maxValue, int retransmitCount) {
            int expValue = (1 << Math.min(retransmitCount + 4, 30));
            int delay = minValue + rand.nextInt(expValue);
            return Math.max(minValue, Math.min(maxValue, delay));
        }

        public boolean timedOut(long curTime) {
            return this.submittedTime + this.timeout < curTime;
        }
    }

    ClientStatistics getStats() {

        return new ClientStatistics(this.approxSentCommands, this.approxSendCMDs, this.approxRcvdCMDRESPs,
                this.approxSentCMDRESPACKs, this.pendingCommands.size(), this.completedCommands.size());
    }

    ClientRtStatistics getRtStats() {

        return new ClientRtStatistics(this.approxSentRtCommands, this.approxSendRTCMDs, this.approxRcvdRTCMDRESPs,
                this.approxRtTimeouts, this.pendingRtCommands.size());
    }

    public void bindToRemoteNode(RemoteNode node) {
        this.remoteNode = node; // Could override an existing one. That's ok
    }

    public Address remoteAddress() {
        DatagramTransport.RemoteNode rn = this.remoteNode; // can be null
        return rn == null ? null : rn.remoteAddress();
    }

    public void close() {
        // TODO: release resources
        this.closed = true;
    }

    public SentCommand submitCommand(String cmdType, String command, Object clientContext,
            boolean addToCompletionQueue) {

        if (this.closed) {
            throw new IllegalStateException("Channel is closed");
        }

        if (validSendParams(cmdType, this.remoteNode, "DISCARDING_SEND_COMMAND")) {
            long cmdId = newCommandId();
            SentCommandImplementation sc = new SentCommandImplementation(cmdId, cmdType, command, clientContext,
                    addToCompletionQueue);
            this.pendingCommands.put(cmdId, sc);
            this.approxSentCommands++;
            sendCMD(sc);
            return sc;
        } else {
            throw new IllegalArgumentException();
        }
    }

    public SentCommand submitRtCommand(String cmdType, String command, int timeout, Consumer<SentCommand> onComplete) {

        if (this.closed) {
            throw new IllegalStateException("Channel is closed");
        }

        if (validSendParams(cmdType, this.remoteNode, "DISCARDING_SEND_RT_COMMAND")) {
            long cmdId = newCommandId();
            SentCommandImplementation sc = new SentCommandImplementation(cmdId, cmdType, command, timeout, onComplete);
            this.pendingRtCommands.put(cmdId, sc);
            this.approxSentRtCommands++;
            sendRTCMD(sc);
            return sc;
        } else {
            throw new IllegalArgumentException();
        }
    }

    public SentCommand pollCompletedCommand() {
        if (this.closed) {
            return null; // ********* EARLY RETURN
        }
        SentCommand sc = this.completedCommands.poll();
        if (sc != null && log.tracing()) {
            log.trace("CLI_COMPLETED_COMMAND", CMDTYPE_TAG + sc.cmdType());
        }
        return sc;
    }

    private long newCommandId() {
        return nextCmdId.getAndIncrement();
    }

    private void sendCMD(SentCommandImplementation sc) {
        MessageHeader hdr = new MessageHeader(MessageHeader.DgType.DG_CMD, channelName, sc.cmdType, sc.cmdId,
                MessageHeader.CmdStatus.STATUS_NOVALUE);
        this.approxSendCMDs++;
        sc.updateTransmitStats();
        this.remoteNode.send(hdr.serialize(sc.cmd));
    }

    private void sendRTCMD(SentCommandImplementation sc) {
        MessageHeader hdr = new MessageHeader(MessageHeader.DgType.DG_RTCMD, channelName, sc.cmdType, sc.cmdId,
                MessageHeader.CmdStatus.STATUS_NOVALUE);
        this.approxSendRTCMDs++;
        this.remoteNode.send(hdr.serialize(sc.cmd));
    }

    // Client gets this
    void handleReceivedCMDRESP(MessageHeader header, String msgBody, RemoteNode rn) {

        if (this.closed) {
            return;
        }

        this.approxRcvdCMDRESPs++;
        if (header.isPending()) {
            SentCommandImplementation sc = this.pendingCommands.get(header.cmdId);
            if (sc != null) {
                sc.updateSync(header, "");
            }
            // We don't respond to CMDRESP messages with pending status.
            return; // ************ EARLY RETURN *************
        }

        assert !header.isPending();

        SentCommandImplementation sc = this.pendingCommands.remove(header.cmdId);
        if (sc != null) {
            sc.updateSync(header, msgBody);
            if (sc.addToCompletionQueue) {
                this.completedCommands.add(sc);
            }
        }

        // we always ACK a completed response, whether we find it or not, UNLESS it is
        // rejected - rejected indicates the server doesn't know about this command.
        if (header.status != CmdStatus.STATUS_REJECTED) {
            queueCmdRespAck(header, rn);
        }
    }

    public void handleReceivedRTCMDRESP(MessageHeader header, String msgBody, RemoteNode rn) {
        if (this.closed) {
            return;
        }

        this.approxRcvdRTCMDRESPs++;
        if (header.isPending()) {
            // We should never get RTCMDRESP messages with pending status.
            return; // ************ EARLY RETURN *************
        }

        assert !header.isPending();

        SentCommandImplementation sc = this.pendingRtCommands.remove(header.cmdId);
        if (sc != null) {
            if (sc.timedOut(System.currentTimeMillis())) {
                // Alas, we are rejecting this response because the client's timeout
                // has expired...
                this.approxRtTimeouts++;
                if (log.tracing())
                    log.trace("CLI_TIMEDOUT_RTCOMMAND", CMDTYPE_TAG + sc.cmdType());
                sc.rtCancel(COMMAND_STATUS.STATUS_ERROR_TIMEOUT, true);
                return; // *********** EARLY RETURN
            } else {
                sc.updateSync(header, msgBody);
            }
            if (log.tracing())
                log.trace("CLI_COMPLETED_RTCOMMAND", CMDTYPE_TAG + sc.cmdType());
            sc.rtCallback.accept(sc);
        }

        // We never ACK a completed RT response
    }

    private void queueCmdRespAck(MessageHeader header, RemoteNode rn) {
        // We don't attempt to validate remoteAddr. Because of NAT, perhaps this is
        // different than
        // what we have for the channel's remote addr...
        long[] bufferToSend = null;

        synchronized (this.ackLock) {
            if (this.ackBuffer == null) {
                this.ackBuffer = new long[CMDRESP_BUFFER_SIZE];
            }
            // should always be space in the buffer coming in...
            assert this.ackBufferCount < this.ackBuffer.length;
            this.ackBuffer[this.ackBufferCount] = header.cmdId;
            this.ackBufferCount++;

            // If buffer is full, we send it,
            if (this.ackBufferCount == CMDRESP_BUFFER_SIZE) {
                bufferToSend = this.ackBuffer;
                this.ackBuffer = null;
                this.ackBufferCount = 0;
            }
        }

        if (bufferToSend != null) {
            // We assume that remoteAddr is valid, and represents the
            // server. Perhaps we should validate this, but for now
            // we assume that given that command IDs are random 64-bit numbers
            // we can spam the server with bogus ids without too much disruption.
            sendCMDRESPACK(bufferToSend);
        }
    }

    private void sendCMDRESPACK(long[] bufferToSend) {
        MessageHeader hdr = new MessageHeader(MessageHeader.DgType.DG_CMDRESPACK, channelName,
                MessageHeader.STR_MSGTYPE_IDLIST, 0, MessageHeader.CmdStatus.STATUS_NOVALUE);
        this.approxSentCMDRESPACKs++;
        StringBuilder sb = new StringBuilder();
        String msg = null;
        for (long id : bufferToSend) {
            String strId = Long.toHexString(id);
            if (sb.length() == 0) {
                sb.append(strId);
            } else {
                sb.append("\n" + strId);
            }
        }
        this.remoteNode.send(hdr.serialize(sb.toString()));
    }

    void periodicWork() {
        long curTime = System.currentTimeMillis();
        if (!this.closed) {
            handleRetransmits(curTime);
            processRtTimeouts(curTime);
        }
    }

    // Timeout pending rt commands that have expired.
    private void processRtTimeouts(long curTime) {
        // TODO: we create a new array list whether there any rt commands or not. This
        // is wasteful.
        // Also, we run through the entire RT list looking for timeouts each time. This
        // is also wasteful, though harder to fix.
        ArrayList<SentCommandImplementation> timedOut = new ArrayList<>();

        // See impnotes.md note #March 16A, 2018 - if you specify. say, 0, as the first
        // parameter,
        // the lambda will be executed in parallel for value, resulting in bad and
        // unpredictable behavior
        // because the timeoutArrayList is not thread safe.
        this.pendingRtCommands.forEachValue(Long.MAX_VALUE, sc -> {
            if (sc.timedOut(curTime)) {
                timedOut.add(sc);
                if (log.tracing())
                    log.trace("CLI_RTCMD_TIMEOUT", CMDID_TAG + sc.cmdId);

            }
        });

        for (SentCommandImplementation sc : timedOut) {
            sc.rtCancel(COMMAND_STATUS.STATUS_ERROR_TIMEOUT, false); // false == remove from map
            this.approxRtTimeouts++;
            if (log.tracing())
                log.trace("CLI_TIMEDOUT_RTCOMMAND", CMDTYPE_TAG + sc.cmdType());
        }
    }

    private void handleRetransmits(long curTime) {
        this.pendingCommands.forEachValue(Long.MAX_VALUE, sc -> {
            if (sc.shouldResend(curTime)) {
                sendCMD(sc);
            }
        });
    }

    private boolean validSendParams(String msgType, RemoteNode rn, String logMsgType) {
        final String BAD_MSGTYPE_CHARS = ", \t\f\n\r";
        boolean ret = false;

        if (rn == null) {
            if (log.tracing())
                log.trace(logMsgType, "No default send node");
        } else if (RobotComm.containsChars(msgType, BAD_MSGTYPE_CHARS)) {
            if (log.tracing())
                log.trace(logMsgType, "Message type has invalid chars: " + msgType);
        } else {
            ret = true;
        }

        return ret;
    }
}

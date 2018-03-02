// Implementation of RobotComm command client. Only called from RobotComm.
// Created by Joseph M. Joy (https://github.com/josephmjoy)
package com.rinworks.robotutils;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import com.rinworks.robotutils.RobotComm.Address;
import com.rinworks.robotutils.RobotComm.DatagramTransport;
import com.rinworks.robotutils.RobotComm.MessageHeader;
import com.rinworks.robotutils.RobotComm.SentCommand;
import com.rinworks.robotutils.RobotComm.DatagramTransport.RemoteNode;

class CommClientImplementation {
    private final DatagramTransport transport;
    private final StructuredLogger.Log log;
    private final String channelName;
    private DatagramTransport.RemoteNode remoteNode;
    private final Random rand; // For generating command Ids and random delays

    // Initialized to a random value and then incremented for each new command on
    // this channel.
    private final AtomicLong nextCmdId;

    private final ConcurrentHashMap<Long, SentCommandImplementation> pendingCommands;
    private final ConcurrentLinkedQueue<SentCommandImplementation> completedCommands;
    private final Object ackLock; // To serialize access to the next three
    private long[] ackBuffer; // Buffer of command ids of unsent CMDRESPACKs
    private int ackBufferCount; // Count of ids in the buffer (rest will be 0 / unused)
    private static final int CMDRESP_BUFFER_SIZE = 25; // Size of buffer - all these must go into a single
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

    private boolean closed;

    // These are purely for statistics reporting
    // They are not incremented atomically, so are approximate
    private volatile long approxSentCommands;
    private volatile long approxSendCMDs;
    private volatile long approxRcvdCMDRESPs;
    private volatile long approxSentCMDRESPACKs;

    CommClientImplementation(String channelName, DatagramTransport transport, StructuredLogger.Log log) {
        this.transport = transport;
        this.log = log;
        this.rand = new Random();
        this.channelName = channelName;
        this.remoteNode = null;
        this.nextCmdId = new AtomicLong(rand.nextLong());

        this.pendingCommands = new ConcurrentHashMap<>();
        this.completedCommands = new ConcurrentLinkedQueue<>();
        this.ackLock = new Object();

    }

    // Client side
    private class SentCommandImplementation implements SentCommand {

        private final long cmdId;
        private final String cmdType;
        private final String cmd;
        private final long submittedTime;
        private final boolean addToCompletionQueue;
        private COMMAND_STATUS stat;
        private String resp;
        private String respType;
        private long respTime;
        private long nextRetransmitTime;
        private int transmitCount;

        SentCommandImplementation(long cmdId, String cmdType, String cmd, boolean addToCompletionQueue) {
            this.cmdId = cmdId;
            this.cmdType = cmdType;
            this.cmd = cmd;
            this.addToCompletionQueue = addToCompletionQueue;
            this.submittedTime = System.currentTimeMillis();
            this.stat = COMMAND_STATUS.STATUS_SUBMITTED;
            this.transmitCount = 0;
            this.nextRetransmitTime = Long.MAX_VALUE;
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

        // LK ==> caller should ensure locking
        public void updateLK(MessageHeader header, String respBody) {
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
    }

    public static class ClientStatistics {
        public final long sentCommands;
        public final long sentCMDs;
        public final long rcvdCMDRESPs;
        public final long sentCMDRESPACKs;
        public final int curCliSentCmdMapSize;
        public final int curCliSentCmdCompletionQueueSize;

        ClientStatistics(long sentCommands, long sentCMDs, long rcvdCMDRESPs, long sentCMDRESPACKs,
                int curCliSentCmdMapSize, int curCliSentCmdCompletionQueueSize) {
            this.sentCommands = sentCommands;
            this.sentCMDs = sentCMDs;
            this.rcvdCMDRESPs = rcvdCMDRESPs;
            this.sentCMDRESPACKs = sentCMDRESPACKs;
            this.curCliSentCmdMapSize = curCliSentCmdMapSize;
            this.curCliSentCmdCompletionQueueSize = curCliSentCmdCompletionQueueSize;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (sentCommands > 0) {
                sb.append(" sc: " + sentCommands);
            }
            if (sentCMDs > 0) {
                sb.append(" sC: " + sentCMDs);
            }
            if (rcvdCMDRESPs > 0) {
                sb.append(" rCR: " + rcvdCMDRESPs);
            }
            if (sentCMDRESPACKs > 0) {
                sb.append(" sCRA: " + sentCMDRESPACKs);
            }
            if (curCliSentCmdMapSize > 0) {
                sb.append(" cliCMap: " + curCliSentCmdMapSize);
            }
            if (curCliSentCmdCompletionQueueSize > 0) {
                sb.append(" cliCCQ: " + curCliSentCmdCompletionQueueSize);
            }
            return sb.toString();
        }
    }

    ClientStatistics getStats() {

        return new ClientStatistics(this.approxSentCommands, this.approxSendCMDs, this.approxRcvdCMDRESPs,
                this.approxSentCMDRESPACKs, this.pendingCommands.size(), this.completedCommands.size());
    }

    public void bindToRemoteNode(Address remoteAddress) {
        DatagramTransport.RemoteNode node = transport.newRemoteNode(remoteAddress);
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

    public SentCommand sendCommand(String cmdType, String command, boolean addToCompletionQueue) {

        if (this.closed) {
            throw new IllegalStateException("Channel is closed");
        }

        DatagramTransport.RemoteNode rn = this.remoteNode; // can be null

        if (validSendParams(cmdType, rn, "DISCARDING_SEND_COMMAND")) {
            long cmdId = newCommandId();
            SentCommandImplementation sc = new SentCommandImplementation(cmdId, cmdType, command, addToCompletionQueue);
            this.pendingCommands.put(cmdId, sc);
            this.approxSentCommands++;
            sendCmd(sc);
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
        if (sc != null) {
            log.trace("CLI_COMPLETED_COMMAND", CMDTYPE_TAG + sc.cmdType());
        }
        return sc;
    }

    private long newCommandId() {
        return nextCmdId.getAndIncrement();
    }

    private void sendCmd(SentCommandImplementation sc) {
        MessageHeader hdr = new MessageHeader(MessageHeader.DgType.DG_CMD, channelName, sc.cmdType, sc.cmdId,
                MessageHeader.CmdStatus.STATUS_NOVALUE);
        this.approxSendCMDs++;
        sc.updateTransmitStats();
        this.remoteNode.send(hdr.serialize(sc.cmd));
    }

    // Client gets this
    void cliHandleReceivedCommandResponse(MessageHeader header, String msgBody, Address remoteAddr) {

        if (this.closed) {
            return;
        }

        this.approxRcvdCMDRESPs++;
        if (header.isPending()) {
            SentCommandImplementation sc = this.pendingCommands.get(header.cmdId);
            if (sc != null) {
                synchronized (sc) {
                    sc.updateLK(header, "");
                }
            }
            // We don't respond to CMDRESP messages with pending status.
            return; // ************ EARLY RETURN *************
        }

        assert !header.isPending();

        SentCommandImplementation sc = this.pendingCommands.remove(header.cmdId);
        if (sc != null) {
            synchronized (sc) {
                // If it *was* in the pending sent queue, it *must* be pending.
                assert sc.pending();
                sc.updateLK(header, msgBody);
                assert !sc.pending();
            }

            if (sc.addToCompletionQueue) {
                this.completedCommands.add(sc);
            }
        }

        queueCmdRespAck(header, remoteAddr); // we always ACK a completed response, whether we find it or not.
    }

    private void queueCmdRespAck(MessageHeader header, Address remoteAddr) {
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
            sendCmdRespAcks(bufferToSend);
        }
    }

    private void sendCmdRespAcks(long[] bufferToSend) {
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
        }
    }

    private void handleRetransmits(long curTime) {
        this.pendingCommands.forEachValue(0, sc -> {
            if (sc.shouldResend(curTime)) {
                sendCmd(sc);
            }
        });
    }

    private boolean validSendParams(String msgType, RemoteNode rn, String logMsgType) {
        final String BAD_MSGTYPE_CHARS = ", \t\f\n\r";
        boolean ret = false;

        if (rn == null) {
            log.trace(logMsgType, "No default send node");
        } else if (RobotComm.containsChars(msgType, BAD_MSGTYPE_CHARS)) {
            log.trace(logMsgType, "Message type has invalid chars: " + msgType);
        } else {
            ret = true;
        }

        return ret;
    }

}

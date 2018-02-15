// RobotComm - UDP based communications for robot status and commands.
// Created by Joseph M. Joy (https://github.com/josephmjoy)
package com.rinworks.robotutils;

import java.io.Closeable;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import com.rinworks.robotutils.RobotComm.DatagramTransport.RemoteNode;
import com.rinworks.robotutils.RobotComm.MessageHeader.CmdStatus;
import com.rinworks.robotutils.RobotComm.SentCommand.COMMAND_STATUS;

/**
 * Class to implement simple 2-way message passing over UDP
 *
 */
public class RobotComm implements Closeable {
    private static final String PROTOCOL_SIGNATURE = "3wIC"; // About 1 of 10E7 combnations.
    private final DatagramTransport transport;
    private final StructuredLogger.Log log;
    private boolean commClosed; // set to false when close() is called.
    private final ConcurrentHashMap<String, ChannelImplementation> channels;
    private final Object listenLock;
    private final Random cmdIdRand; // Used for generating command Ids

    private volatile DatagramTransport.Listener listener;

    interface Address {
        String stringRepresentation();
    }

    interface SentCommand {
        enum COMMAND_STATUS {
            STATUS_SUBMITTED, STATUS_REMOTE_QUEUED, STATUS_REMOTE_COMPUTING, STATUS_COMPLETED, STATUS_REMOTE_REJECTED, STATUS_ERROR_TIMEOUT, STATUS_ERROR_COMM, STATUS_CLIENT_CANCELED
        }

        // These are set when the command is submitted.

        long cmdId();

        String cmdType();

        String command();

        long submittedTime();

        // Status can be checked anytime.
        COMMAND_STATUS status();

        // True if the command is still pending.
        default boolean pending() {
            COMMAND_STATUS stat = status();
            return stat == COMMAND_STATUS.STATUS_SUBMITTED || stat == COMMAND_STATUS.STATUS_REMOTE_QUEUED
                    || stat == COMMAND_STATUS.STATUS_REMOTE_COMPUTING;
        }

        // These three fields only return valid values if the status
        // is STATUS_COMPLETED
        String respType();

        String response();

        long respondedTime();

        void cancel();
    }

    public interface ReceivedMessage {
        String message();

        String msgType();

        Address remoteAddress();

        long receivedTimestamp(); // when it was received.

        Channel channel();
    }

    interface ReceivedCommand extends ReceivedMessage {
        long cmdId();

        void respond(String respType, String resp);
    }

    public interface Channel extends Closeable {

        String name();

        void startReceivingMessages();

        void stopReceivingMessages(); // will drop incoming messages in queue

        void startReceivingCommands();

        void stopReceivingCommands(); // will drop incoming commands in queue

        // This channel will only communicate with the specified remote node,
        // including received messages and commands.
        // Can be changed on the fly. Set to null to clear.
        void bindToRemoteNode(Address remoteAddress);

        Address remoteAddress(); // Can be null

        ReceivedMessage pollReceivedMessage();

        ReceivedCommand pollReceivedCommand();

        // Will drop message if not bound to a remote node.
        void sendMessage(String msgType, String message);

        void sendMessage(String msgType, String message, Address addr);

        SentCommand sendCommand(String cmdType, String command, boolean addToCompletionQueue);

        SentCommand pollCompletedCommand();

        void close();
    }

    public interface DatagramTransport extends Closeable {

        interface RemoteNode {
            Address remoteAddress();

            void send(String msg);
        }

        interface Listener extends Closeable {

            /**
             * Listens for messages.
             * 
             * @param handler
             *            called when a message arrives. Will likely be called in some other
             *            thread's context. The handler is expected NOT to block. If time
             *            consuming operations need to be performed, queue the message for
             *            further processing, or implement a state machine. The handler
             *            *may* be reentered or called concurrently from another thread.
             *            Call close to stop new messages from being received.
             */
            void listen(BiConsumer<String, RemoteNode> handler);

            Address getAddress();

            void close(); // idempotent. handler MAY get called after close() returns.
        }

        Address resolveAddress(String address);

        Listener newListener(Address localAddress);

        RemoteNode newRemoteNode(Address remoteAddress);

        void close(); // Closes all open listeners and remote notes.
    }

    public RobotComm(DatagramTransport transport, StructuredLogger.Log log) {
        this.transport = transport;
        this.log = log;
        this.listenLock = new Object();
        this.cmdIdRand = new Random();
        this.channels = new ConcurrentHashMap<>();

    }

    public Address resolveAddress(String address) {
        return this.transport.resolveAddress(address);
    }

    /*
     * Channels must be unique. An attempt to create a channel that already exists
     * produces a DuplicateKey exception
     */
    public Channel newChannel(String channelName) {
        if (commClosed) {
            throw new IllegalStateException("Robot comm is closed!");
        }
        final String BAD_CHANNEL_CHARS = ", \t\f\r\n";
        if (containsChars(channelName, BAD_CHANNEL_CHARS)) {
            throw new IllegalArgumentException("channel name has invalid characters: " + channelName);
        }
        ChannelImplementation ch = this.channels.get(channelName);
        if (ch != null) {
            throw new UnsupportedOperationException("Channel with name " + channelName + " exists");
        } else {
            ch = new ChannelImplementation(channelName);
            ChannelImplementation prevCh = this.channels.put(channelName, ch);
            if (prevCh != null) {
                ch = prevCh;
            }
        }
        return ch;
    }

    // Idempotent, thread safe
    public void startListening() {
        DatagramTransport.Listener li = null;
        synchronized (listenLock) {
            if (this.listener == null) {
                li = this.transport.newListener(null);
                this.listener = li;
            }
        }

        if (li != null) {
            log.info("STARTED LISTENING");

            li.listen((String msg, DatagramTransport.RemoteNode rn) -> {
                Address remoteAddr = rn.remoteAddress();
                if (!msg.startsWith(PROTOCOL_SIGNATURE)) {
                    log.trace("WARN_DROPPING_RECIEVED_MESSAGE", "Incorrect protocol signature.");
                    return; // EARLY RETURN
                }
                int headerLength = msg.indexOf('\n');
                String headerStr = "";
                if (headerLength < 0) {
                    log.trace("WARN_DROPPING_RECIEVED_MESSAGE", "Malformed header.");
                    return; // EARLY RETURN
                }
                headerStr = msg.substring(0, headerLength);

                MessageHeader header = MessageHeader.parse(headerStr, remoteAddr, log);
                if (header == null) {
                    return; // EARLY RETURN
                }
                ChannelImplementation ch = channels.get(header.channel);

                if (ch == null) {
                    handleMsgToUnknownChannel(header);
                } else {
                    String msgBody = msg.substring(headerLength + 1);
                    if (header.dgType == MessageHeader.DgType.DG_MSG) {
                        ch.handleReceivedMessage(header, msgBody, remoteAddr);
                    } else if (header.dgType == MessageHeader.DgType.DG_CMD) {
                        ch.srvHandleReceivedCommand(header, msgBody, remoteAddr);
                    } else if (header.dgType == MessageHeader.DgType.DG_CMDRESP) {
                        ch.cliHandleReceivedCommandResponse(header, msgBody, remoteAddr);
                    } else if (header.dgType == MessageHeader.DgType.DG_CMDRESPACK) {
                        ch.srvHandleReceivedCommandResponseAck(header, msgBody, remoteAddr);
                    } else {
                        assert false; // we have already validated the message, so shouldn't get here.
                    }
                }
            });
        }
    }

    // Idempotent, threada safe
    public void stopListening() {
        DatagramTransport.Listener li = null;
        synchronized (listenLock) {
            if (this.listener != null) {
                li = this.listener;
                this.listener = null;
            }
        }
        log.info("STOPPED LISTENING");
        if (li != null) {
            li.close();
        }
    }

    public boolean isListening() {
        // Not synchronized as there is no point
        return this.listener != null;
    }

    public void close() {

        // THis will cause subsequent attempts to create channels to
        // fail with an invalid state exception.
        this.commClosed = true;

        stopListening();

        // Close all channels
        for (ChannelImplementation ch : channels.values()) {
            ch.close();
        }
        // Channels should pull themselves off the list as they close...
        assert channels.size() == 0;

        transport.close();
    }

    /**
     * MUST be called periodically so that periodic maintenance tasks can be done,
     * chiefly handling of re-transmits.
     */
    public void periodicWork() {
        if (!this.commClosed && this.isListening()) {
            for (ChannelImplementation ch : this.channels.values()) {
                ch.periodicWork();
            }
        }
    }

    private void handleMsgToUnknownChannel(MessageHeader header) {
        // TODO Auto-generated method stub

    }

    static class MessageHeader {
        enum DgType {
            DG_MSG, DG_CMD, DG_CMDRESP, DG_CMDRESPACK
        };

        static final String STR_DG_MSG = "MSG";
        static final String STR_DG_CMD = "CMD";
        static final String STR_DG_CMDRESP = "CMDRESP";
        static final String STR_DG_CMDRESPACK = "CMDRESPACK";

        static final String STR_STATUS_PENDING_QUEUED = "QUEUED";
        static final String STR_STATUS_PENDING_COMPUTING = "COMPUTING";
        static final String STR_STATUS_COMPLETED = "COMPLETED";
        static final String STR_STATUS_REJECTED = "REJECTED";

        static final int INDEX_PROTO = 0;
        static final int INDEX_DG_TYPE = 1;
        static final int INDEX_CHANNEL = 2;
        static final int INDEX_MSG_TYPE = 3;
        static final int INDEX_CMDID = 4;
        static final int INDEX_CMDSTATUS = 5;

        final DgType dgType;
        final String channel;
        final String msgType;
        final long cmdId;

        enum CmdStatus {
            STATUS_PENDING_QUEUED, STATUS_PENDING_COMPUTING, STATUS_COMPLETED, STATUS_REJECTED, STATUS_NOVALUE // Don't
                                                                                                               // use
        };

        final CmdStatus status;

        private MessageHeader(DgType dgType, String channel, String msgType, long cmdId, CmdStatus status) {
            this.dgType = dgType;
            this.channel = channel;
            this.msgType = msgType == null ? "" : msgType;
            this.cmdId = cmdId;
            this.status = status;
        }

        public boolean isPending() {
            return this.dgType == DgType.DG_CMDRESP && (this.status == CmdStatus.STATUS_PENDING_COMPUTING
                    || this.status == CmdStatus.STATUS_PENDING_QUEUED);
        }

        public boolean isComplete() {
            return this.dgType == DgType.DG_CMDRESP && !!!isPending();
        }

        // Examples
        // - "1309JHI,MY_CHANNEL,MSG,MY_MSG_TYPE"
        // - "1309JHI,MY_CHANNEL,CMD,MY_COMMAND_TYPE,2888AB89"
        // - "1309JHI,MY_CHANNEL,CMDRESP,MY_RESPONSE_TYPE,2888AB89,OK"

        static MessageHeader parse(String headerStr, Address remoteAddr, StructuredLogger.Log log) {
            final String BAD_HEADER_CHARS = " \t\f\n\r";

            if (containsChars(headerStr, BAD_HEADER_CHARS)) {
                log.trace("WARN_DROPPING_RECIEVED_MESSAGE", "Header contains invalid chars");
                return null; // ************ EARLY RETURN
            }
            String[] header = headerStr.split(",");
            if (header.length < 4) {
                log.trace("WARN_DROPPING_RECIEVED_MESSAGE", "Malformed header");
                return null; // ************ EARLY RETURN
            }

            // This fact should have been checked before calling us
            assert header[INDEX_PROTO].equals(PROTOCOL_SIGNATURE);

            String dgTypeStr = header[INDEX_DG_TYPE];
            DgType dgType;
            boolean getCmdId = false;
            boolean getStatus = false;
            if (dgTypeStr.equals(STR_DG_MSG)) {
                dgType = DgType.DG_MSG;
            } else if (dgTypeStr.equals(STR_DG_CMD)) {
                dgType = DgType.DG_CMD;
                getCmdId = true;
            } else if (dgTypeStr.equals(STR_DG_CMDRESP)) {
                dgType = DgType.DG_CMDRESP;
                getCmdId = true;
                getStatus = true;
            } else if (dgTypeStr.equals(STR_DG_CMDRESPACK)) {
                dgType = DgType.DG_CMDRESPACK;
            } else {
                log.trace("WARN_DROPPING_RECIEVED_MESSAGE", "Malformed header - unknown DG type");
                return null; // ************ EARLY RETURN
            }

            String channel = header[INDEX_CHANNEL];
            if (channel.length() == 0) {
                log.trace("WARN_DROPPING_RECEIVED_MESSGAGE", "Missing channel name");
                return null; // ************ EARLY RETURN
            }

            String msgType = header[INDEX_MSG_TYPE];
            // We do not do special error checking on user msgType...
            long cmdId = 0;
            if (getCmdId) {
                if (header.length <= INDEX_CMDID) {
                    log.trace("WARN_DROPPING_RECIEVED_MESSAGE", "Malformed header - missing cmd ID");
                    return null; // ************ EARLY RETURN
                }
                try {
                    cmdId = Long.parseUnsignedLong(header[INDEX_CMDID], 16); // Hex
                } catch (NumberFormatException e) {
                    log.trace("WARN_DROPPING_RECIEVED_MESSAGE", "Malformed header - invalid cmd ID");
                    log.trace(e.toString());
                    return null; // ************ EARLY RETURN
                }
            }

            CmdStatus status = CmdStatus.STATUS_NOVALUE;
            if (getStatus) {
                if (header.length <= INDEX_CMDSTATUS) {
                    log.trace("WARN_DROPPING_RECIEVED_MESSAGE", "Malformed header - missing cmd status");
                    return null; // ************ EARLY RETURN
                }
                String statusStr = header[INDEX_CMDSTATUS];
                if (statusStr.equals(STR_STATUS_PENDING_QUEUED)) {
                    status = CmdStatus.STATUS_PENDING_QUEUED;
                } else if (statusStr.equals(STR_STATUS_PENDING_COMPUTING)) {
                    status = CmdStatus.STATUS_PENDING_COMPUTING;
                } else if (statusStr.equals(STR_STATUS_COMPLETED)) {
                    status = CmdStatus.STATUS_COMPLETED;
                } else if (statusStr.equals(STR_STATUS_REJECTED)) {
                    status = CmdStatus.STATUS_REJECTED;
                } else {
                    log.trace("WARN_DROPPING_RECIEVED_MESSAGE", "Malformed header - unknown status");
                    return null; // ************ EARLY RETURN
                }
            }

            return new MessageHeader(dgType, channel, msgType, cmdId, status);
        }

        public String serialize(String additionalText) {
            additionalText = additionalText == null ? "" : additionalText;
            String dgTypeStr = dgTypeToString();
            String cmdIdStr = cmdIdToString();
            String statusStr = statusToString() + '\n' + additionalText;
            return String.join(",", PROTOCOL_SIGNATURE, dgTypeStr, this.channel, this.msgType, cmdIdStr, statusStr);
        }

        private String statusToString() {
            if (this.status == CmdStatus.STATUS_PENDING_QUEUED) {
                return STR_STATUS_PENDING_QUEUED;
            }
            if (this.status == CmdStatus.STATUS_PENDING_COMPUTING) {
                return STR_STATUS_PENDING_COMPUTING;
            } else if (this.status == CmdStatus.STATUS_COMPLETED) {
                return STR_STATUS_COMPLETED;
            } else if (this.status == CmdStatus.STATUS_REJECTED) {
                return STR_STATUS_REJECTED;
            } else if (this.status == CmdStatus.STATUS_NOVALUE) {
                return "";
            } else {
                assert false;
                return "UNKNOWN";
            }
        }

        private String cmdIdToString() {
            return cmdId == 0 ? "" : Long.toHexString(cmdId);
        }

        private String dgTypeToString() {
            if (this.dgType == DgType.DG_MSG) {
                return STR_DG_MSG;
            } else if (this.dgType == DgType.DG_CMD) {
                return STR_DG_CMD;
            } else if (this.dgType == DgType.DG_CMDRESP) {
                return STR_DG_CMDRESP;
            } else if (this.dgType == DgType.DG_CMDRESPACK) {
                return STR_DG_CMDRESPACK;
            } else {
                assert false;
                return "UNKNOWN";
            }
        }
    }

    private class ChannelImplementation implements Channel {
        private final String name;
        private DatagramTransport.RemoteNode remoteNode;

        // Initialized to a random value and then incremented for each new command on
        // this channel.
        private final AtomicLong nextCmdId;

        // Receiving messages
        private final ConcurrentLinkedQueue<ReceivedMessageImplementation> pendingRecvMessages;

        // Sending of commands
        private final ConcurrentHashMap<Long, SentCommandImplementation> cliPendingSentCommands;
        private final ConcurrentLinkedQueue<SentCommandImplementation> cliCompletedSentCommands;

        // Receiving of commands
        private final ConcurrentHashMap<Long, ReceivedCommandImplementation> srvRecvCommandsMap;
        private final ConcurrentLinkedQueue<ReceivedCommandImplementation> srvPendingRecvCommands;
        private final ConcurrentLinkedQueue<ReceivedCommandImplementation> srvCompletedRecvCommands;

        final Object receiverLock;
        DatagramTransport.Listener listener;
        private boolean receiveMessages;
        private boolean receiveCommands;
        private boolean closed;

        private class ReceivedMessageImplementation implements ReceivedMessage {
            private final String msg;
            private final String msgType;
            private final Address remoteAddress;
            private long recvdTimeStamp;
            private final Channel ch;

            ReceivedMessageImplementation(String msgType, String msg, Address remoteAddress, Channel ch) {
                this.msg = msg;
                this.msgType = msgType;
                this.remoteAddress = remoteAddress;
                this.recvdTimeStamp = System.currentTimeMillis();
                this.ch = ch;
            }

            @Override
            public String message() {
                return this.msg;
            }

            @Override
            public String msgType() {
                return this.msgType;
            }

            @Override
            public Address remoteAddress() {
                return this.remoteAddress;
            }

            @Override
            public long receivedTimestamp() {
                return recvdTimeStamp;
            }

            @Override
            public Channel channel() {
                return ch;
            }

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

            SentCommandImplementation(long cmdId, String cmdType, String cmd, boolean addToCompletionQueue) {
                this.cmdId = cmdId;
                this.cmdType = cmdType;
                this.cmd = cmd;
                this.addToCompletionQueue = addToCompletionQueue;
                this.submittedTime = System.currentTimeMillis();
                this.stat = COMMAND_STATUS.STATUS_SUBMITTED;
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
                // TODO Auto-generated method stub

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
        }

        // Server Side
        private class ReceivedCommandImplementation implements ReceivedCommand {

            private final long cmdId;
            private final String cmdBody;
            private final String cmdType;
            private final Address remoteAddress;
            private final DatagramTransport.RemoteNode rn;
            private long recvdTimeStamp;
            private final ChannelImplementation ch;

            private MessageHeader.CmdStatus status;
            private String respType;
            private String respBody;

            public ReceivedCommandImplementation(long cmdId, String msgType, String msgBody, Address remoteAddr,
                    ChannelImplementation ch) {
                this.cmdId = cmdId;
                this.cmdBody = msgBody;
                this.cmdType = msgType;
                this.remoteAddress = remoteAddr;
                this.rn = transport.newRemoteNode(remoteAddr);
                this.recvdTimeStamp = System.currentTimeMillis();
                this.ch = ch;
                this.status = MessageHeader.CmdStatus.STATUS_PENDING_QUEUED;
            }

            @Override
            public long cmdId() {
                return this.cmdId;
            }

            @Override
            public String message() {
                return this.cmdBody;
            }

            @Override
            public String msgType() {
                return this.cmdType;
            }

            @Override
            public Address remoteAddress() {
                return this.remoteAddress;
            }

            @Override
            public long receivedTimestamp() {
                return this.recvdTimeStamp;
            }

            @Override
            public Channel channel() {
                return this.ch;
            }

            @Override
            public void respond(String respType, String resp) {
                // Server code is responding with the result of performing a command.
                srvHandleComputedResponse(this, respType, resp);

            }

        }

        public ChannelImplementation(String channelName) {
            this.name = channelName;
            this.remoteNode = null;
            this.nextCmdId = new AtomicLong(cmdIdRand.nextLong());

            // For receiving messages
            this.pendingRecvMessages = new ConcurrentLinkedQueue<>();

            // For sending commands
            this.cliPendingSentCommands = new ConcurrentHashMap<>();
            this.cliCompletedSentCommands = new ConcurrentLinkedQueue<>();

            // For receiving commands
            this.srvPendingRecvCommands = new ConcurrentLinkedQueue<>();
            this.srvCompletedRecvCommands = new ConcurrentLinkedQueue<>();
            this.srvRecvCommandsMap = new ConcurrentHashMap<>();
            this.receiverLock = new Object();

        }//

        @Override
        public String name() {
            return this.name;
        }

        @Override
        public void bindToRemoteNode(Address remoteAddress) {
            DatagramTransport.RemoteNode node = transport.newRemoteNode(remoteAddress());
            this.remoteNode = node; // Could override an existing one. That's ok
        }

        @Override
        public Address remoteAddress() {
            DatagramTransport.RemoteNode rn = this.remoteNode; // can be null
            return rn == null ? null : rn.remoteAddress();
        }

        @Override
        public void sendMessage(String msgType, String message) {
            DatagramTransport.RemoteNode rn = this.remoteNode; // can be null

            if (!this.closed && validSendParams(msgType, message, rn, "DISCARDING_SEND_MESSAGE")) {
                MessageHeader hdr = new MessageHeader(MessageHeader.DgType.DG_MSG, name, msgType, 0,
                        MessageHeader.CmdStatus.STATUS_NOVALUE);
                rn.send(hdr.serialize(message));
            }

        }

        @Override
        public void sendMessage(String msgType, String message, Address addr) {
            // TODO Auto-generated method stub

        }

        @Override
        public void close() {
            // TODO: If necessary remote nodes of channel closing.
            // then remove ourselves from the channels queue.
            log.trace("Removing channel " + name + " from list of channels.");
            channels.remove(name, this);
            this.closed = true;
        }

        @Override
        public ReceivedMessage pollReceivedMessage() {
            return this.closed ? null : pendingRecvMessages.poll();
        }

        @Override
        public SentCommand sendCommand(String cmdType, String command, boolean addToCompletionQueue) {

            if (this.closed) {
                throw new IllegalStateException("Channel is closed");
            }

            if (!isListening()) {
                throw new IllegalStateException(
                        "Attempt to call sendCommand on a RobotComm instance that is not listening.");

            }
            DatagramTransport.RemoteNode rn = this.remoteNode; // can be null

            if (validSendParams(cmdType, command, rn, "DISCARDING_SEND_COMMAND")) {
                long cmdId = cliNewCommandId();
                SentCommandImplementation sc = new SentCommandImplementation(cmdId, cmdType, command,
                        addToCompletionQueue);
                this.cliPendingSentCommands.put(cmdId, sc);
                cliSendCmd(sc);
                return sc;
            } else {
                throw new IllegalArgumentException();
            }
        }

        @Override
        public SentCommand pollCompletedCommand() {
            if (this.closed) {
                return null; // ********* EARLY RETURN
            }
            SentCommand sc = this.cliCompletedSentCommands.poll();
            if (sc != null) {
                log.trace("CLI_COMPLETED_COMMAND", "cmd: " + sc.cmdType());
            }
            return sc;
        }

        @Override
        public void startReceivingMessages() {
            if (this.closed) {
                throw new IllegalStateException("Attempt to start receiving on a closed channel.");
            }

            this.receiveMessages = true;
        }

        @Override
        public void stopReceivingMessages() {
            // TODO Close resources related to receiving
            this.receiveMessages = false;

        }

        @Override
        public void startReceivingCommands() {
            if (this.closed) {
                throw new IllegalStateException("Attempt to start receiving on a closed channel.");
            }
            this.receiveCommands = true;

        }

        @Override
        public void stopReceivingCommands() {
            // TODO Close resources related to receiving,
            // and cancel all outstanding client and server-side command state.
            this.receiveCommands = false;
        }

        @Override
        public ReceivedCommand pollReceivedCommand() {
            if (this.closed) {
                return null; // EARLY RETURN
            }

            ReceivedCommandImplementation rc = null;
            // We skip past commands that don't have status PENDING_QUEUED. This really
            // should not happen
            // normally, but could happen if we support remote cancelling of requests. Or
            // perhaps we are
            // closing the channel concurrently with this and in that process cancelling all
            // queued commands?
            while ((rc = srvPendingRecvCommands.poll()) != null) {
                synchronized (rc) {
                    if (rc.status == MessageHeader.CmdStatus.STATUS_PENDING_QUEUED) {
                        rc.status = MessageHeader.CmdStatus.STATUS_PENDING_COMPUTING;
                        break;
                    }
                }
            }
            if (rc != null) {
                log.trace("SRV_CMD_POLLED", "cmdType: " + rc.cmdType);
            }
            return rc;
        }

        private long cliNewCommandId() {
            return nextCmdId.getAndIncrement();
        }

        private void cliSendCmd(SentCommandImplementation sc) {
            MessageHeader hdr = new MessageHeader(MessageHeader.DgType.DG_CMD, name, sc.cmdType, sc.cmdId,
                    MessageHeader.CmdStatus.STATUS_NOVALUE);
            this.remoteNode.send(hdr.serialize(sc.cmd));           
        }

        // Client gets this
        void cliHandleReceivedCommandResponse(MessageHeader header, String msgBody, Address remoteAddr) {

            if (this.closed) {
                return;
            }

            if (header.isPending()) {
                SentCommandImplementation sc = this.cliPendingSentCommands.get(header.cmdId);
                if (sc != null) {
                    synchronized (sc) {
                        sc.updateLK(header, "");
                    }
                }
                // We don't respond to CMDRESP messages with pending status.
                return; // ************ EARLY RETURN *************
            }

            assert !header.isPending();

            SentCommandImplementation sc = this.cliPendingSentCommands.remove(header.cmdId);
            if (sc == null) {
                cliQueueCmdRespAck(header, remoteAddr);
                return; // ************ EARLY RETURN *************
            }

            synchronized (sc) {
                // If it *was* in the pending sent queue, it *must* be pending.
                assert sc.pending();
                sc.updateLK(header, msgBody);
                assert !sc.pending();
            }
            if (sc.addToCompletionQueue) {
                this.cliCompletedSentCommands.add(sc);
            }
        }

        private void cliQueueCmdRespAck(MessageHeader header, Address remoteAddr) {
            // TODO: Validate remoteAddr and add eventually send a CMDRESPACK.
            // For now we do nothing.
        }

        // Server gets this
        void srvHandleReceivedCommand(MessageHeader header, String msgBody, Address remoteAddr) {
            if (!this.receiveCommands) {
                return; // ************* EARLY RETURN
            }

            // TODO: we should incorporate the remoteAddr into the key :-(. Otherwise two
            // incoming commands
            // from different remote nodes could potentially clash. For the moment we don't
            // take this to account
            // because our own client generates completely random long cmdIds, so the risk
            // of collision is
            // miniscule. But it is completely valid for remote clients to generate very
            // simple cmdIds which could
            // easily collide.
            long cmdId = header.cmdId;
            ReceivedCommandImplementation rc = this.srvRecvCommandsMap.get(cmdId);

            if (rc != null) {
                srvSendCmdResp(rc);
                return; // ********* EARLY RETURN
            }

            // We haven't seen this request below, let's make a new one.

            ReceivedCommandImplementation rcNew = new ReceivedCommandImplementation(cmdId, header.msgType, msgBody,
                    remoteAddr, this);
            ReceivedCommandImplementation rcPrev = this.srvRecvCommandsMap.putIfAbsent(cmdId, rcNew);
            if (rcPrev != null) {
                // In the tiny amount of time before the previous check, another CMD for this
                // same cmdID was
                // processed and inserted into the map! We will simply drop this current one. No
                // need to respond
                // because clearly we only recently inserted it into the map.
                return; // ********** EARLY RETURN
            }

            log.trace("SRV_CMD_QUEUED", "cmdType: " + rcNew.cmdType + " cmdId: " + rcNew.cmdId);
            this.srvPendingRecvCommands.add(rcNew);
        }

        // The server command has been completed locally (i.e., on the server)
        public void srvHandleComputedResponse(ReceivedCommandImplementation rc, String respType, String resp) {

            log.trace("SRV_COMPUTING_DONE", "cmdType: " + rc.cmdType);
            if (this.closed) {
                return; // EARLY return
            }

            boolean fNotify = false;

            synchronized (rc) {
                if (rc.status == MessageHeader.CmdStatus.STATUS_PENDING_COMPUTING) {
                    rc.status = MessageHeader.CmdStatus.STATUS_COMPLETED;
                    rc.respType = respType;
                    rc.respBody = resp;
                    fNotify = true;
                }
            }

            if (fNotify) {
                srvSendCmdResp(rc);
            }

        }

        private void srvSendCmdResp(ReceivedCommandImplementation rc) {
            MessageHeader header = new MessageHeader(MessageHeader.DgType.DG_CMDRESP, this.name, rc.respType, rc.cmdId,
                    rc.status);
            rc.rn.send(header.serialize(rc.respBody));
        }

        // Server gets this
        void srvHandleReceivedCommandResponseAck(MessageHeader header, String msgBody, Address remoteAddr) {
            // TODO Auto-generated method stub

        }

        void handleReceivedMessage(MessageHeader header, String msgBody, Address remoteAddr) {
            if (this.receiveMessages) {
                ReceivedMessageImplementation rm = new ReceivedMessageImplementation(header.msgType, msgBody,
                        remoteAddr, this);
                this.pendingRecvMessages.add(rm);
            }

        }

        void periodicWork() {
            if (!this.closed) {
                cliHandleRetransmits();
            }
        }

        private void cliHandleRetransmits() {
            this.cliPendingSentCommands.forEachValue(0, sc -> {
                if (sc.pending()) {
                    cliSendCmd(sc);
                }
            });
        }
    }

    private boolean validSendParams(String msgType, String message, RemoteNode rn, String logMsgType) {
        final String BAD_MSGTYPE_CHARS = ", \t\f\n\r";
        boolean ret = false;

        if (rn == null) {
            log.trace(logMsgType, "No default send node");
        } else if (containsChars(msgType, BAD_MSGTYPE_CHARS)) {
            log.trace(logMsgType, "Message type has invalid chars: " + msgType);
        } else {
            ret = true;
        }

        return ret;
    }

    /**
     * Creates a remote UDP port - for sending
     * 
     * @param nameOrAddress
     *            - either a name to be resolved or an dotted IP address
     * @param port
     *            - port number (0-65535)
     * @return remote port object
     */
    public static Address makeUDPRemoteAddress(String nameOrAddress, int port) {
        return null;
    }

    private static boolean containsChars(String str, String chars) {
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (chars.indexOf(c) >= 0) {
                return true;
            }

        }
        return false;
    }

}

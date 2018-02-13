// RobotComm - UDP based communications for robot status and commands.
// Created by Joseph M. Joy (https://github.com/josephmjoy)
package com.rinworks.robotutils;

import java.io.Closeable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Class to implement simple 2-way message passing over UDP
 *
 */
public class RobotComm implements Closeable {
    private static final String PROTOCOL_SIGNATURE = "3wIC"; // About 1 of 10E7 combnations.
    private final DatagramTransport transport;
    private final StructuredLogger.Log log;
    private boolean commClosed; // set to false when close() is called.
    // This lives here and NOT in a channel so id's can never repeat once
    // an instance of RobotComm has been created. If it were kept in
    // a channel, then if a channel is later created with the same name
    // it's command IDs could overlap with previous incarnations.
    private final AtomicLong nextCmdId;

    private final ConcurrentHashMap<String, ChannelImplementation> channels;
    private final Object listenLock;

    private volatile DatagramTransport.Listener listener;

    interface Address {
        String stringRepresentation();
    };

    /**
     * Sends a dynamically generated message periodically. The period was set when
     * the underlying object was created. Pause and resume may be called in any
     * order and from multiple threads, though results of doing so will be
     * unpredictable. The messages themselves will be sent as whole units.
     *
     */
    interface PeriodicSender extends Closeable {
        void pause();

        void resume();

        void close(); // will cancel all further attempts to start/stop.
    }

    interface SentCommand {
        enum COMMAND_STATUS {
            STATUS_PENDING, STATUS_COMPLETED, STATUS_ERROR_TIMEOUT, STATUS_ERROR_COMM, STATUS_CANCELED
        }

        // These are set when the command is submitted.
        String cmdType();

        String command();

        long submittedTime();

        // Status can be checked anytime.
        COMMAND_STATUS status();

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

        SentCommand sendCommand(String cmdType, String command);

        PeriodicSender periodicSend(int period, String msgType, Supplier<String> messageSource);

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
        this.nextCmdId = new AtomicLong(System.currentTimeMillis());
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

    public void startListening() {
        DatagramTransport.Listener listener = null;
        synchronized (listenLock) {
            if (this.listener == null) {
                listener = this.transport.newListener(null);
                this.listener = listener;
            }
        }

        if (listener != null) {
            log.info("STARTED LISTENING");

            listener.listen((String msg, DatagramTransport.RemoteNode rn) -> {
                Address remoteAddr = rn.remoteAddress();
                if (!msg.startsWith(PROTOCOL_SIGNATURE)) {
                    log.trace("WARN_DROPPING_RECIEVED_MESSAGE", "Incorrect protocol signature.");
                    return; // EARLY RETURN
                }
                int headerLength = msg.indexOf("\n");
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
                    } else {
                        // TODO: implement other types of messages
                        assert false; // we have already validated the message, so shouldn't get here.
                    }
                }
            });
        }
    }

    public void stopListening() {
        DatagramTransport.Listener listener = null;
        synchronized (listenLock) {
            if (this.listener != null) {
                listener = this.listener;
                this.listener = null;
            }
        }
        log.info("STOPPED LISTENING");
        if (listener != null) {
            listener.close();
        }
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

    private void handleMsgToUnknownChannel(MessageHeader header) {
        // TODO Auto-generated method stub

    }

    static class MessageHeader {
        enum DgType {
            DG_MSG,
            // DG_CMD,
            // DG_CMDRESP,
            // DG_CMDRESPACK
        };

        final static String STR_DG_MSG = "MSG";
        final static String STR_DG_CMD = "CMD";
        final static String STR_CMDRESP = "CMDRESP";
        final static String STR_CMDRESPACK = "CMDRESPACK";

        final static int INDEX_PROTO = 0;
        final static int INDEX_DG_TYPE = 1;
        final static int INDEX_CHANNEL = 2;
        final static int INDEX_MSG_TYPE = 3;
        final static int INDEX_CMDID = 4;
        final static int INDEX_CMDSTATUS = 5;

        final DgType dgType;
        final String channel;
        final String msgType;
        final long cmdId;

        enum CmdStatus {
            STATUS_OK, STATUS_PENDING, STATUS_COMPLETED, STATUS_REJECTED, STATUS_NOVALUE // Don't use
        };

        final CmdStatus status;

        private MessageHeader(DgType dgType, String channel, String msgType, long cmdId, CmdStatus status) {
            this.dgType = dgType;
            this.channel = channel;
            this.msgType = msgType;
            this.cmdId = cmdId;
            this.status = status;
        }

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
            if (dgTypeStr.equals(STR_DG_MSG)) {
                dgType = DgType.DG_MSG;
            } else {
                log.trace("WARN_DROPPING_RECIEVED_MESSAGE", "Malformed header");
                return null; // ************ EARLY RETURN
            }

            String channel = header[INDEX_CHANNEL];
            if (channel.length() == 0) {
                log.trace("WARN_DROPPING_RECEIVED_MESSGAGE", "Missing channel name");
                return null; // ************ EARLY RETURN
            }

            String msgType = header[INDEX_MSG_TYPE];
            // We do not do special error checking on user msgType...

            // TODO finish other types of messages.
            return new MessageHeader(dgType, channel, msgType, 0, CmdStatus.STATUS_NOVALUE);
        }

        public String serialize(String additionalText) {
            String dgTypeStr = dgTypeToString();
            String cmdIdStr = cmdIdToString();
            String statusStr = statusToString() + '\n' + additionalText;
            return String.join(",", PROTOCOL_SIGNATURE, dgTypeStr, this.channel, this.msgType, cmdIdStr, statusStr);
        }

        private String statusToString() {
            // TODO Implement this
            return "";
        }

        private String cmdIdToString() {
            // TODO - implement - write a HEX value
            return "";
        }

        private String dgTypeToString() {
            if (this.dgType == DgType.DG_MSG) {
                return STR_DG_MSG;
            } else {
                // TODO - finish other types
                return "";
            }
        }
    }

    private class ChannelImplementation implements Channel {
        private final String name;
        private DatagramTransport.RemoteNode remoteNode;

        // Receiving messages
        private final ConcurrentLinkedQueue<ReceivedMessage> pendingRecvMessages;

        // Sending of commands
        private final ConcurrentHashMap<Long, SentCommand> pendingSentCommands;

        // Receiving of commands
        private final ConcurrentHashMap<Long, ReceivedCommand> recvCommandsMap;
        private final ConcurrentLinkedQueue<ReceivedCommand> pendingRecvCommands;
        private final ConcurrentLinkedQueue<ReceivedCommand> workingRecvCommands;
        private final ConcurrentLinkedQueue<ReceivedCommand> completedRecvCommands;

        // Should be ...
        // 0 (not receiving anything)
        // >0 receiving one or more things = messages, commends or command-responses.
        final Object receiverLock;
        DatagramTransport.Listener listener;
        private boolean receiveMessages;

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

        public ChannelImplementation(String channelName) {
            this.name = channelName;
            this.remoteNode = null;

            // For receiving messages
            this.pendingRecvMessages = new ConcurrentLinkedQueue<>();

            // For sending commands
            this.pendingSentCommands = new ConcurrentHashMap<>();

            // For receiving commands
            this.pendingRecvCommands = new ConcurrentLinkedQueue<>();
            this.workingRecvCommands = new ConcurrentLinkedQueue<>();
            this.completedRecvCommands = new ConcurrentLinkedQueue<>();
            this.recvCommandsMap = new ConcurrentHashMap<>();

            this.receiverLock = new Object();

        }//

        public void handleReceivedMessage(MessageHeader header, String msgBody, Address remoteAddr) {
            if (this.receiveMessages) {
                ReceivedMessage rm = new ReceivedMessageImplementation(header.msgType, msgBody, remoteAddr, this);
                this.pendingRecvMessages.add(rm);
            }

        }

        @Override
        public String name() {
            return this.name;
        }

        @Override
        public Address remoteAddress() {
            DatagramTransport.RemoteNode rn = this.remoteNode; // can be null
            return rn == null ? null : rn.remoteAddress();
        }

        @Override
        public ReceivedMessage pollReceivedMessage() {
            return pendingRecvMessages.poll();
        }

        // - "1309JHI,MY_CHANNEL,MSG,MY_MSG_TYPE"
        // - "1309JHI,MY_CHANNEL,CMD,MY_COMMAND_TYPE,0x2888AB89"
        // - "1309JHI,MY_CHANNEL,CMDRESP,MY_RESPONSE_TYPE,0x2888AB89,OK"

        @Override
        public void sendMessage(String msgType, String message) {
            DatagramTransport.RemoteNode rn = this.remoteNode; // can be null
            final String BAD_MSGTYPE_CHARS = ", \t\f\n\r";

            if (rn == null) {
                log.trace("DISCARDING_SEND_MESSAGE", "No default send node");
            } else if (containsChars(msgType, BAD_MSGTYPE_CHARS)) {
                log.trace("DISCARDING_SEND_MESSAGE", "Message type has invalid chars: " + msgType);
            } else {
                MessageHeader hdr = new MessageHeader(MessageHeader.DgType.DG_MSG, name, msgType, 0,
                        MessageHeader.CmdStatus.STATUS_NOVALUE);
                rn.send(hdr.serialize(message));
            }

        }

        @Override
        public SentCommand sendCommand(String cmdType, String command) {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public PeriodicSender periodicSend(int period, String msgType, Supplier<String> messageSource) {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public void close() {
            // TODO: If necessary remote nodes of channel closing.
            // then remove ourselves from the channels queue.
            log.trace("Removing channel " + name + " from list of channels.");
            channels.remove(name, this);
        }

        @Override
        public void startReceivingMessages() {
            this.receiveMessages = true;
        }

        @Override
        public void stopReceivingMessages() {
            // TODO Auto-generated method stub
            this.receiveMessages = false;

        }

        @Override
        public void startReceivingCommands() {
            // TODO Auto-generated method stub

        }

        @Override
        public void stopReceivingCommands() {
            // TODO Auto-generated method stub

        }

        @Override
        public void bindToRemoteNode(Address remoteAddress) {
            DatagramTransport.RemoteNode node = transport.newRemoteNode(remoteAddress());
            this.remoteNode = node; // Could override an existing one. That's ok
        }

        @Override
        public ReceivedCommand pollReceivedCommand() {
            ReceivedCommand rCmd = pendingRecvCommands.poll();
            if (rCmd != null) {
                this.workingRecvCommands.add(rCmd);
            }
            return rCmd;
        }

        @Override
        public void sendMessage(String msgType, String message, Address addr) {
            // TODO Auto-generated method stub

        }

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

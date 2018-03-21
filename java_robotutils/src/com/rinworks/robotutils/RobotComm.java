// RobotComm - Datagram based communications for robot status and commands.
// Created by Joseph M. Joy (https://github.com/josephmjoy)
package com.rinworks.robotutils;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.rinworks.robotutils.RobotComm.DatagramTransport.Address;
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
    private final Random rand; // Used for generating command Ids and random delays

    private volatile boolean listening;



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

        // This channel will only communicate with the specified remote node,
        // including received messages and commands.
        // Can be changed on the fly. Set to null to clear.
        void bindToRemoteNode(Address remoteAddress);

        Address remoteAddress(); // Can be null

        // Sending and receiving messages
        ReceivedMessage pollReceivedMessage();

        // Will drop message if not bound to a remote node.
        void sendMessage(String msgType, String message);

        void sendMessage(String msgType, String message, Address addr);

        // Commands: Client-side
        SentCommand submitCommand(String cmdType, String command, boolean addToCompletionQueue);

        SentCommand pollCompletedCommand();

        SentCommand submitRtCommand(String cmdType, String command, int timeout, Consumer<SentCommand> onComplete);

        // Commands: Server-side
        void startReceivingCommands();

        void stopReceivingCommands(); // will drop incoming commands in queue

        ReceivedCommand pollReceivedCommand();

        void startReceivingRtCommands(Consumer<ReceivedCommand> incoming);

        void stopReceivingRtCommands();

        void close();
    }

    public interface DatagramTransport extends Closeable {
        
        interface Address {
            String stringRepresentation();
        }

        interface RemoteNode {
            Address remoteAddress();

            void send(String msg);
        }


        Address resolveAddress(String address);
        
        /**
         * Starts listening for incoming datagrams. This will likely use up resources, such as a dedicated
         * thread, depending on the implementation.
         * 
         * @param handler
         *            called when a message arrives. Will likely be called in some other
         *            thread's context. The handler is expected NOT to block. If time
         *            consuming operations need to be performed, queue the message for
         *            further processing, or implement a state machine. The handler
         *            *may* be reentered or called concurrently from another thread.
         *            Call stopListening to stop new messages from being received.
         */
        void startListening(BiConsumer<String, RemoteNode> handler);
        
        void stopListening();

        RemoteNode newRemoteNode(Address remoteAddress);

        void close(); // Closes all open listeners and remote notes.
    }

    public RobotComm(DatagramTransport transport, StructuredLogger.Log log) {
        this.transport = transport;
        this.log = log;
        this.listenLock = new Object();
        this.rand = new Random();
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
            ch = new ChannelImplementation(this, channelName, transport, log);
            ChannelImplementation prevCh = this.channels.put(channelName, ch);
            if (prevCh != null) {
                ch = prevCh;
            }
        }
        return ch;
    }

    // Idempotent, thread safe
    public void startListening() {
        boolean start = false;
        synchronized (listenLock) {
            if (!this.listening) {
                start = true;
                this.listening = true;
                start = true;
            }
        }

        if (start) {
            log.info("STARTING LISTENING");

            this.transport.startListening((String msg, DatagramTransport.RemoteNode rn) -> {
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

                MessageHeader header = MessageHeader.parse(headerStr, log);
                if (header == null) {
                    return; // EARLY RETURN
                }
                ChannelImplementation ch = channels.get(header.channel);

                if (ch == null) {
                    handleMsgToUnknownChannel(header);
                } else {

                    String msgBody = msg.substring(headerLength + 1);
                    if (header.dgType == MessageHeader.DgType.DG_RTCMD) {
                        ch.server.handleReceivedRTCMD(header, msgBody, rn);
                    } else if (header.dgType == MessageHeader.DgType.DG_RTCMDRESP) {
                        ch.client.handleReceivedRTCMDRESP(header, msgBody, rn);
                    } else if (header.dgType == MessageHeader.DgType.DG_MSG) {
                        ch.handleReceivedMessage(header, msgBody, rn);
                    } else if (header.dgType == MessageHeader.DgType.DG_CMD) {
                        ch.server.handleReceivedCMD(header, msgBody, rn);
                    } else if (header.dgType == MessageHeader.DgType.DG_CMDRESP) {
                        ch.client.handleReceivedCMDRESP(header, msgBody, rn);
                    } else if (header.dgType == MessageHeader.DgType.DG_CMDRESPACK) {
                        ch.server.handleReceivedCMDRESPACK(header, msgBody, rn);
                    } else {
                        assert false; // we have already validated the message, so shouldn't get here.
                    }
                }
            });
        }
    }

    // Idempotent, thread safe
    public void stopListening() {
        boolean stop = false;
        synchronized (listenLock) {
            if (this.listening) {
                this.listening = false;
                stop = true;
            }
        }        
        if (stop) {
            log.info("STOPPING LISTENING");
            this.transport.stopListening();
        }
    }

    public boolean isListening() {
        // Not synchronized as there is no point
        return this.listening;
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

    public static class ServerStatistics {
        public final long rcvdCommands;
        public final long rcvdCMDs;
        public final long sentCMDRESPs;
        public final long rcvdCMDRESPACKs;
        public final int curSvrRecvdCmdMapSize;
        public final int curSvrRcvdCmdIncomingQueueSize;
        public final int curSvrRcvdCmdCompletedQueueSize;

        ServerStatistics(long rcvdCommands, long rcvdCMDs, long sentCMDRESPs, long rcvdCMDRESPACKs,
                int curSvrRecvdCmdMapSize, int curSvrRcvdCmdIncomingQueueSize, int curSvrRcvdCmdCompletedQueueSize) {
            this.rcvdCommands = rcvdCommands;
            this.rcvdCMDs = rcvdCMDs;
            this.sentCMDRESPs = sentCMDRESPs;
            this.rcvdCMDRESPACKs = rcvdCMDRESPACKs;
            this.curSvrRecvdCmdMapSize = curSvrRecvdCmdMapSize;
            this.curSvrRcvdCmdIncomingQueueSize = curSvrRcvdCmdIncomingQueueSize;
            this.curSvrRcvdCmdCompletedQueueSize = curSvrRcvdCmdCompletedQueueSize;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (rcvdCommands > 0) {
                sb.append(" rc: " + rcvdCommands);
            }

            if (rcvdCMDs > 0) {
                sb.append(" rC: " + rcvdCMDs);
            }

            if (sentCMDRESPs > 0) {
                sb.append(" sCR: " + sentCMDRESPs);
            }

            if (rcvdCMDRESPACKs > 0) {
                sb.append(" rCRA: " + rcvdCMDRESPACKs);
            }

            if (curSvrRecvdCmdMapSize > 0) {
                sb.append(" srvCMap: " + curSvrRecvdCmdMapSize);
            }
            if (curSvrRcvdCmdIncomingQueueSize > 0) {
                sb.append(" srvIQ: " + curSvrRcvdCmdIncomingQueueSize);
            }
            if (curSvrRcvdCmdCompletedQueueSize > 0) {
                sb.append(" srvCCQ: " + curSvrRcvdCmdCompletedQueueSize);
            }

            return sb.toString();
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
                int curCliSentCmdMapSize, int curCliSentCmdCompletionQueueSize
                ) {
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

    public static class ClientRtStatistics {
        public final long approxSentRtCommands;
        public final long approxSendRTCMDs;
        public final long approxRcvdRTCMDRESPs;
        public final long approxRtTimeouts; 
        public final int curCliSentRtCmdMapSize;
         
        ClientRtStatistics(
        long approxSentRtCommands,
        long approxSendRTCMDs,
        long approxRcvdRTCMDRESPs,
        long approxRtTimeouts,
        int curCliSentRtCmdMapSize) {
            this.approxSentRtCommands = approxSentRtCommands;
            this.approxSendRTCMDs = approxSendRTCMDs;
            this.approxRcvdRTCMDRESPs = approxRcvdRTCMDRESPs;
            this.approxRtTimeouts = approxRtTimeouts;
            this.curCliSentRtCmdMapSize = curCliSentRtCmdMapSize;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (approxSentRtCommands > 0) {
                sb.append(" srtc: " + approxSentRtCommands);
            }
            if (approxSendRTCMDs > 0) {
                sb.append(" srtC: " + approxSendRTCMDs);
            }
            if (approxRcvdRTCMDRESPs > 0) {
                sb.append(" rRTCR: " + approxRcvdRTCMDRESPs);
            }
            if (approxRtTimeouts > 0) {
                sb.append(" sRTTO: " + approxRtTimeouts);
            }
            if (curCliSentRtCmdMapSize > 0) {
                sb.append(" cliRTCMap: " + curCliSentRtCmdMapSize);
            }

            return sb.toString();
        }
    }
    
    public static class ChannelStatistics {
        public final String channelName;
        public final long sentMessages;
        public final long rcvdMessages;
        public final ClientStatistics clientStats;
        public final ClientRtStatistics clientRtStats;
        public final ServerStatistics serverStats;

        ChannelStatistics(String channelName, long sentMessages, long rcvdMessages, ClientStatistics clientStats,
                ClientRtStatistics clientRtStats,
                ServerStatistics serverStats) {
            this.channelName = channelName;
            this.sentMessages = sentMessages;
            this.rcvdMessages = rcvdMessages;
            this.clientStats = clientStats;
            this.clientRtStats = clientRtStats;
            this.serverStats = serverStats;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ch: " + channelName);
            if (sentMessages > 0) {
                sb.append(" sm: " + sentMessages);
            }
            if (rcvdMessages > 0) {
                sb.append(" rm: " + rcvdMessages);
            }
            sb.append(" ");
            sb.append(clientStats.toString());
            sb.append(" ");
            sb.append(clientRtStats.toString());
            sb.append(" ");
            sb.append(serverStats.toString());
            return sb.toString();
        }
    }

    List<ChannelStatistics> getChannelStatistics() {
        ArrayList<ChannelStatistics> stats = new ArrayList<>();
        for (ChannelImplementation ch : channels.values()) {
            stats.add(ch.getStats());
        }
        return stats;
    }

    private void handleMsgToUnknownChannel(MessageHeader header) {
        // TODO Auto-generated method stub

    }

    static class MessageHeader {
        enum DgType {
            DG_MSG, DG_CMD, DG_CMDRESP, DG_RTCMD, DG_RTCMDRESP, DG_CMDRESPACK
        }

        static final String STR_DG_MSG = "MSG";
        static final String STR_DG_CMD = "CMD";
        static final String STR_DG_CMDRESP = "CMDRESP";
        static final String STR_DG_RTCMD = "RTCMD";
        static final String STR_DG_RTCMDRESP = "RTCMDRESP";
        static final String STR_DG_CMDRESPACK = "CMDRESPACK";
        static final String STR_MSGTYPE_IDLIST = "IDLIST"; // body of CMDRESP is a list of IDs.

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
        }

        final CmdStatus status;

        MessageHeader(DgType dgType, String channel, String msgType, long cmdId, CmdStatus status) {
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
            return (this.dgType == DgType.DG_CMDRESP || this.dgType == DgType.DG_RTCMDRESP) && !!!isPending();
        }

        // Examples
        // - "1309JHI,MY_CHANNEL,MSG,MY_MSG_TYPE"
        // - "1309JHI,MY_CHANNEL,CMD,MY_COMMAND_TYPE,2888AB89"
        // - "1309JHI,MY_CHANNEL,CMDRESP,MY_RESPONSE_TYPE,2888AB89,OK"

        static MessageHeader parse(String headerStr, StructuredLogger.Log log) {
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
            } else if (dgTypeStr.equals(STR_DG_RTCMD)) {
                dgType = DgType.DG_RTCMD;
                getCmdId = true;
            } else if (dgTypeStr.equals(STR_DG_RTCMDRESP)) {
                dgType = DgType.DG_RTCMDRESP;
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
            if (dgType == DgType.DG_CMDRESPACK && !msgType.equals(STR_MSGTYPE_IDLIST)) {
                log.trace("WARN_DROPPING_RECEIVED_MESSGAGE", "Unexpected CMDRESPACK message type");
                return null; // ************ EARLY RETURN
            }

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
                    log.trace("WARN_DROPPING_RECIEVED_MESSAGE", "Malformed header - missing [rt]cmd status");
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
            } else if (this.dgType == DgType.DG_RTCMD) {
                return STR_DG_RTCMD;
            } else if (this.dgType == DgType.DG_RTCMDRESP) {
                return STR_DG_RTCMDRESP;
            } else if (this.dgType == DgType.DG_CMDRESPACK) {
                return STR_DG_CMDRESPACK;
            } else {
                assert false;
                return "UNKNOWN";
            }
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

    static boolean containsChars(String str, String chars) {
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (chars.indexOf(c) >= 0) {
                return true;
            }

        }
        return false;
    }

    void removeChannel(String name, ChannelImplementation ch) {
        this.channels.remove(name, ch);
    }

}

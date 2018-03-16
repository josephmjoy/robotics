// Implementation of RobotComm channels. Only called from RobotComm.
// Created by Joseph M. Joy (https://github.com/josephmjoy)

package com.rinworks.robotutils;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import com.rinworks.robotutils.RobotComm.Address;
import com.rinworks.robotutils.RobotComm.Channel;
import com.rinworks.robotutils.RobotComm.ChannelStatistics;
import com.rinworks.robotutils.RobotComm.DatagramTransport;
import com.rinworks.robotutils.RobotComm.MessageHeader;
import com.rinworks.robotutils.RobotComm.ReceivedCommand;
import com.rinworks.robotutils.RobotComm.ReceivedMessage;
import com.rinworks.robotutils.RobotComm.SentCommand;
import com.rinworks.robotutils.StructuredLogger.Log;
import com.rinworks.robotutils.RobotComm.DatagramTransport.RemoteNode;

class ChannelImplementation implements Channel {
    private final RobotComm rc;
    private final DatagramTransport transport;
    private final StructuredLogger.Log log;
    private final String name;
    private DatagramTransport.RemoteNode remoteNode;
    final CommClientImplementation client;
    CommServerImplementation server;

    // Receiving messages
    private final ConcurrentLinkedQueue<ReceivedMessageImplementation> pendingRecvMessages;

    // packet. 25 => msg body size of just under 500 bytes,
    // as ids are encoded as 16-digit hex numbers delimited
    // by the newline char.

    private boolean receiveMessages;
    private boolean closed;

    // These are purely for statistics reporting
    // They are not incremented atomically, so are approximate
    private volatile long approxSentMessages;
    private volatile long approxRcvdMessages;

    private class ReceivedMessageImplementation implements ReceivedMessage {
        private final String msg;
        private final String msgType;
        private final RemoteNode rn;
        private long recvdTimeStamp;
        private final Channel ch;

        ReceivedMessageImplementation(String msgType, String msg, RemoteNode rn, Channel ch) {
            this.msg = msg;
            this.msgType = msgType;
            this.rn = rn;
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
            return this.rn.remoteAddress();
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

    // Server Side
    public ChannelImplementation(RobotComm rc, String channelName, DatagramTransport transport, Log log) {
        this.rc = rc;
        this.name = channelName;
        this.transport = transport;
        this.log = log;
        this.remoteNode = null;
        this.client = new CommClientImplementation(channelName, log);
        this.server = new CommServerImplementation(this, log);

        // For receiving messages
        this.pendingRecvMessages = new ConcurrentLinkedQueue<>();

    }

    ChannelStatistics getStats() {

        return new ChannelStatistics(this.name, this.approxSentMessages, this.approxRcvdMessages,
                this.client.getStats(), this.server.getStats());
    }

    @Override
    public String name() {
        return this.name;
    }

    @Override
    public void bindToRemoteNode(Address remoteAddress) {
        DatagramTransport.RemoteNode node = transport.newRemoteNode(remoteAddress());
        this.remoteNode = node; // Could override an existing one. That's ok
        client.bindToRemoteNode(node);
    }

    @Override
    public Address remoteAddress() {
        return client.remoteAddress();
    }

    @Override
    public void sendMessage(String msgType, String message) {
        DatagramTransport.RemoteNode rn = this.remoteNode; // can be null

        if (!this.closed && validSendParams(msgType, message, rn, "DISCARDING_SEND_MESSAGE")) {
            MessageHeader hdr = new MessageHeader(MessageHeader.DgType.DG_MSG, name, msgType, 0,
                    MessageHeader.CmdStatus.STATUS_NOVALUE);
            this.approxSentMessages++;
            rn.send(hdr.serialize(message));
        }

    }

    @Override
    public void sendMessage(String msgType, String message, Address addr) {
        log.err("UNIMPLEMENTED", "sendMessage #QwWV");
    }

    @Override
    public void close() {
        log.trace("Removing channel " + name + " from list of channels.");
        this.client.close();
        rc.removeChannel(name, this);
        this.closed = true;
    }

    @Override
    public ReceivedMessage pollReceivedMessage() {
        if (!this.receiveMessages) {
            throw new IllegalStateException("Attempt to poll for received messages when listening is not enabled.");
            // ********** EARLY EXCEPTION
        }
        return this.closed ? null : pendingRecvMessages.poll();
    }

    @Override
    public SentCommand submitCommand(String cmdType, String command, boolean addToCompletionQueue) {
        return this.client.submitCommand(cmdType, command, addToCompletionQueue);
    }

    @Override
    public SentCommand submitRtCommand(String cmdType, String command, int timeout, Consumer<SentCommand> onComplete) {
        return this.client.submitRtCommand(cmdType, command, timeout, onComplete);
    }

    @Override
    public SentCommand pollCompletedCommand() {
        if (this.closed) {
            return null; // ********* EARLY RETURN
        }
        return this.client.pollCompletedCommand();
    }

    @Override
    public void startReceivingMessages() {
        if (this.closed) {
            throw new IllegalStateException("Attempt to start receiving on a closed channel.");
        }

        this.receiveMessages = true;
    }

    void handleReceivedMessage(MessageHeader header, String msgBody, RemoteNode rn) {
        if (this.receiveMessages) {
            ReceivedMessageImplementation rm = new ReceivedMessageImplementation(header.msgType, msgBody, rn, this);
            this.approxRcvdMessages++;
            this.pendingRecvMessages.add(rm);
        }

    }

    void periodicWork() {
        if (!this.closed) {
            this.client.periodicWork();
            this.server.periodicWork();
        }
    }

    @Override
    public void stopReceivingMessages() {
        // TODO Close resources related to receiving
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
    public ReceivedCommand pollReceivedCommand() {
        return server.pollReceivedCommand();
    }

    private boolean validSendParams(String msgType, String message, RemoteNode rn, String logMsgType) {
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

    @Override
    public void startReceivingRtCommands(Consumer<ReceivedCommand> incoming) {
        this.server.startReceivingRtCommands(incoming);
    }

    @Override
    public void stopReceivingRtCommands() {
        this.server.stopReceivingRtCommands();
    }
}

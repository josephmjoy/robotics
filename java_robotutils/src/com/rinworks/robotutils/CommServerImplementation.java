// Implementation of RobotComm command server. Only called from RobotComm.
// Created by Joseph M. Joy (https://github.com/josephmjoy)

package com.rinworks.robotutils;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import com.rinworks.robotutils.RobotComm.Address;
import com.rinworks.robotutils.RobotComm.Channel;
import com.rinworks.robotutils.RobotComm.DatagramTransport;
import com.rinworks.robotutils.RobotComm.MessageHeader;
import com.rinworks.robotutils.RobotComm.ReceivedCommand;
import com.rinworks.robotutils.RobotComm.ServerStatistics;

class CommServerImplementation {
    
    private final DatagramTransport transport;
    private final StructuredLogger.Log log;
    private final Channel ch;
    private final ConcurrentHashMap<Long, ReceivedCommandImplementation> cmdMap;
    private final ConcurrentLinkedQueue<ReceivedCommandImplementation> pendingCmds;
    private final ConcurrentLinkedQueue<ReceivedCommandImplementation> completedCmds;
    private final AtomicLong receivedCmdCount; // aggregate and accurate number of received commands. Used to
                                                      // trigger purging completed commands
    
    // Logging strings used more than once.
    private static final String CMDTYPE_TAG = "cmdType: ";
    private static final String CMDID_TAG = "cmdId: ";
    private static final int PURGE_COMPLETED_COMMAND_THRESHOLD = 1000000; // Server ties to keep the list of

    private  boolean closed = false; // TODO proper init
    private volatile long approxRcvdCommands;
    private volatile long approxRcvdCMDs;
    private volatile long approxSentCMDRESPs;
    private volatile long approxRcvdCMDRESPACKs;
    private volatile long approxTrackedCommands;


    private class ReceivedCommandImplementation implements ReceivedCommand {

        private final long cmdId;
        private final String cmdBody;
        private final String cmdType;
        private final Address remoteAddress;
        private final DatagramTransport.RemoteNode rn;
        private long recvdTimeStamp;
        private final Channel ch;

        private MessageHeader.CmdStatus status;
        private String respType;
        private String respBody;
        public boolean gotRespAck;

        public ReceivedCommandImplementation(long cmdId, String msgType, String msgBody, Address remoteAddr,
                Channel ch) {
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
            handleComputedResponse(this, respType, resp);

        }

    }

    CommServerImplementation(Channel ch, DatagramTransport transport, StructuredLogger.Log log) {
        this.transport = transport;
        this.log = log;
        this.ch = ch;

        // For receiving commands
        this.pendingCmds = new ConcurrentLinkedQueue<>();
        this.completedCmds = new ConcurrentLinkedQueue<>();
        this.cmdMap = new ConcurrentHashMap<>();
        this.receivedCmdCount = new AtomicLong(0);
    }

    ServerStatistics getStats() {
        // TODO Auto-generated method stub
        return new ServerStatistics(approxRcvdCMDRESPACKs, approxRcvdCMDRESPACKs, approxRcvdCMDRESPACKs, approxRcvdCMDRESPACKs, cmdMap.size(), pendingCmds.size(), completedCmds.size());
    }

    ReceivedCommand pollReceivedCommand() {
        if (this.closed) {
            return null; // EARLY RETURN
        }

        ReceivedCommandImplementation rc = null;
        // We skip past commands that don't have status PENDING_QUEUED. This really
        // should not happen
        // normally, but could happen if we support remote canceling of requests. Or
        // perhaps we are
        // closing the channel concurrently with this and in that process canceling all
        // queued commands?
        while ((rc = pendingCmds.poll()) != null) {
            synchronized (rc) {
                if (rc.status == MessageHeader.CmdStatus.STATUS_PENDING_QUEUED) {
                    rc.status = MessageHeader.CmdStatus.STATUS_PENDING_COMPUTING;
                    break;
                }
            }
        }
        if (rc != null) {
            log.trace("SRV_CMD_POLLED", CMDTYPE_TAG + rc.cmdType);
        }
        return rc;
    }

    // Server gets this
    void handleReceivedCommand(MessageHeader header, String msgBody, Address remoteAddr) {
        if (this.closed) {
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
        this.approxRcvdCMDs++;
        long cmdId = header.cmdId;
        ReceivedCommandImplementation rc = this.cmdMap.get(cmdId);

        if (rc != null) {
            sendCmdResp(rc);
            return; // ********* EARLY RETURN
        }

        // We haven't seen this request below, let's make a new one.

        ReceivedCommandImplementation rcNew = new ReceivedCommandImplementation(cmdId, header.msgType, msgBody,
                remoteAddr, ch);
        ReceivedCommandImplementation rcPrev = this.cmdMap.putIfAbsent(cmdId, rcNew);
        if (rcPrev != null) {
            // In the tiny amount of time before the previous check, another CMD for this
            // same cmdID was
            // processed and inserted into the map! We will simply drop this current one. No
            // need to respond
            // because clearly we only recently inserted it into the map.
            return; // ********** EARLY RETURN
        }

        log.trace("SRV_CMD_QUEUED", CMDTYPE_TAG + rcNew.cmdType + " cmdId: " + rcNew.cmdId);
        this.approxRcvdCommands++;
        this.pendingCmds.add(rcNew);
        long totCmd = this.receivedCmdCount.incrementAndGet();
        if (totCmd % PURGE_COMPLETED_COMMAND_THRESHOLD == 0) {
            pruneCompletedCommands();
        }
    }

    // The server command has been completed locally (i.e., on the server)
    void handleComputedResponse(ReceivedCommandImplementation rc, String respType, String resp) {
    
        log.trace("SRV_COMPUTING_DONE", CMDTYPE_TAG + rc.cmdType);
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
            this.completedCmds.add(rc);
            sendCmdResp(rc);
        }
    
    }

    // Server gets this
    void handleReceivedCommandResponseAck(MessageHeader header, String msgBody, Address remoteAddr) {
        this.approxRcvdCMDRESPACKs++;
        try {
            String[] parts = msgBody.split("\n");
            for (String strId : parts) {
                long id = Long.parseUnsignedLong(strId, 16);
                ReceivedCommandImplementation rc = this.cmdMap.get(id);
                if (rc != null) {
                    rc.gotRespAck = true;
                    log.trace("SVR_CMCDRESPACK", CMDID_TAG + id + "Marking gotRespAck");
                } else {
                    log.trace("SVR_CMDRESPACK", CMDID_TAG + id + "not found");
                }
            }
        } catch (NumberFormatException e) {
            log.trace("SVR_CMDRESPACK", "error parsing command IDs");
        }

    }


    void periodicWork() {
        // We do not have any periodic work.
    }
    
    void close() {
        this.closed = true;
        // TODO: more cleanup;
    }

    // Walk the list of completed commands, oldest first, and kill enough to get
    // size down to half the max.
    // This call is relatively expensive.
    private void pruneCompletedCommands() {
        int count = this.completedCmds.size(); // O(n)
        ReceivedCommandImplementation rc = this.completedCmds.poll();
        int maxAllowed = PURGE_COMPLETED_COMMAND_THRESHOLD / 2;
        // Note that concurrently the queue could be growing or the map chould be
        // growing or shrinking.
        while (rc != null && count > maxAllowed) {
            log.trace("SRV_CMD_PURGE", CMDID_TAG + rc.cmdId);
            this.cmdMap.remove(rc.cmdId, rc);
            count--;
            rc = this.completedCmds.poll();
        }
    }

    private void sendCmdResp(ReceivedCommandImplementation rc) {
        MessageHeader header = new MessageHeader(MessageHeader.DgType.DG_CMDRESP, this.ch.name(), rc.respType, rc.cmdId,
                rc.status);
        this.approxSentCMDRESPs++;
        rc.rn.send(header.serialize(rc.respBody));
    }

}

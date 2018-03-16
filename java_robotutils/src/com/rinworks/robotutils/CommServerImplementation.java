// Implementation of RobotComm command server. Only called from RobotCom's ChannelImplementation
// Created by Joseph M. Joy (https://github.com/josephmjoy)

package com.rinworks.robotutils;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.rinworks.robotutils.RobotComm.Address;
import com.rinworks.robotutils.RobotComm.Channel;
import com.rinworks.robotutils.RobotComm.DatagramTransport;
import com.rinworks.robotutils.RobotComm.DatagramTransport.RemoteNode;
import com.rinworks.robotutils.RobotComm.MessageHeader;
import com.rinworks.robotutils.RobotComm.MessageHeader.CmdStatus;
import com.rinworks.robotutils.RobotComm.ReceivedCommand;
import com.rinworks.robotutils.RobotComm.ServerStatistics;

class CommServerImplementation {

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

    private static final int PURGE_COMPLETED_COMMAND_THRESHOLD = 100000; // Server ties to keep the list of
    private static final int PURGE_ZOMBIFIED_COMMAND_THRESHOLD = 100000; // Server ties to keep the list of

    private boolean closed = false; // TODO proper init
    private volatile long approxRcvdCommands;
    private volatile long approxRcvdCMDs;
    private volatile long approxSentCMDRESPs;
    private volatile long approxRcvdCMDRESPACKs;

    private class ReceivedCommandImplementation implements ReceivedCommand {
        private static final String SCRUBBED_VALUE = "EXPIRED";

        private final long cmdId;
        private final DatagramTransport.RemoteNode rn;
        private long recvdTimeStamp;
        private final Channel ch;

        // These are not final because we may scrub them when they are no longer needed.
        // See the scrub method.
        private String cmdBody;
        private String cmdType;

        private MessageHeader.CmdStatus status;
        private String respType;
        private String respBody;
        private boolean zombie; // True for expired commands - just want the cmdID to reject duplicate
                                // command submissions.

        public ReceivedCommandImplementation(long cmdId, String msgType, String msgBody, RemoteNode rn, Channel ch) {
            this.cmdId = cmdId;
            this.cmdBody = msgBody;
            this.cmdType = msgType;
            this.rn = rn;
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
            return this.rn.remoteAddress();
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

        // Remove fields that are no required as the record goes through it's life
        // cycle.
        // We replace unused fields by a reference to a single constant string instead
        // of null
        // to help with debugging and logging.
        public void scrub() {
            synchronized (this) {
                if (this.status == CmdStatus.STATUS_COMPLETED) {
                    this.cmdType = this.cmdBody = SCRUBBED_VALUE;
                    if (this.zombie) {
                        this.status = CmdStatus.STATUS_REJECTED; // From now on server will reject requests for this
                                                                 // msg.
                        this.respBody = this.respType = SCRUBBED_VALUE;
                    }
                }
            }
        }
    }

    CommServerImplementation(Channel ch, StructuredLogger.Log log) {
        this.log = log;
        this.ch = ch;

        // For receiving commands
        this.pendingCmds = new ConcurrentLinkedQueue<>();
        this.completedCmds = new ConcurrentLinkedQueue<>();
        this.cmdMap = new ConcurrentHashMap<>();
        this.receivedCmdCount = new AtomicLong(0);
    }

    ServerStatistics getStats() {
        return new ServerStatistics(approxRcvdCommands, approxRcvdCMDs, approxSentCMDRESPs, approxRcvdCMDRESPACKs,
                cmdMap.size(), pendingCmds.size(), completedCmds.size());
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
    void handleReceivedCMD(MessageHeader header, String msgBody, RemoteNode rn) {
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

        // Are we currently processing/tracking this command?
        if (rc != null) {
            if (rc.zombie) {
                // We have got a CMD request for command that we had processed and forgotten
                // about in the past.
                rejectCmd(header, rn);
            }
            sendCMDRESP(rc);
            return; // ********* EARLY RETURN
        }

        // We haven't seen this request below, let's make a new one.

        ReceivedCommandImplementation rcNew = new ReceivedCommandImplementation(cmdId, header.msgType, msgBody, rn, ch);
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

        // Every so often, we do perform a slightly time-consuming task of pruning
        // various queues...
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
            sendCMDRESP(rc);
        }

    }

    // Server gets this
    void handleReceivedCMDRESPACK(MessageHeader header, String msgBody, RemoteNode rn) {
        this.approxRcvdCMDRESPACKs++;
        try {
            String[] parts = msgBody.split("\n");
            for (String strId : parts) {
                long id = Long.parseUnsignedLong(strId, 16);

                ReceivedCommandImplementation rc = this.cmdMap.get(id);
                if (rc != null) {
                    // Try to add it to the Zombie map.
                    zombify(rc);
                } else {
                    log.trace("SVR_GOTRESPACK", CMDID_TAG + id + " - ignoring because we can't find it.");
                }
            }
        } catch (NumberFormatException e) {
            log.trace("SVR_CMDRESPACK", "error parsing command IDs");
        }
    }

    private void zombify(ReceivedCommandImplementation rc) {
        // We just mark it as a zombie.
        rc.zombie = true;
        rc.scrub();
        log.trace("SVR_ZOMBIFY", CMDID_TAG + rc.cmdId + " - zombified");
    }

    void periodicWork() {
        // We do not have any periodic work.
    }

    void close() {
        this.closed = true;
        // TODO: more cleanup;
    }

    // Walk the list of completed commands. This call is relatively expensive. It
    // makes multiple passes,
    // removing old zombified commands, zombifying completed commands (oldest
    // first).
    private void pruneCompletedCommands() {

        final int IZOMBIE = 1;
        final int IOTHER = 0;
        final int[] counts = { 0, 0 };

        // Obtain a rough count of total and zombified commands. We maintain these
        // counts in an array
        // because labdas require local variables to be final.
        this.completedCmds.forEach(rc -> {
            counts[IOTHER]++;
            if (rc.zombie) {
                counts[IZOMBIE]++;
            }
        });

        // Compute roughly how many zombified commands we need to delete and how many
        // non-zombified completed commands need to be zombified.
        int maxZombified = PURGE_ZOMBIFIED_COMMAND_THRESHOLD / 2;
        int maxCompleted = PURGE_COMPLETED_COMMAND_THRESHOLD / 2;
        int delZombies = Math.max(0, counts[IZOMBIE] - maxZombified);
        int delCompleted = Math.max(0, counts[IOTHER] - counts[IZOMBIE] - maxCompleted);
        counts[IZOMBIE] = delZombies;
        counts[IOTHER] = delCompleted;

        // Actually delete the required zombified commands (oldest first) and zombify
        // the required completed commands (also oldest first)
        // SIDE AFFECT: the command is also removed from the command map (based on the
        // command id).
        this.completedCmds.removeIf(rc -> {
            if (rc.zombie) {
                if (counts[IZOMBIE] > 0) {
                    log.trace("SRV_CMD_PURGE", CMDID_TAG + Long.toHexString(rc.cmdId));
                    this.cmdMap.remove(rc.cmdId); // SIDE EFFECT; may not be there.
                    counts[IZOMBIE]--;
                    return true; // ********* EARLY RETURN
                }
            } else if (counts[IOTHER] > 0) {
                zombify(rc); // SIDE EFFECT
                counts[IOTHER]--;
            }
            return false;
        });
    }

    private void sendCMDRESP(ReceivedCommandImplementation rc) {
        MessageHeader header = new MessageHeader(MessageHeader.DgType.DG_CMDRESP, this.ch.name(), rc.respType, rc.cmdId,
                rc.status);
        this.approxSentCMDRESPs++;
        rc.rn.send(header.serialize(rc.respBody));
    }

    private void rejectCmd(MessageHeader headerIn, RemoteNode rn) {
        MessageHeader header = new MessageHeader(MessageHeader.DgType.DG_CMDRESP, this.ch.name(), null, headerIn.cmdId,
                CmdStatus.STATUS_REJECTED);
        this.approxSentCMDRESPs++;
        rn.send(header.serialize(""));
    }

    public void startReceivingRtCommands(Consumer<ReceivedCommand> incoming) {
        // TODO Auto-generated method stub

    }

    public void stopReceivingRtCommands() {
        // TODO Auto-generated method stub
    }

    public void handleReceivedRTCMD(MessageHeader header, String msgBody, RemoteNode rn) {
        // TODO Auto-generated method stub

    }

}

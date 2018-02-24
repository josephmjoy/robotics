package com.rinworks.robotutils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import com.rinworks.robotutils.RobotComm.Address;
import com.rinworks.robotutils.RobotComm.ReceivedMessage;
import com.rinworks.robotutils.RobotComm.SentCommand;

class RobotCommTest {

    private boolean assertionFailure;
    private String assertionFailureString;

    class TestTransport implements RobotComm.DatagramTransport {
        final String ALWAYSDROP_TEXT = "TRANSPORT-MUST-DROP";
        private final MyRemoteNode loopbackNode = new MyRemoteNode(new MyAddress("loopback"));
        private final ConcurrentLinkedQueue<String> recvQueue = new ConcurrentLinkedQueue<>();
        private final Timer transportTimer = new Timer();
        private final Random rand = new Random();
        private final AtomicLong numSends = new AtomicLong(0);
        private final AtomicLong numRecvs = new AtomicLong(0);
        private final AtomicLong numForceDrops = new AtomicLong(0);
        private final AtomicLong numRandomDrops = new AtomicLong(0);
        private final StructuredLogger.Log log;

        private boolean closed = false;
        private double failureRate = 0;
        private int maxDelay = 0;
        private MyListener curListener = null; // we support only one listener at a time.

        private class MyAddress implements Address {
            final String addr;

            public MyAddress(String a) {
                this.addr = a;
            }

            @Override
            public String stringRepresentation() {
                return this.addr;
            }

        }

        TestTransport(StructuredLogger.Log log) {
            this.log = log;
        }

        // Changes transport characteristics: sends datagrams with {failureRate} failure
        // rate,
        // and {maxDelay} max delay per packet.
        void setTransportCharacteristics(double failureRate, int maxDelay) {
            assert failureRate >= 0 && failureRate <= 1;
            assert maxDelay >= 0;
            this.failureRate = failureRate;
            this.maxDelay = maxDelay;
        }

        // {failureRate} "close enough" to 0 is considered to be zero-failures.
        boolean zeroFailures() {
            return Math.abs(this.failureRate) < 1e-7;
        }

        boolean noDelays() {
            return this.maxDelay == 0;
        }

        @Override
        public Address resolveAddress(String address) {
            return new MyAddress(address);
        }

        private class MyListener implements Listener {

            final private Address addr;
            final MyRemoteNode loopbackNode;
            BiConsumer<String, RemoteNode> clientRecv;
            boolean closed;

            MyListener(Address a) {
                this.addr = a;
                this.loopbackNode = new MyRemoteNode(addr);
                this.closed = false;

                // Test transport supports only one listener at a time.
                assert (TestTransport.this.curListener == null);
                TestTransport.this.curListener = this;
            }

            @Override
            public void listen(BiConsumer<String, RemoteNode> handler) {
                // Listen should only be called once.
                assert this.clientRecv == null;
                this.clientRecv = handler;
            }

            @Override
            public Address getAddress() {
                return this.addr;
            }

            @Override
            public void close() {
                this.closed = true;
                this.clientRecv = null;
            }

            void receiveData(String s) {
                if (this.clientRecv != null) {
                    TestTransport.this.numRecvs.incrementAndGet();
                    log.trace("TRANSPORT:\n[" + s + "]\n");
                    this.clientRecv.accept(s, loopbackNode);
                } else {
                    TestTransport.this.numForceDrops.incrementAndGet();
                }
            }

        }

        @Override
        public Listener newListener(Address localAddress) {
            return new MyListener(localAddress);
        }

        private class MyRemoteNode implements RemoteNode {
            final Address addr;

            MyRemoteNode(Address a) {
                this.addr = a;
            }

            @Override
            public Address remoteAddress() {
                return addr;
            }

            @Override
            public void send(String msg) {
                TestTransport.this.numSends.incrementAndGet();
                if (forceDrop(msg)) {
                    TestTransport.this.numForceDrops.incrementAndGet();
                    return; // **************** EARLY RETURN *****
                }
                if (nonForceDrop(msg)) {
                    TestTransport.this.numRandomDrops.incrementAndGet();
                    return; // **************** EARLY RETURN *****
                }
                handleSend(msg);
            }

        }

        @Override
        public RemoteNode newRemoteNode(Address remoteAddress) {
            return new MyRemoteNode(remoteAddress);
        }

        public boolean forceDrop(String msg) {
            return msg.indexOf(ALWAYSDROP_TEXT) >= 0;
        }

        public boolean nonForceDrop(String msg) {
            if (!this.closed && zeroFailures()) {
                return false;
            } else {
                return this.rand.nextDouble() < this.failureRate;
            }
        }

        @Override
        public void close() {
            this.closed = true;
            this.transportTimer.cancel();
        }

        public long getNumSends() {
            return this.numSends.get();
        }

        public long getNumRecvs() {
            return this.numRecvs.get();
        }

        public long getNumForceDrops() {
            return this.numForceDrops.get();
        }

        public long getNumRandomDrops() {
            return this.numRandomDrops.get();
        }

        public int getMaxDelay() {
            return this.maxDelay;
        }

        public double getFailureRate() {
            return this.failureRate;
        }

        private void handleSend(String msg) {
            if (this.closed) {
                TestTransport.this.numForceDrops.incrementAndGet();
                return; // ************ EARLY RETURN
            }
            if (noDelays()) {
                if (this.curListener != null) {
                    // Send right now!
                    this.curListener.receiveData(msg);
                } else {
                    TestTransport.this.numForceDrops.incrementAndGet();
                }
            } else {
                int delay = (int) (rand.nextDouble() * this.maxDelay);
                transportTimer.schedule(new TimerTask() {

                    @Override
                    public void run() {
                        if (!TestTransport.this.closed && TestTransport.this.curListener != null) {
                            // Delayed send
                            TestTransport.this.curListener.receiveData(msg);
                        } else {
                            TestTransport.this.numForceDrops.incrementAndGet();
                        }
                    }

                }, delay);
            }
        }
    }

    class StressTester {

        private final int nThreads;
        private final ExecutorService exPool;
        private final TestTransport transport;
        private final Random rand = new Random();
        private final AtomicLong nextId = new AtomicLong(1);
        private final Timer testTimer = new Timer();
        private final StructuredLogger.Log log;
        private final StructuredLogger.Log hfLog; // high frequency (verbose) log
        RobotComm rc;
        RobotComm.Channel ch;

        private final ConcurrentHashMap<Long, MessageRecord> msgMap = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<MessageRecord> droppedMsgs = new ConcurrentLinkedQueue<>();
        private final ConcurrentHashMap<Long, CommandRecord> cmdMap = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<CommandRecord> cmdCliSubmitQueue = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<CommandRecord> cmdSvrComputeQueue = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<CommandRecord> droppedCommands = new ConcurrentLinkedQueue<>();

        private class MessageRecord {
            final long id;
            final boolean alwaysDrop;
            final String msgType;
            final String msgBody;

            MessageRecord(long id, boolean alwaysDrop) {
                this.id = id;
                this.alwaysDrop = alwaysDrop;
                this.msgType = randomMsgType();
                this.msgBody = alwaysDrop ? transport.ALWAYSDROP_TEXT : randomMessageBody(id);
            }

            // First line of message body is the internal 'id'. Remainder is random junk.
            private String randomMessageBody(long id) {
                String strId = Long.toHexString(id);
                String body = strId;

                int nExtra = rand.nextInt(10);
                for (int i = 0; i < nExtra; i++) {
                    body += "\n" + Long.toHexString(rand.nextLong());
                }
                return body;
            }

            // Type is a little bit of random hex digits, possibly an empty string.
            private String randomMsgType() {
                String msgt = Long.toHexString(rand.nextLong());
                assert msgt.length() > 0;
                msgt = msgt.substring(rand.nextInt(msgt.length()));
                return msgt;
            }
        }

        private class CommandRecord {
            final MessageRecord cmdRecord;
            final MessageRecord respRecord;

            CommandRecord(MessageRecord cmdRecord, MessageRecord respRecord) {
                this.cmdRecord = cmdRecord;
                this.respRecord = respRecord;
            }
        }

        public StressTester(int nThreads, TestTransport transport, StructuredLogger.Log log) {
            this.nThreads = nThreads;
            this.transport = transport;
            this.log = log;
            this.hfLog = log.newLog("HFLOG");

            // Init executor.
            this.exPool = Executors.newFixedThreadPool(nThreads);

        }

        public void init() {
            StructuredLogger.Log rcLog = log.newLog("RCOMM");
            this.rc = new RobotComm(transport, rcLog);
            RobotComm.Address addr = rc.resolveAddress("localhost");
            this.ch = rc.newChannel("testChannel");
            this.ch.bindToRemoteNode(addr);
            this.rc.startListening();
            this.ch.startReceivingMessages();
        }

        /**
         * Stress test sending, receiving and processing of commands.
         * 
         * @param nMessages
         *            - number of messagegs to submit
         * @param submissionRate
         *            - rate of submission per second
         * @param alwaysDropRate
         *            - rate at which datagrams are always dropped by the transport
         */
        public void submitMessages(int nMessages, int submissionRate, double alwaysDropRate) {
            final int BATCH_SPAN_MILLIS = 1000;
            final int MAX_EXPECTED_TRANSPORT_FAILURES = 1000000;
            // If more than LARGE_COUNT messages, there should be NO transport send failures
            // else our tracked messages will start to accumulate and take up too much
            // memory.
            final double expectedTransportFailures = nMessages * transport.getFailureRate();
            if (expectedTransportFailures > MAX_EXPECTED_TRANSPORT_FAILURES) {
                log.err("Too many transport failures expected " + (int) expectedTransportFailures
                        + ") - resulting in too much memory consumption. Abandoning test.");
                fail("Too much expected memory consumption to run this test.");
            }

            try {
                int messagesLeft = nMessages;
                while (messagesLeft > submissionRate) {
                    long t0 = System.currentTimeMillis();
                    submitMessageBatch(submissionRate, BATCH_SPAN_MILLIS, alwaysDropRate);
                    messagesLeft -= submissionRate;
                    long t1 = System.currentTimeMillis();
                    int sleepTime = (int) Math.max(BATCH_SPAN_MILLIS * 0.1, BATCH_SPAN_MILLIS - (t1 - t0));
                    Thread.sleep(sleepTime);
                    pruneDroppedMessages();
                }
                int timeLeftMillis = (1000 * messagesLeft) / submissionRate;
                submitMessageBatch(messagesLeft, timeLeftMillis, alwaysDropRate);
                Thread.sleep(timeLeftMillis);

                // Having initiated the process of sending all the messages, we now have to
                // wait for ??? and verify that exactly those messages that are expected to be
                // received are received correctly.
                int maxReceiveDelay = transport.getMaxDelay();
                log.trace("Waiting to receive up to " + nMessages + " messages");
                Thread.sleep(maxReceiveDelay);
                int retryCount = 10;
                while (retryCount-- > 0 && this.msgMap.size() > transport.getNumRandomDrops()) {
                    purgeAllDroppedMessages();
                    Thread.sleep(250);
                }
                purgeAllDroppedMessages();
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }

            // Now we do final validation.
            this.finalSendMessageValidation();

            this.cleanup();
        }

        /**
         * Stress test sending, receiving and processing of commands.
         * 
         * @param nCommands
         *            - number of commands to submit
         * @param submissionRate
         *            - rate of submission per second
         * @param alwaysDropCmdRate
         *            - rate at which CMD datagrams are always dropped by the transporrt
         * @param alwaysDropRespRate
         *            - rate at which CMDRESP datagrams are always dropped by the
         *            transport
         * @param maxComputeTime
         *            - max time it takes to compute a response to a command.
         */
        public void submitCommands(int nCommands, int submissionRate, double alwaysDropCmdRate,
                double alwaysDropRespRate, int maxComputeTime) {
            final int BATCH_SPAN_MILLIS = 1000;
            final int MAX_EXPECTED_TRANSPORT_FAILURES = 1000000;
            // If more than LARGE_COUNT messages, there should be NO transport send failures
            // else our tracked messages will start to accumulate and take up too much
            // memory.

            try {
                int commandsLeft = nCommands;
                while (commandsLeft > submissionRate) {
                    long t0 = System.currentTimeMillis();
                    submitCommandBatch(submissionRate, BATCH_SPAN_MILLIS, alwaysDropCmdRate, alwaysDropRespRate,
                            maxComputeTime);
                    commandsLeft -= submissionRate;
                    long t1 = System.currentTimeMillis();
                    int sleepTime = (int) Math.max(BATCH_SPAN_MILLIS * 0.1, BATCH_SPAN_MILLIS - (t1 - t0));
                    Thread.sleep(sleepTime);
                    pruneDroppedCommands();
                }
                int timeLeftMillis = (1000 * commandsLeft) / submissionRate;
                submitCommandBatch(commandsLeft, timeLeftMillis, alwaysDropCmdRate, alwaysDropRespRate, maxComputeTime);
                Thread.sleep(timeLeftMillis);

                // Having initiated the process of submitting all the commands, we now have to
                // wait for ??? and verify that exactly those commands that are expected to be
                // processed were processed correctly.
                int approxCompletionDelay = 2 * transport.getMaxDelay();
                log.trace("Waiting to receive up to " + nCommands + " commands");
                Thread.sleep(approxCompletionDelay);
                int retryCount = 10;
                while (retryCount-- > 0 && this.cmdMap.size() > 0) {
                    purgeAllDroppedCommands();
                    Thread.sleep(250);
                }
                purgeAllDroppedCommands();
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }

            // Now we do final validation.
            this.finalSubmitCommandValidation();

            this.cleanup();
        }

        // Keep count of dropped messages by removing the older
        // half of them if their number grows larger than DROP_TRIGGER
        private void pruneDroppedMessages() {
            final long DROP_TRIGGER = 1000;
            // Remember that the message map and queue of dropped messages are concurrently
            // modified so sizes are estimates.
            long sizeEst = this.droppedMsgs.size(); // Warning O(n) operation.
            if (sizeEst > DROP_TRIGGER) {
                long dropCount = sizeEst / 2;
                log.trace("Purging about " + dropCount + " force-dropped messages.");
                while (dropCount > 0) {
                    MessageRecord mr = this.droppedMsgs.poll();
                    if (mr == null) {
                        break;
                    }
                    this.msgMap.remove(mr.id, mr); // Should be O(log(n)), so it's ok all in all.
                    dropCount--;
                }
            }
        }

        // Remove all dropped messages (at least the ones that were in the queue when we
        // entered
        // this method).
        private void purgeAllDroppedMessages() {
            MessageRecord mr;
            while ((mr = this.droppedMsgs.poll()) != null) {
                this.msgMap.remove(mr.id, mr); // Should be O(log(n)), so it's ok all in all.
            }
        }

        public void submitMessageBatch(int nMessages, int submissionTimespan, double alwaysDropRate) {

            assert this.rc != null;
            assert this.ch != null;
            assert alwaysDropRate >= 0 && alwaysDropRate <= 1;
            assert submissionTimespan >= 0;

            log.trace("Beginning to submit " + nMessages + " messages.");

            for (int i = 0; i < nMessages; i++) {
                boolean alwaysDrop = rand.nextDouble() < alwaysDropRate;
                long id = nextId.getAndIncrement();
                MessageRecord mr = new MessageRecord(id, alwaysDrop);
                int delay = (int) (rand.nextDouble() * submissionTimespan);
                this.msgMap.put(id, mr);
                if (alwaysDrop) {
                    this.droppedMsgs.add(mr);
                }
                this.testTimer.schedule(new TimerTask() {

                    @Override
                    public void run() {
                        StressTester.this.exPool.submit(() -> {
                            try {
                                hfLog.trace("Sending message with id " + id);
                                StressTester.this.ch.sendMessage(mr.msgType, mr.msgBody);
                            } catch (Exception e) {
                                log.err("EXCEPTION", e.toString());
                            }
                        });
                    }
                }, delay);
            }

            int recvAttempts = Math.max(nMessages / 100, 10);
            int maxReceiveDelay = submissionTimespan + transport.getMaxDelay();
            log.info("maxReceiveDelay: " + maxReceiveDelay);
            for (int i = 0; i < recvAttempts; i++) {
                int delay = (int) (rand.nextDouble() * submissionTimespan);

                // Special case of i == 0 - we schedule our final receive attempt to be AFTER
                // the last packet is actually sent by the transport (after a potential delay)
                if (i == 0) {
                    delay = maxReceiveDelay + 1;
                }

                this.testTimer.schedule(new TimerTask() {

                    @Override
                    public void run() {
                        StressTester.this.exPool.submit(() -> {
                            try {
                                // Let's pick up all messages received so far and verify them...
                                RobotComm.ReceivedMessage rm = ch.pollReceivedMessage();
                                while (rm != null) {
                                    StressTester.this.processReceivedMessage(rm);
                                    rm = ch.pollReceivedMessage();
                                }
                            } catch (Exception e) {
                                log.err("EXCEPTION", e.toString());
                            }

                        });
                    }
                }, delay);
            }
        }

        private void processReceivedMessage(ReceivedMessage rm) {
            // Extract id from message.
            // Look it up - we had better find it in our map.
            // Verify that we expect to get it - (not alwaysDrop)
            // Verify we have not already received it.
            // Verify message type and message body is what we expect.
            // If all ok, remove this from the hash map.
            String msgType = rm.msgType();
            String msgBody = rm.message();
            int nli = msgBody.indexOf('\n');
            String strId = nli < 0 ? msgBody : msgBody.substring(0, nli);
            try {
                long id = Long.parseUnsignedLong(strId, 16);
                hfLog.trace("Received message with id " + id);
                MessageRecord mr = this.msgMap.get(id);
                // assertNotEquals(mr, null);
                log.loggedAssert(mr != null, "mr == null");
                // assertEquals(mr.msgType, msgType);
                log.loggedAssert(mr.msgType.equals(msgType), "mr.msgType not equal to msgType");
                // assertEquals(mr.msgBody, msgBody);
                log.loggedAssert(mr.msgBody.equals(msgBody), "mr.msgBody not equal to msgBody");
                // assertFalse(mr.alwaysDrop);
                log.loggedAssert(!mr.alwaysDrop, "mr.alwaysDrop is true");
                this.msgMap.remove(id, mr);
            } catch (Exception e) {
                log.err("EXCEPTION", e.toString());
                fail("Exception attempting to parse ID from received message [" + rm.message() + "]");
            }
        }

        private void finalSendMessageValidation() {
            // Verify that the count of messages that still remain in our map
            // is exactly equal to the number of messages dropped by the transport - i.e.,
            // they were never received.
            long randomDrops = this.transport.getNumRandomDrops();
            long forceDrops = this.transport.getNumForceDrops();
            int mapSize = this.msgMap.size();
            log.info("Final verification. ForceDrops: " + forceDrops + "  RandomDrops: " + randomDrops + "   Missing: "
                    + (mapSize - randomDrops));

            if (randomDrops != mapSize) {
                // We will fail this test later, but do some logging here...
                log.info("Drop queue size: " + this.droppedMsgs.size());
                for (MessageRecord mr : msgMap.values()) {
                    String mrStr = "missing mr id=" + mr.id + "  drop=" + mr.alwaysDrop;
                    log.info(mrStr);
                }
            }
            log.flush();
            assertEquals(randomDrops, mapSize);
        }

        private void submitCommandBatch(int submissionRate, int bATCH_SPAN_MILLIS, double alwaysDropCmdRate,
                double alwaysDropRespRate, int maxComputeTime) {
            // TODO Auto-generated method stub

        }

        private void purgeAllDroppedCommands() {
            // TODO Auto-generated method stub

        }

        private void pruneDroppedCommands() {
            // TODO Auto-generated method stub

        }

        private void finalSubmitCommandValidation() {
            // TODO Auto-generated method stub

        }

        // Cleanup our queues and maps so we can do another test
        private void cleanup() {
            this.msgMap.clear();
            this.droppedMsgs.clear();
            this.cmdMap.clear();
            this.cmdCliSubmitQueue.clear();
            this.cmdSvrComputeQueue.clear();
            this.droppedCommands.clear();
        }

        public void close() {
            this.testTimer.cancel();
            this.exPool.shutdownNow();
            cleanup();
        }
    }

    @Test
    void testBasicSendReceieveMessageTest() throws InterruptedException {
        StructuredLogger baseLogger = initStructuredLogger();
        RobotComm.DatagramTransport transport = new TestTransport(baseLogger.defaultLog().newLog("TRANS"));

        RobotComm rc = new RobotComm(transport, baseLogger.defaultLog());
        RobotComm.Address addr = rc.resolveAddress("localhost");
        RobotComm.Channel ch = rc.newChannel("testChannel");
        ch.bindToRemoteNode(addr);
        rc.startListening();

        String testMessage = "test message";
        ch.startReceivingMessages();
        ch.sendMessage("MYTYPE", testMessage);
        baseLogger.flush();
        RobotComm.ReceivedMessage rm = null;
        while (rm == null) {
            rm = ch.pollReceivedMessage();
            Thread.sleep(0);
        }
        ;

        for (RobotComm.ChannelStatistics stats : rc.getChannelStatistics()) {
            System.out.println(stats);
        }

        ch.stopReceivingMessages();
        rc.stopListening();
        rc.close();
        baseLogger.endLogging();
        assertTrue(rm != null);
        assertEquals(testMessage, rm.message());

    }

    @Test
    void testBasicSendReceieveCommandTest() throws InterruptedException {
        StructuredLogger baseLogger = initStructuredLogger();
        RobotComm.DatagramTransport transport = new TestTransport(baseLogger.defaultLog().newLog("TRANS"));

        RobotComm rc = new RobotComm(transport, baseLogger.defaultLog());
        RobotComm.Address addr = rc.resolveAddress("localhost");
        RobotComm.Channel ch = rc.newChannel("testChannel");
        ch.bindToRemoteNode(addr);
        rc.startListening();

        final String TEST_COMMAND = "TESTCMD1";
        final String TEST_CMDTYPE = "TESTCMDTYPE1";
        final String TEST_RESP = "TESTRESP1";
        final String TEST_RESPTYPE = "TESTRESPTYPE";
        ch.startReceivingCommands();
        RobotComm.SentCommand cmd = ch.sendCommand(TEST_CMDTYPE, TEST_COMMAND, true); // true == queue completion
        baseLogger.flush();
        assertEquals(TEST_CMDTYPE, cmd.cmdType());
        assertEquals(TEST_COMMAND, cmd.command());

        RobotComm.ReceivedCommand rcom = null;
        while (rcom == null) {
            rc.periodicWork();
            rcom = ch.pollReceivedCommand();
            Thread.sleep(1000);
        }

        // Got a commend, let's process it and turn a response.
        assertEquals(TEST_CMDTYPE, rcom.msgType());
        assertEquals(TEST_COMMAND, rcom.message());
        rcom.respond(TEST_RESPTYPE, TEST_RESP);
        ;

        // Let's wait for the commend to be completed.
        while (cmd.status() != SentCommand.COMMAND_STATUS.STATUS_COMPLETED) {
            Thread.sleep(1000);
            rc.periodicWork();
        }
        assertEquals(TEST_RESPTYPE, cmd.respType());
        assertEquals(TEST_RESP, cmd.response());
        RobotComm.SentCommand cmd1 = ch.pollCompletedCommand();
        assertEquals(cmd, cmd1); // We should also pick it up from the completion queue.

        for (RobotComm.ChannelStatistics stats : rc.getChannelStatistics()) {
            System.out.println(stats);
        }

        ch.stopReceivingMessages();
        rc.stopListening();
        rc.close();
        baseLogger.endLogging();

    }

    StructuredLogger initStructuredLogger() {
        File logfile = new File("C:\\Users\\jmj\\Documents\\robotics\\temp\\log.txt");
        StructuredLogger.Filter f1 = (ln, p, cat) -> {
            return ln.equals("test.TRANS") || ln.equals("test.HFLOG") ? false : true;
        };
        StructuredLogger.Filter f2 = (ln, p, cat) -> {
            return ln.equals("test.TRANS") ? false : true;
        };
        StructuredLogger.Filter f = f1;

        StructuredLogger.RawLogger rl = StructuredLogger.createFileRawLogger(logfile, 1000000, f);
        StructuredLogger.RawLogger rl2 = StructuredLogger.createConsoleRawLogger(f);
        StructuredLogger.RawLogger[] rls = { rl, rl2 };
        StructuredLogger sl = new StructuredLogger(rls, "test");
        sl.setAsseretionFailureHandler((s) -> {
            this.assertionFailure = true;
            this.assertionFailureString = s;
        });
        sl.beginLogging();
        sl.info("INIT LOGGER");
        return sl;
    }

    @Test
    void stressSendAndRecvMessages() {
        StructuredLogger baseLogger = initStructuredLogger();
        TestTransport transport = new TestTransport(baseLogger.defaultLog().newLog("TRANS"));
        StressTester stresser = new StressTester(1, transport, baseLogger.defaultLog());
        stresser.init();
        transport.setTransportCharacteristics(0.2, 250);
        stresser.submitMessages(1000, 10000, 0.1);
        baseLogger.flush();
        baseLogger.endLogging();
        stresser.close();
    }

    @Test
    void stressSubmitAndProcessCommands() {
        StructuredLogger baseLogger = initStructuredLogger();
        TestTransport transport = new TestTransport(baseLogger.defaultLog().newLog("TRANS"));
        StressTester stresser = new StressTester(1, transport, baseLogger.defaultLog());
        stresser.init();
        transport.setTransportCharacteristics(0, 0);
        stresser.submitCommands(0, 1, 0, 0, 0);
        baseLogger.flush();
        baseLogger.endLogging();
        stresser.close();
    }

}

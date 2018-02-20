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
import com.rinworks.robotutils.RobotComm.SentCommand;

class RobotCommTest {

    class TestTransport implements RobotComm.DatagramTransport {
        final String ALWAYSDROP_TEXT = "TRANSPORT-MUST-DROP";
        private final MyRemoteNode loopbackNode = new MyRemoteNode(new MyAddress("loopback"));
        private final ConcurrentLinkedQueue<String> recvQueue = new ConcurrentLinkedQueue<>();
        private final Timer transportTimer = new Timer();
        private final Random rand = new Random();
        private final AtomicLong numSends = new AtomicLong(0);
        private final AtomicLong numRecvs = new AtomicLong(0);
        private final AtomicLong numDrops = new AtomicLong(0);

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
            return Math.abs(this.failureRate) < 1e-3;
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
                    this.clientRecv.accept(s, loopbackNode);
                } else {
                    TestTransport.this.numDrops.incrementAndGet();
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
                if (shouldDrop(msg)) {
                    TestTransport.this.numDrops.incrementAndGet();
                    return; // **************** EARLY RETURN *****
                }
                handleSend(msg);
            }

        }

        @Override
        public RemoteNode newRemoteNode(Address remoteAddress) {
            return new MyRemoteNode(remoteAddress);
        }

        public void handleSend(String msg) {
            if (this.closed) {
                TestTransport.this.numDrops.incrementAndGet();
                return; // ************ EARLY RETURN
            }
            if (noDelays()) {
                if (this.curListener != null) {
                    // Send right now!
                    this.curListener.receiveData(msg);
                } else {
                    TestTransport.this.numDrops.incrementAndGet();
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
                            TestTransport.this.numDrops.incrementAndGet();
                        }
                    }

                }, delay);
            }
        }

        public boolean shouldDrop(String msg) {
            if (this.closed || msg.indexOf(ALWAYSDROP_TEXT) >= 0) {
                return true;
            } else if (zeroFailures()) {
                return false;
            } else {
                return this.rand.nextDouble() < this.failureRate;
            }
        }

        @Override
        public void close() {
            this.closed = true;
        }

        public long getNumSends() {
            return this.numSends.get();
        }
        
        public long getNumRecvs() {
            return this.numRecvs.get();
        }
        
        public long getNumDrops() {
            return this.numDrops.get();
        }
    }

    class StressTester {

        AtomicLong nextId = new AtomicLong(1);
        Timer testTimer = new Timer();

        private class MessageRecord {
            final long id;
            final boolean alwaysDrop;
            final String msgType;
            final String msgBody;

            MessageRecord(long id, boolean alwaysDrop) {
                this.id = id;
                this.alwaysDrop = alwaysDrop;
                this.msgType = randomMsgType(id);
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
            private String randomMsgType(long id) {
                String msgt = Long.toHexString(rand.nextLong());
                assert msgt.length() > 0;
                msgt = msgt.substring(rand.nextInt(msgType.length()));
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

        private final int nThreads;
        private final ExecutorService exPool;
        TestTransport transport;
        private final Random rand;

        public StressTester(int nThreads) {
            this.nThreads = nThreads;
            this.transport = new TestTransport();
            ConcurrentHashMap<Long, MessageRecord> mrMap = new ConcurrentHashMap<>();
            ConcurrentLinkedQueue<MessageRecord> mrSubmitQueue = new ConcurrentLinkedQueue<>();

            ConcurrentHashMap<Long, CommandRecord> crMap = new ConcurrentHashMap<>();
            ConcurrentLinkedQueue<CommandRecord> crCliSubmitQueue = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<CommandRecord> crSvrComputeQueue = new ConcurrentLinkedQueue<>();

            // Init executor.
            this.exPool = Executors.newFixedThreadPool(nThreads);
            this.rand = new Random();
        }

        public void submitMessages(int nMessages, double alwaysDropRate, double transportDropRate, double avgDelay) {
            assert alwaysDropRate >= 0 && alwaysDropRate <= 1;
            assert transportDropRate >= 0 && transportDropRate <= 1;
            for (int i = 0; i < nMessages; i++) {
                // MessageRecord(boolean alwaysDrop, String msgType, String payload, double
                // failureRate, double averageDelay) {
                boolean alwaysDrop = rand.nextDouble() < alwaysDropRate;
                long id = nextId.getAndIncrement();
                MessageRecord mr = new MessageRecord(id, alwaysDrop);
            }
        }

        public void close() {
            this.exPool.shutdownNow();
        }
    }

    @Test
    void testBasicSendReceieveMessageTest() throws InterruptedException {
        RobotComm.DatagramTransport transport = new TestTransport();
        StructuredLogger baseLogger = initStructuredLogger();
        baseLogger.beginLogging();

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
        RobotComm.DatagramTransport transport = new TestTransport();
        StructuredLogger baseLogger = initStructuredLogger();

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
        StructuredLogger.RawLogger rl = StructuredLogger.createFileRawLogger(logfile, 10000, null);
        StructuredLogger.RawLogger rl2 = StructuredLogger.createConsoleRawLogger(null);
        StructuredLogger.RawLogger[] rls = { rl, rl2 };
        StructuredLogger sl = new StructuredLogger(rls, "test");
        sl.beginLogging();
        return sl;
    }

}

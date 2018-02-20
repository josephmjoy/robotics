package com.rinworks.robotutils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import com.rinworks.robotutils.RobotComm.Address;
import com.rinworks.robotutils.RobotComm.Channel;
import com.rinworks.robotutils.RobotComm.SentCommand;

class RobotCommTest {

    class TestTransport implements RobotComm.DatagramTransport {
        public final String ALWAYSDROP_TEXT = "TRANSPORT-MUST-DROP";
        MyRemoteNode loopbackNode = new MyRemoteNode(new MyAddress("loopback"));
        ConcurrentLinkedQueue<String> recvQueue = new ConcurrentLinkedQueue<>();
        int msgsSent = 0;
        int msgsReceived = 0;

        class MyAddress implements Address {
            final String addr;

            public MyAddress(String a) {
                this.addr = a;
            }

            @Override
            public String stringRepresentation() {
                return this.addr;
            }

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
            }

            @Override
            public void listen(BiConsumer<String, RemoteNode> handler) {
                this.clientRecv = handler;
                ExecutorService pool = Executors.newFixedThreadPool(1);
                pool.execute(new Runnable() {

                    @Override
                    public void run() {
                        System.out.println("Test listener: starting...");
                        String msg = null;
                        while (!closed) {
                            if ((msg = recvQueue.poll()) != null) {
                                System.out.println("TRANSPORT:\n[" + msg + "]\n");
                                if (msgsReceived++ % 1 != 0) {
                                    // drop!
                                    System.out.println("Dropping received pkt");
                                    continue;
                                }
                                handler.accept(msg, loopbackNode);
                            } else {
                                try {
                                    Thread.sleep(0);
                                } catch (InterruptedException e) {
                                    // TODO Auto-generated catch block
                                    e.printStackTrace();
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                        System.out.println("Test listner: ...quitting.");
                    }
                });
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
                // TODO Auto-generated method stub
                return addr;
            }

            @Override
            public void send(String msg) {
                // TODO Auto-generated method stub
                if (msgsSent++ % 1 != 0) {
                    // drop!
                    return;
                }
                recvQueue.add(msg);
            }

        }

        @Override
        public RemoteNode newRemoteNode(Address remoteAddress) {
            // TODO Auto-generated method stub
            return new MyRemoteNode(remoteAddress);
        }

        @Override
        public void close() {
            // TODO Auto-generated method stub

        }

    }

    class StressTester {

        AtomicLong nextId = new AtomicLong(1);

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

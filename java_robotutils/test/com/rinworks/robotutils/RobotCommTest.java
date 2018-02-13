package com.rinworks.robotutils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import com.rinworks.robotutils.RobotComm.Address;
import com.rinworks.robotutils.RobotComm.Channel;
import com.rinworks.robotutils.RobotComm.SentCommand;

class RobotCommTest {

    class TestTransport implements RobotComm.DatagramTransport {
        MyRemoteNode loopbackNode = new MyRemoteNode(new MyAddress("loopback"));
        ConcurrentLinkedQueue<String> recvQueue = new ConcurrentLinkedQueue<>();

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
                                handler.accept(msg, loopbackNode);
                            } else {
                                Thread.yield();
                            }
                        }
                        System.out.println("Test listner: ...quitting.");
                    }
                });
            }

            @Override
            public Address getAddress() {
                // TODO Auto-generated method stub
                return this.addr;
            }

            @Override
            public void close() {
                // TODO Auto-generated method stub
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
        baseLogger.beginLogging();

        RobotComm rc = new RobotComm(transport, baseLogger.defaultLog());
        RobotComm.Address addr = rc.resolveAddress("localhost");
        RobotComm.Channel ch = rc.newChannel("testChannel");
        ch.bindToRemoteNode(addr);
        rc.startListening();

        final String TEST_COMMAND = "CMD1";
        final String TEST_CMDTYPE  = "CMDTYPE";
        final String TEST_RESP = "RESP1";
        final String TEST_RESPTYPE = "RESPTYPE";
        ch.startReceivingMessages();
        RobotComm.SentCommand cmd = ch.sendCommand(TEST_CMDTYPE, TEST_COMMAND);
        baseLogger.flush();
        assertEquals(TEST_CMDTYPE, cmd.cmdType());
        assertEquals(TEST_COMMAND, cmd.command());

        
        RobotComm.ReceivedCommand rcom = null;
        while (rcom == null) {
            rcom = ch.pollReceivedCommand();           
            Thread.sleep(0);
        }
        // Got a commend, let's process it and turn a response.
        assertEquals(TEST_CMDTYPE, rcom.msgType());
        assertEquals(TEST_COMMAND, rcom.message());
        rcom.respond(TEST_RESPTYPE, TEST_RESP);
        ;

        // Let's wait for the commend to be completed.
        while (cmd.status() != SentCommand.COMMAND_STATUS.STATUS_COMPLETED) {
            Thread.sleep(0);
        }
        assertEquals(TEST_RESPTYPE, cmd.respType());
        assertEquals(TEST_RESP, cmd.response());
 

        ch.stopReceivingMessages();
        rc.stopListening();
        rc.close();
        baseLogger.endLogging();

    }

    StructuredLogger initStructuredLogger() {
        File logfile = new File("C:\\Users\\jmj\\Documents\\robotics\\temp\\log.txt");
        StructuredLogger.RawLogger rl = StructuredLogger.createFileRawLogger(logfile, 10000, null);
        StructuredLogger sl = new StructuredLogger(rl, "test");
        return sl;
    }

}

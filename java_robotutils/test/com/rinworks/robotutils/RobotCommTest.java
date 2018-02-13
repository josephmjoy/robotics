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
    void testBasicSendReceieveTest() throws InterruptedException {
        RobotComm.DatagramTransport transport = new TestTransport();
        StructuredLogger baseLogger = initStructuredLogger();
        baseLogger.beginLogging();
        RobotComm rc = new RobotComm(transport, baseLogger.defaultLog());
        RobotComm.Address addr = rc.resolveAddress("localhost");
        RobotComm.Channel ch = rc.createChannel("testChannel");
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
        };
        ch.stopReceivingMessages();
        rc.stopListening();
        baseLogger.endLogging();
        assertTrue(rm != null);
        assertEquals(testMessage, rm.message());

    }
    
    StructuredLogger initStructuredLogger() {
        File logfile = new File("C:\\Users\\jmj\\Documents\\robotics\\temp\\log.txt");
        StructuredLogger.RawLogger rl = StructuredLogger.createFileRawLogger(logfile, 10000, null);
        StructuredLogger sl = new StructuredLogger(rl, "test");
        return sl;
    }

}

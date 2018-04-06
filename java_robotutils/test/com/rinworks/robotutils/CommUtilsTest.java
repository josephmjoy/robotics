/**
 * 
 */
package com.rinworks.robotutils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rinworks.robotutils.CommUtils.EchoClient;
import com.rinworks.robotutils.CommUtils.EchoServer;
import com.rinworks.robotutils.RobotComm.DatagramTransport;
import com.rinworks.robotutils.StructuredLogger;
import com.rinworks.robotutils.StructuredMessageMapper;

class CommUtilsTest {

    final String SERVER_IP_ADDRESS = "127.0.0.1";
    final int SERVER_PORT = 41899 + 3;
    final int MAX_PACKET_SIZE = 1024;

    // Echo client-server tests use these channel names.
    final String ECHO_CHANNEL_A = "A";

    @Test
    void testSimpleUdpTransport() throws InterruptedException {
        StructuredLogger logger = initStructuredLogger();
        StructuredLogger.Log log = logger.defaultLog();

        DatagramTransport client = CommUtils.createUdpTransport(MAX_PACKET_SIZE, log);
        DatagramTransport server = CommUtils.createUdpTransport(SERVER_IP_ADDRESS, SERVER_PORT, MAX_PACKET_SIZE, log);
        final int N = 100;
        CountDownLatch latch = new CountDownLatch(N);
        server.startListening((msg, node) -> {
            log.trace("Server got message: " + msg);
            latch.countDown();
        });
        DatagramTransport.Address addr = client.resolveAddress(SERVER_IP_ADDRESS + ":" + SERVER_PORT);
        DatagramTransport.RemoteNode rn = client.newRemoteNode(addr);
        for (int i = 0; i < N; i++) {
            String msg = "Test message #" + i;
            rn.send(msg);
            log.trace("Sent message: " + msg);
            //Thread.sleep(100);
        }
        print("Waiting for all messages to arrive...");
        if (latch.await(1, TimeUnit.SECONDS)) {
            print("Done waiting for all " + N + " messages to arrive.");
        } else {
            print("TIMED OUT waiting for all " + N + " messages to arrive.");
        }
        client.close();
        server.close();
        logger.endLogging();
    }

    @Test
    void testSimpleEchoClientServer() {
        String[] channelNames = { ECHO_CHANNEL_A };
        File rootDir = new File(System.getProperty("user.home"), "robotutils");
        File serverConfig = new File(rootDir.getAbsoluteFile(), "echo_server.yaml");
        File clientConfig = new File(rootDir.getAbsoluteFile(), "echo_client.yaml");;
       EchoServer server = new EchoServer(serverConfig, SERVER_IP_ADDRESS, SERVER_PORT, MAX_PACKET_SIZE,
                "testEchoServer", channelNames);
        runEchoServer(server);
        try (EchoClient client = new EchoClient(clientConfig, SERVER_IP_ADDRESS, SERVER_PORT, MAX_PACKET_SIZE, "testEchoClient")) {
            client.sendMessages(1, 1000, 1000, ECHO_CHANNEL_A);
            client.sendCommands(1, 1000, 1000, ECHO_CHANNEL_A);
            client.sendRtCommands(1, 1000, 1000, ECHO_CHANNEL_A);
            Thread.sleep(1000);
            server.stop();
            server.close();
        } catch (Exception e) {
            e.printStackTrace();
            fail("Exception thrown");
        }
    }

    private void runEchoServer(EchoServer server) {
        Thread bgThread = new Thread(null, new Runnable() {

            @Override
            public void run() {

                String[] channelNames = { ECHO_CHANNEL_A };
                try {
                    server.run();
                } catch (Exception e) {
                    e.printStackTrace();
                    fail("Exception thrown");
                }
            }
        });
        bgThread.start();
    }

    private void print(String s) {
        System.out.println(s);

    }

    StructuredLogger initStructuredLogger() {
        final String DEFAULT_LOGDIR = "robotutils\\testlogs";
        String logDir = (new File(System.getProperty("user.home"), DEFAULT_LOGDIR)).getAbsolutePath();
        File logfile = new File(logDir, "commtestlog2.txt");
        File logDirFile = new File(logDir);
        ToIntFunction<String> f1 = name -> {
            return name.equals("test") || name.equals("HFLOG") ? -1 : Integer.MAX_VALUE;
        };
        ToIntFunction<String> f2 = name -> {
            return name.equals("TRANS") ? -1 : Integer.MAX_VALUE;
        };
        ToIntFunction<String> f = null; //f1;

        // StructuredLogger.RawLogger rl = LoggerUtils.createFileRawLogger(logfile,
        // 1000000, f);
        StructuredLogger.RawLogger rl = LoggerUtils.createFileRawLogger(logDirFile, "CUT", ".txt", 1000000, f);
        StructuredLogger.RawLogger rl2 = LoggerUtils.createConsoleRawLogger(f1);
        StructuredLogger.RawLogger[] rls = { rl, rl2 };
        StructuredLogger sl = new StructuredLogger(rls, "test");
        sl.setAsseretionFailureHandler((s) -> {
            System.err.println("ASSERTION FAILURE: " + s);
            sl.flush();

        });
        sl.setAutoFlushParameters(1, 100);
        sl.beginLogging();
        sl.info("INIT LOGGER");
        return sl;
    }

}

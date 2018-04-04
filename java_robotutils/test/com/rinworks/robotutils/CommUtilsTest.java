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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rinworks.robotutils.CommUtils.EchoClient;
import com.rinworks.robotutils.CommUtils.EchoServer;
import com.rinworks.robotutils.StructuredLogger;
import com.rinworks.robotutils.StructuredMessageMapper;

class CommUtilsTest {

    final String SERVER_IP_ADDRESS = "277.0.0.0";
    final int SERVER_PORT = 1899;

    // Echo client-server tests use these channel names.
    final String ECHO_CHANNEL_A = "A";

    @Test
    void testSimpleEchoClientServer() {
        startEchoServer();
        String localIPAddress = "";
        try (EchoClient client = new EchoClient(localIPAddress, ECHO_CHANNEL_A, SERVER_PORT, "testEchoClient")) {
            client.sendMessages(1, 1000, 1000, ECHO_CHANNEL_A);
            client.sendCommands(1, 1000, 1000, ECHO_CHANNEL_A);
            client.sendRtCommands(1, 1000, 1000, ECHO_CHANNEL_A);
        } catch (Exception e) {
            e.printStackTrace();
            fail("Exception thrown");
        }
    }

    private void startEchoServer() {
        Thread bgThread = new Thread(null, new Runnable() {

            @Override
            public void run() {

                String[] channelNames = { ECHO_CHANNEL_A };
                try (EchoServer server = new EchoServer(SERVER_IP_ADDRESS, SERVER_PORT, "tesetEchoServer",
                        channelNames)) {
                    server.run();
                } catch (Exception e) {
                    e.printStackTrace();
                    fail("Exception thrown");
                }
            }
        });
        bgThread.run();
    }

}

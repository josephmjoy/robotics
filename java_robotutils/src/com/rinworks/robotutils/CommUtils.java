// Various helper classes to make it easier to use the RobotComm communication infrastructure.
// Created by Joseph M. Joy (https://github.com/josephmjoy)

package com.rinworks.robotutils;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiConsumer;

import com.rinworks.robotutils.RobotComm.Channel;
import com.rinworks.robotutils.RobotComm.DatagramTransport;
import com.rinworks.robotutils.RobotComm.DatagramTransport.Address;
import com.rinworks.robotutils.RobotComm.ReceivedCommand;
import com.rinworks.robotutils.RobotComm.ReceivedMessage;

public class CommUtils {

    public static class EchoServer implements Closeable {
        private final StructuredLogger logger;
        private final StructuredLogger.Log log;
        private final DatagramTransport transport;
        private final RobotComm.Channel[] channels;
        private final RobotComm rc;
        private final CountDownLatch latch = new CountDownLatch(1);
        private Thread runningThread = null;

        /**
         * Creates an echo server. Logging goes to the default logging directory (see
         * <code>LoggerUtils</code>), with sysName {serverName}.
         * 
         * @param configFile
         *            - File containing configuration information, including
         *            logging-related configuration.
         * @param address
         *            - IP address
         * @param port
         *            - IP port
         * @param recvBufSize
         *            - Size in bytes of the internal buffer used to receive incoming
         *            data. If it is too small, data will be truncated.
         * @param serverName
         *            - Name to associate with this server. For logging purposes only.
         * @param channelNames
         *            - array of channel names.
         */
        public EchoServer(File configFile, String address, int port, int recvBufSize, String serverName,
                String[] channelNames) {
            this.logger = LoggerUtils.makeStandardLogger(serverName, configFile);
            this.logger.beginLogging();
            this.log = logger.defaultLog();
            this.transport = CommUtils.createUdpTransport(address, port, recvBufSize, this.log.newLog("UDP"));
            this.rc = new RobotComm(transport, this.log.newLog("RCOMM"));
            this.channels = new Channel[channelNames.length];
            for (int i = 0; i < channelNames.length; i++) {
                this.channels[i] = rc.newChannel(channelNames[i]);
            }
        }

        /**
         * Runs the server. This call must be invoked only once, and it will block until
         * some exception is thrown or there is a CTRL-C break. Call close() when done
         * or on exception.
         */
        public void run() {

            synchronized (this.latch) {
                assert this.runningThread == null;
                this.runningThread = Thread.currentThread();
            }

            this.log.info("Starting server");
            for (Channel ch : this.channels) {
                ch.startReceivingCommands();
                ch.startReceivingMessages();
                // Echo received real time command.
                ch.startReceivingRtCommands(cmd -> cmd.respond(cmd.msgType(), cmd.message()));
            }
            this.rc.startListening();
            try {
                while (true) {
                    rc.periodicWork();
                    for (Channel ch : channels) {
                        ReceivedCommand cmd = ch.pollReceivedCommand();
                        if (cmd != null) {
                            // Echo received command.
                            cmd.respond(cmd.msgType(), cmd.message());
                        }
                        ReceivedMessage msg = ch.pollReceivedMessage();
                        if (msg != null) {
                            // Echo received message.
                            ch.sendMessage(msg.msgType(), msg.message(), msg.remoteAddress());
                        }
                    }
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // We fall through...
            }
            log.info("Stopping server");
            this.rc.stopListening();
            for (Channel ch : this.channels) {
                ch.stopReceivingCommands();
                ch.stopReceivingMessages();
                ch.stopReceivingRtCommands();
            }
            this.latch.countDown();
        }

        /**
         * Stops a running server. Will block until the server is stopped. This call
         * must be called by a different thread from the one that is running, obviously.
         * Will also close the server.
         * 
         * @throws InterruptedException
         */
        public void stop() throws InterruptedException {
            boolean await = false;
            synchronized (latch) {
                if (this.runningThread != null) {
                    this.runningThread.interrupt();
                    this.runningThread = null;
                    await = true;
                }
            }
            if (await) {
                this.latch.await();
            }
            this.close();
        }

        @Override
        public void close() {
            this.rc.close();
            this.transport.close();
            this.logger.endLogging();
        }
    }

    public static class EchoClient implements Closeable {

        private final StructuredLogger logger;
        private final StructuredLogger.Log log;
        private final DatagramTransport transport;
        public final DatagramTransport.Address remoteAddress;
        private final RobotComm rc;

        /**
         * Creates a RobotComm echo client. Logging goes to the default logging
         * directory (see <code>LoggerUtils</code>), with sysName {serverName}.
         * 
         * @param configFile
         *            - File containing configuration information, including
         *            logging-related configuration.
         * @param serverAddress
         *            - server IP address
         * @param serverPort
         *            - server IP port
         * @param clientName
         *            - used for logging
         */
        public EchoClient(File configFile, String serverAddress, int serverPort, int recvBufSize, String clientName) {
            this.logger = LoggerUtils.makeStandardLogger(clientName, configFile);
            this.logger.beginLogging();
            this.log = logger.defaultLog();
            this.transport = CommUtils.createUdpTransport(recvBufSize, this.log.newLog("UDP"));
            this.remoteAddress = CommUtils.resolveUdpAddress(serverAddress, serverPort);
            this.rc = new RobotComm(transport, this.log.newLog("RCOMM"));
        }

        /**
         * Sends {count} messages over channel {channelName}, one every {periodMs}
         * milliseconds, with message size {msgSize}. Will block until all messages are
         * sent unless there is an exception or a CTRL-C event. This call may be invoked
         * multiple times (sequentially or concurrently). However, <code>close</code>
         * must be called after all invocations of <code>send*</code> are called.
         */
        public void sendMessages(long count, int periodMs, int msgSize, String channelName) {
            this.log.info("Starting to send messages");
            this.rc.startListening();
            try (Channel ch = rc.newChannel(channelName)) {

                ch.startReceivingMessages();

                for (int i = 0; i < count; i++) {
                    rc.periodicWork();
                    String msgType = "MSG" + count;
                    String msgBody = makeMessageBody("body" + count + ", ", msgSize);
                    log.trace("ECHO_SEND_MSG", "msgtype: " + msgType + "  msgBody: " + msgBody);
                    ch.sendMessage(msgType, msgBody, this.remoteAddress);
                    ReceivedMessage msg = ch.pollReceivedMessage();
                    if (msg != null) {
                        log.trace("ECHO_RECV_MSG", "msgtype: " + msg.msgType() + "  msgBody: " + msg.message()
                                + "  from: " + msg.remoteAddress());
                    }
                    Thread.sleep(periodMs);
                }

                ch.stopReceivingMessages();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // We fall through...
            }

            log.info("Done sending messages ");
            this.rc.stopListening();

        }

        // Make dummy message content up to msgSize.
        // Filler MUST not be empty.
        private String makeMessageBody(String filler, int msgSize) {
            assert filler.length() > 0;
            int nBlahs = msgSize / filler.length();
            String[] allBlahs = new String[nBlahs + 1];
            for (int i = 0; i < allBlahs.length - 1; i++) {
                allBlahs[i] = filler;
            }
            int extra = msgSize - nBlahs * filler.length();
            allBlahs[nBlahs] = filler.substring(0, extra);
            return String.join("", allBlahs);
        }

        /**
         * Sends {count} commands over channel {channelName}, one every {periodMs}
         * milliseconds, with message size {msgSize}. Will block until all messages are
         * sent unless there is an exception or a CTRL-C event. This call may be invoked
         * multiple times (sequentially or concurrently). However, <code>close</code>
         * must be called after all invocations of <code>send*</code> are called.
         */
        public void sendCommands(long count, int periodMs, int msgSize, String channelName) {
            this.log.info("Pretending to send commands");
        }

        /**
         * Sends {count} real time commands over channel {channelName}, one every
         * {periodMs} milliseconds, with message size {msgSize}. Will block until all
         * messages are sent unless there is an exception or a CTRL-C event. This call
         * may be invoked multiple times (sequentially or concurrently). However,
         * <code>close</code> must be called after all invocations of <code>send*</code>
         * are called.
         */
        public void sendRtCommands(long count, int periodMs, int msgSize, String channelName) {
            this.log.info("Pretending to send realtime commands");
        }

        @Override
        public void close() {
            this.log.info("in EchoClient.close");
            this.rc.close();
            this.transport.close();
            this.logger.endLogging();
        }
    }

    // For clients - ask system to pick local addres and port.
    public static DatagramTransport createUdpTransport(int recvBufSize, StructuredLogger.Log log) {
        return new UdpTransport(recvBufSize, log);
    }

    // For servers - specify server ip address and port.
    public static DatagramTransport createUdpTransport(String address, int port, int recvBufSize,
            StructuredLogger.Log log) {
        return new UdpTransport(address, port, recvBufSize, log);
    }

    public static Address resolveUdpAddress(String hostname, int port) {
        return UdpTransport.resolveUdpAddress(hostname, port);
    }

    // To prevent public invocation of default constructor.
    private CommUtils() {
    }

    private static class UdpTransport implements DatagramTransport {
        public static final String WILDCARD_IP_ADDRESS = "0.0.0.0";
        public static final int EPHEMERAL_PORT = -1;

        final Object lock = new Object();
        final int localPort;
        final StructuredLogger.Log log;
        final int recvBufSize;
        InetAddress localAddress;
        boolean active;
        boolean stoppingListening;
        DatagramSocket socket;
        private final ConcurrentHashMap<String, UdpRemoteNode> remoteNodeMap = new ConcurrentHashMap<>();

        // For listening on an ephemeral port.
        public UdpTransport(int recvBufSize, StructuredLogger.Log log) {
            this(WILDCARD_IP_ADDRESS, EPHEMERAL_PORT, recvBufSize, log);
        }

        // For setting up a server at a known address and port.
        public UdpTransport(String localAddress, int localPort, int recvBufSize, StructuredLogger.Log log) {
            this.localPort = localPort;
            this.log = log;
            this.recvBufSize = Math.max(0, recvBufSize);
            try {
                this.localAddress = InetAddress.getByName(localAddress);
                this.active = true;
            } catch (UnknownHostException e) {
                this.log.warn("UNKNOWN_HOST", "Could not resolve local host address: " + localAddress);
                this.localAddress = null;
                this.active = false;
            }
        }

        @Override
        // This is a "composite" address including both ip address and port.
        public Address resolveAddress(String address) {
            int i = address.lastIndexOf(':');
            if (i >= 0) {
                String nameOrIP = address.substring(0, i);
                String candidatePort = address.substring(i + 1);
                try {
                    int port = Integer.parseInt(candidatePort);
                    return resolveUdpAddress(nameOrIP, port); // *** EARLY RETURN ***
                } catch (NumberFormatException nfe) {
                    // Drop down ...
                }
            }
            throw new IllegalArgumentException("Badly formatted address: " + address);
        }

        public static Address resolveUdpAddress(String hostAddress, int port) {
            try {
                InetAddress inaddr = InetAddress.getByName(hostAddress);
                return new UdpAddress(inaddr, port);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Unable to resolve address" + hostAddress + ". Exception: " + e);
            }
        }

        @Override
        public RemoteNode newRemoteNode(Address remoteAddress) {
            String key = remoteAddress.stringRepresentation();
            return this.remoteNodeMap.computeIfAbsent(key, s -> new UdpRemoteNode((UdpAddress) remoteAddress));
        }

        @Override
        public void close() {
            stopListening();
        }

        @Override
        public void startListening(BiConsumer<String, RemoteNode> handler) {

            if (!this.active) {
                // We never initialized properly. So bail.
                this.log.err("CANNOT_START_LISTENING", "reason: failed initialization");
                return; // EARLY return...
            }

            DatagramSocket sock = getSocket();

            if (sock == null) {
                return; // *************** EARLY RETURN
            }

            Thread th = new Thread(() -> {
                final int MAX_MAP_SIZE = 100;
                HashMap<String, RemoteNode> map = new HashMap<>();
                byte[] receiveData = new byte[recvBufSize];

                try {
                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);

                    while (true) {

                        // Wipe out the map if it gets too large - it will be built up again.
                        if (map.size() > MAX_MAP_SIZE) {
                            map.clear();
                        }
                        log.trace("Waiting to receive UDP packet...");
                        sock.receive(receivePacket);
                        UdpAddress addr = new UdpAddress(receivePacket.getAddress(), receivePacket.getPort());
                        RemoteNode rn = newRemoteNode(addr);
                        String msg = new String(receivePacket.getData(), 0, receivePacket.getLength());
                        log.trace("RECV_PKT_DATA", msg);
                        handler.accept(msg, rn);
                        // Reset packet for next use
                        receivePacket.setData(receiveData);
                    }

                } catch (IOException e) {
                    if (!stoppingListening) {
                        this.log.err("Unexpected IO Exception " + e);
                    }
                }
            });
            th.start();
        }

        // Gets or creates socket (this.socket).
        private DatagramSocket getSocket() {
            DatagramSocket sock = null;
            synchronized (this.lock) {
                if (this.socket == null) {
                    try {
                        if (this.localPort == EPHEMERAL_PORT) {
                            this.socket = new DatagramSocket();
                        } else {
                            this.socket = new DatagramSocket(this.localPort, this.localAddress);
                        }
                    } catch (SocketException e) {
                        this.log.err("SocketException attempting to open socket local port: " + this.localPort
                                + "  local addr: " + this.localAddress);
                        this.socket = null;
                    }
                }
                sock = this.socket;
            }
            return sock;
        }

        @Override
        public void stopListening() {
            synchronized (this.lock) {
                if (this.socket != null) {
                    this.stoppingListening = true;
                    this.socket.close(); // Will cause IO exception in listening thread.
                    this.socket = null;
                }
            }
        }

        private static class UdpAddress implements DatagramTransport.Address {
            final InetSocketAddress saddr;

            UdpAddress(InetAddress addr, int port) {
                this.saddr = new InetSocketAddress(addr, port);
            }

            @Override
            public String stringRepresentation() {
                return this.saddr.toString();
            }

        }

        private class UdpRemoteNode implements DatagramTransport.RemoteNode {
            final UdpAddress udpAddr;

            UdpRemoteNode(UdpAddress udpAddr) {
                this.udpAddr = udpAddr;
            }

            @Override
            public Address remoteAddress() {
                return udpAddr;
            }

            @Override
            public void send(String msg) {
                DatagramSocket sock = getSocket();

                if (sock != null) {
                    byte[] sendData = msg.getBytes();
                    DatagramPacket pkt = new DatagramPacket(sendData, sendData.length, this.udpAddr.saddr);
                    try {
                        log.trace("SEND_PKT_DATA", msg);
                        sock.send(pkt);
                    } catch (IOException e) {
                        log.trace("SEND_ERROR", "reason: IOException e: " + e);
                        // e.printStackTrace();
                    }
                }
            }
        }

    }
}

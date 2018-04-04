// Various helper classes to make it easier to use the RobotComm communication infrastructure.
// Created by Joseph M. Joy (https://github.com/josephmjoy)

package com.rinworks.robotutils;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.BiConsumer;

import com.rinworks.robotutils.RobotComm.DatagramTransport;

public class CommUtils {

    public static class EchoServer implements Closeable {
        /**
         * Creates an echo server. Logging goes to the default logging directory (see
         * <code>LoggerUtils</code>), with sysName {serverName}.
         * 
         * @param address
         *            - IP address
         * @param port
         *            - IP port
         * @param serverName
         *            - Name to associate with this server. For logging purposes only.
         * @param channelNames
         *            - array of channel names.
         */
        public EchoServer(String address, int port, String serverName, String[] channelNames) {
        }

        /**
         * Runs the server. This call must be invoked only once, and it will block until
         * some exception is thrown or there is a CTRL-C break. Call close() when done
         * or on exception.
         */
        public void run() {
            print("running server");
        }

        @Override
        public void close() throws IOException {
            print("in EchoServer.close");

        }
    }

    public static class EchoClient implements Closeable {

        /**
         * Creates a RobotComm echo client. Logging goes to the default logging
         * directory (see <code>LoggerUtils</code>), with sysName {serverName}.
         * 
         * @param localAddress
         *            - local IP address
         * @param serverAddress
         *            - server IP address
         * @param remotePort
         *            - server IP port
         * @param clientName
         *            - used for logging
         */
        public EchoClient(String localAddress, String remoteAddress, int remotePort, String clientName) {

        }

        /**
         * Sends {count} messages over channel {channelName}, one every {periodMs}
         * milliseconds, with message size {msgSize}. Will block until all messages are
         * sent unless there is an exception or a CTRL-C event. This call may be invoked
         * multiple times (sequentially or concurrently). However, <code>close</code>
         * must be called after all invocations of <code>send*</code> are called.
         */
        public void sendMessages(long count, int periodMs, int msgSize, String channelName) {
            print("sending messages");
        }

        /**
         * Sends {count} commands over channel {channelName}, one every {periodMs}
         * milliseconds, with message size {msgSize}. Will block until all messages are
         * sent unless there is an exception or a CTRL-C event. This call may be invoked
         * multiple times (sequentially or concurrently). However, <code>close</code>
         * must be called after all invocations of <code>send*</code> are called.
         */
        public void sendCommands(long count, int periodMs, int msgSize, String channelName) {
            print("sending commands");
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
            print("sending realtime commands");
        }

        @Override
        public void close() throws IOException {
            print("in EchoClient.close");

        }
    }

    public static DatagramTransport createUdpTransport() {
        return new UdpTransport();
    }

    private static class UdpTransport implements DatagramTransport {

        @Override
        public Address resolveAddress(String address) {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public RemoteNode newRemoteNode(Address remoteAddress) {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public void close() {
            // TODO Auto-generated method stub

        }

        @Override
        public void startListening(BiConsumer<String, RemoteNode> handler) {
            // TODO Auto-generated method stub

        }

        @Override
        public void stopListening() {
            // TODO Auto-generated method stub

        }

    }

    private static void print(String s) {
        System.out.println(s);
    }

}

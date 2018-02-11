// RobotComm - UDP based communications for robot status and commands.
// Created by Joseph M. Joy (https://github.com/josephmjoy)
package com.rinworks.robotutils;

import java.io.Closeable;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Class to implement simple 2-way message passing over UDP
 *
 */
public class RobotComm {


    interface Address {
        String stringRepresentation();
    };

    interface RemoteNode extends Closeable {

        void send(String msg);

        Address getAddress();

        void close(); // idempotent.
    }

    /**
     * Sends a dynamically generated message periodically. The period was set when
     * the underlying object was created. Pause and resume may be called in any
     * order and from multiple threads, though results of doing so will be
     * unpredictable. The messages themselves will be sent as whole units.
     *
     */
    interface PeriodicSender extends Closeable {
        void pause();

        void resume();

        void close(); // will cancel all further attempts to start/stop.
    }

    interface SentCommand {
        enum COMMAND_STATUS {
            STATUS_PENDING, STATUS_COMPLETED, STATUS_ERROR_TIMEOUT, STATUS_ERROR_COMM, STATUS_CANCELED
        };

        String pollResponse();

        COMMAND_STATUS status();

        void cancel();
    }

    public interface ReceivedMessage {
        String message();

        long receivedTimestamp(); // when it was received.

        Channel channel();
    }
    
    interface ReceivedCommand {
        Channel channel();

        String command();

        long receivedTimestamp();

        void respond(String msg);
    }


    public interface Channel extends Closeable {
        
        enum Mode {
            MODE_SENDONLY, MODE_RECEIVEONLY, MODE_SENDRECEIVE
        };
        
        String channelName();
        Mode mode();

        RemoteNode remoteNode();

        ReceivedMessage pollReceivedMessage();

        ReceivedCommand pollReceivedCommand();

        void sendMessage(String message);

        SentCommand sendCommand(String command);

        PeriodicSender periodicSend(int period, Supplier<String> messageSource);

        void close();
    }

    interface Listner extends Closeable {

        /**
         * Listens for messages.
         * 
         * @param handler
         *            called when a message arrives. Will likely be called in some other
         *            thread's context. The handler is expected NOT to block. If time
         *            consuming operations need to be performed, queue the message for
         *            further processing, or implement a state machine. The handler
         *            *may* be reentered or called concurrently from another thread.
         *            Call close to stop new messages from being received.
         */
        void listen(BiConsumer<String, RemoteNode> handler);

        Address getAddress();

        void close(); // idempotent. handler MAY get called after close() returns.
    }

    public interface DatagramTransport extends Closeable {

        Address newAddress(String address);

        Listner newListener(Address localAddress);

        RemoteNode newRemoteNode(Address remoteAddress);

        void close(); // Closes all open listeners and remote notes.
    }

    public RobotComm(DatagramTransport transport, StructuredLogger.Log log) {

    }

    public Channel createChannel(RemoteNode node, String channelName, Channel.Mode mode) {
        return null;
    }

    /**
     * Creates a remote UDP port - for sending
     * 
     * @param nameOrAddress
     *            - either a name to be resolved or an dotted IP address
     * @param port
     *            - port number (0-65535)
     * @return remote port object
     */
    public static Address makeUDPRemoteAddress(String nameOrAddress, int port) {
        return null;
    }

    /**
     * Creates a local UDP port - for listening
     * 
     * @param nameOrAddress
     *            - either a name to be resolved or an dotted IP address
     * @param port
     *            - port number (0-65535)
     * @return local port object
     */
    public static Address makeUDPListnerAddress(String nameOrAddress, int port) {
        return null;
    }

}

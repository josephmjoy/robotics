// RobotComm - UDP based communications for robot status and commands.
// Created by Joseph M. Joy (https://github.com/josephmjoy)
package com.rinworks.robotutils;

/**
 * Class to implement simple 2-way message passing over UDP
 *
 */
public class RobotComm implements AutoCloseable {

	/**
	 * The message and remote port information associated with a received message.
	 *
	 */
	public class ReceivedMessage {

		public ReceivedMessage(String _message, RemotePort _returnPort) {
			message = _message;
			returnPort = _returnPort;
		}

		/**
		 * Received message
		 */
		public final String message;

		/**
		 * Port that may be used to send messages
		 */
		public final RemotePort returnPort;
	}

	/**
	 * Low level interface to send a text message to some destination.
	 *
	 */
	public interface RemotePort {

		void send(String msg);

		/**
		 * @return Text form of remote address
		 */
		String address();
	}

	/**
	 * Low level interface to receive a text message from some destination.
	 */
	public interface LocalPort {
		/**
		 * BLOCKS until it receives a message. Calling cancel() will make it throw a
		 * CancellationException.
		 * 
		 * @return - Text message received.
		 */
		ReceivedMessage receive();

		/**
		 * Cancels a pending receive.
		 */
		void cancel();

		/**
		 * @return Text form of local address
		 */
		String address();
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
	public static RemotePort makeUDPRemotePort(String nameOrAddress, int port) {
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
	public static LocalPort makeUDPLocalPort(String nameOrAddress, int port) {
		return null;
	}

	/**
	 * @param remote_port
	 *            - port used for listening
	 * @param log
	 *            - log to write internal logging and traces of messages.
	 */
	public RobotComm(RemotePort remote_port, StructuredLogger log) {

	}

	/**
	 * Begin listening
	 */
	public void listen() {

	}

	/**
	 * Close any open underlying resources such as sockets. Will automatically get
	 * called by the
	 */
	@Override
	public void close() {

	}

	/**
	 * Sends a message. Never blocks.
	 * 
	 * @param port
	 *            - remote port
	 * @param message
	 *            - message to send
	 */
	public void sendMessage(RemotePort port, String message) {

	}

	/**
	 * Retrieves the next received message, if any. Returns null otherwise. Never
	 * blocks.
	 * 
	 * @return Next received message or null if there are none.
	 */
	public ReceivedMessage nextReceivedMessage() {
		return null;
	}

}

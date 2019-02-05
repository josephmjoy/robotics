"""
A private module that implements concurrency-friendly versions of
queues, dictionaries and counters.
Author: JMJ
"""
import logging
import socket
import threading

from . import logging_helper
from . import _utils
from .comm.common import DatagramTransport
from .comm.robotcomm import RobotComm

_LOGNAME = "robotutils.commutils"
_LOGGER = logging.getLogger(_LOGNAME)
_TRACE = logging_helper.LevelSpecificLogger(logging_helper.TRACELEVEL, _LOGGER)


class UdpTransport(DatagramTransport):
    """Implements the DatagramTransport interface over UDP"""


    # For setting up a server at a known address and port.
    def __init__(self, recv_bufsize, *, local_host=None, local_port=None):
        """For clients - ask system to pick local address and port.
        {local_host} is the local DNS name or IP address. If not specified, the
        system will pick the default address.
        {local_port} is the port number.  If unspecifid, the system will pick an
        ephemeral port. However, a port must be specified if `start_listening`
        is going to be called.
        """
        self.lock = threading.Lock()
        self._listen_thread = None # background listening thread
        self._sock = None # Created on demand.
        self._local_host = local_host if local_host is not None else 'localhost'
        self._local_port = local_port # could be None
        self.recv_bufsize = recv_bufsize
        self._send_errors = 0 # Count of send errors - serialized via self.lock

    #
    # ABC DatagramTransport impementations
    #

    def start_listening(self, handler) -> None:
        """Bind to local address (specified in init) and start listening
        for incoming datagrams. {handler} is signature is
        handler(msg: str, rn: RemoteNode) -- see ABC DatagramTransportation
        for documentation."""

        if self._local_port is None:
            raise ValueError("Attempting to listen with local port set to None")
        address = self._make_sock_address(self._local_host, self._local_port)
        sock = self._getsocket()
        _TRACE("Binding socket to address %s", address)
        sock.bind(address)

        def listen_threadfn():
            try:
                while self._listen_thread:
                    if _TRACE.enabled():
                        _TRACE("RECV_WAIT Waiting to receive UDP packet...")
                    data, node = self._sock.recvfrom(self.recv_bufsize)
                    # We directly get the remote node in our 'node' format, which
                    # is (host, port), so don't need to call self.new_remote_node
                    msg = data.decode('utf-8')
                    if _TRACE.enabled():
                        _TRACE("RECV_PKT_DATA [%s] from %s", msg, str(node))
                    handler(msg, node)

            except Exception as exp: # pylint: disable=broad-except
                if self._listen_thread:
                    _LOGGER.exception("Error in socket.recvfrom or handler")
                else:
                    _TRACE("Expected exception ending listen. exp: %s", exp)

        with self.lock:
            if self._listen_thread:
                raise ValueError("Attempt to listen when already listening")
            thread = threading.Thread(target=listen_threadfn,
                                      name="DGRAM-LISTEN",
                                      daemon=True)
            self._listen_thread = thread

        _TRACE("Starting background listen thread %s", str(thread))
        thread.start()


    def stop_listening(self) -> None:
        """Stops listening if started. Idempotent. It will block if necessary until
        any background processig is complete"""
        with self.lock:
            sock = self._sock
            thread = self._listen_thread
            self._sock = None
            self._listen_thread = None

        if sock:
            sock.close() # Will cause listening thread to break out of recvfrom

        if thread:
            _TRACE("Waiting for listen thread %s to exit...", str(thread))
            thread.join()
            _TRACE("Done waiting for listen thread %s to exit...")

    def close(self) -> None:
        """Close the transport. Future sends will raise ValueError exceptions"""
        self.stop_listening()


    def send(self, msg, destination) -> None:
        """Sends a single text message. No exceptions are raised, however the
        first send error is logged as an error and subsequent errors generate trace
        messages"""
        try:
            sock = self._getsocket()
            if _TRACE.enabled():
                _TRACE("SEND_PKT_DATA msg=[%s] dest=[%s]", msg, str(destination))
            sock.sendto(msg.encode('utf8'), destination)
        except Exception as exp: # pylint: disable=broad-except
            with self.lock:
                first = self._send_errors == 0
                self._send_errors += 1

            if first:
                _LOGGER.exception("Exception raised during send")
            elif _TRACE.enabled():
                _TRACE("Exception raised during send. Exp={%s}", str(exp))

    #
    # Other public methods
    #

    def new_remote_node(self, remote_host, remote_port, *, lookup_dns=False) -> object:
        """Returns a remote node object corresponding to {(remote_host, remote_port)}.
        If {lookup_dns} it will perform a DNS lookup to resolve the host to an
        IP address - an operation that can take some time.
        Return value: an internal representation of the node. The object
        is immutable and may be used for hashing. Call str(return-value) to
        obtain a string representation of the node.
        """
        # The following is IPv4 only and returns only 1 address. At some point,
        # use socket.getaddrinfo to work with both IPv4 and IPv6.
        # Will block until DNS resolution completes unless its cached. Whether
        # DNS results are cached on the system is environment dependent.
        if lookup_dns:
            _TRACE("Waiting to resolve address [%s]", remote_host)
            remote_host = socket.gethostbyname(remote_host)
            _TRACE("Address resolves to %s", remote_host)
        return self._make_sock_address(remote_host, remote_port)

    #
    # Private methods
    #
    def _getsocket(self) -> object:
        """Creates self._sock on demand and returns it."""
        with self.lock:
            if not self._sock:
                self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock = self._sock
        return sock

    @staticmethod
    def _make_sock_address(host, port) -> object:
        """returns a (IP-address, port) tuple needed for socket.bind or
        socket.sendto.  No DNS resolution is attemptd on {host}"""
        assert host
        assert port
        return (host, port)


class EchoServer:
    """Implements a simple robotcomm echo server that echos messages, commands
    and rt-commands
    """

    def __init__(self, hostname, port, channel_names,
                 *, recv_bufsize=1024, server_name='server'):
        """
        Creates an echo server.
        Positional parameters:
            hostname, port - local host name and port associated with this
            server channel_names - array of channel names
        Optional keyword-only parameters:
            server_name - name to associate with this server. For logging
                purposes only.
            recv_bufSize - Size in bytes of the internal buffer used to receive
                incoming data. If it is too small, data will be truncated.
        """
        self.transport = UdpTransport(recv_bufsize, local_host=hostname,
                                      local_port=port)
        self.rcomm = RobotComm(self.transport)
        self.channels = [self.rcomm.new_channel(name) for name in channel_names]
        self.server_name = server_name


    def start(self) -> None:
        """Starts the server. It does not block. Rather it relies on the client
        repeatedly calling periodic_work to do its work.
        """
        _LOGGER.info("Starting server %s", self.server_name)
        for chan in self.channels:
            chan.start_receiving_commands()
            chan.start_receiving_messages()
            def rt_handler(cmd): # Echo back RT commands
                cmd.respond(cmd.msgtype, cmd.message)
            chan.startReceivingRtCommands(rt_handler)

        self.rcomm.start_listening()


    def periodic_work(self):
        """Perform periodic work"""
        self.rcomm.periodic_work()
        for chan in self.channels:

            cmd = chan.poll_received_command()
            for cmd in _utils.getsome(chan.poll_received_command):
                # Echo received command.
                cmd.respond(cmd.msgtype, cmd.message)

            for msg in _utils.getsome(chan.poll_received_message):
                # Echo received message.
                chan.send_message(msg.msgtype, msg.message, msg.remote_node)


    def stop(self) -> None:
        """ Stops a running server. Will block until the server is stopped. This call
        * must be called by a different thread from the one that is running, obviously.
        *   Will also close the server.
        """
        _LOGGER.info("Stopping server %s", self.server_name)
        self.rcomm.stop_listening()
        for chan in self.channels:
            chan.stop_receiving_commands()
            chan.stop_receiving_messages()
            chan.stop_receiving_rtcommands()

        for stats in self.rcomm.get_channel_statistics():
            _LOGGER.info("CHANNEL_STATS %s", str(stats))

        self.rcomm.close()
        self.transport.close()

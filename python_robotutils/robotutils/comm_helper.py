"""
A private module that implements concurrency-friendly versions of
queues, dictionaries and counters.
Author: JMJ
"""
import itertools
import logging
import socket
import threading
import time

from . import logging_helper
from . import _utils
from .comm.common import DatagramTransport
from .comm.robotcomm import RobotComm


_LOGNAME = "robotutils.commutils"
_LOGGER = logging.getLogger(_LOGNAME)
_TRACE = logging_helper.LevelSpecificLogger(logging_helper.TRACELEVEL, _LOGGER)


class UdpTransport(DatagramTransport): # pylint: disable=too-many-instance-attributes
    """Implements the DatagramTransport interface over UDP"""


    # For setting up a server at a known address and port.
    def __init__(self, *, recv_bufsize=1400, local_host=None, local_port=None):
        """For clients - ask system to pick local address and port.
        {local_host} is the local DNS name or IP address. If not specified, the
        system will pick the default address.
        {local_port} is the port number.  If unspecifid, the system will pick an
        ephemeral port. However, a port must be specified if `start_listening`
        is going to be called.
        """
        self._lock = threading.Lock()
        self._listen_thread = None # background listening thread
        self._deferred_listen_handler = None # Set in start_listening
        self._sock = None # Created on demand.
        self._local_host = local_host if local_host is not None else 'localhost'
        self._local_port = local_port # could be None
        self.recv_bufsize = recv_bufsize
        self._send_errors = 0 # Count of send errors - serialized via self._lock

    #
    # ABC DatagramTransport impementations
    #

    def start_listening(self, handler) -> None:
        """Bind to local address (specified in init) and start listening
        for incoming datagrams. {handler} is signature is
        handler(msg: str, rn: RemoteNode) -- see ABC DatagramTransportation
        for documentation."""

        # Because (at least on Windows sockets) one cannot start listening on an ephemeral
        # port - one has to first send at least one packet - listening when the local port
        # is None is deferred until the first send packet. This fact is logged.
        #
        sock = None
        with self._lock:
            if self._deferred_listen_handler or self._listen_thread:
                raise ValueError("Attempt to listen when already listening")
            if self._sock is None:
                if self._local_port is None:
                    # We can't create the socket now; have to defer to the first send
                    self._deferred_listen_handler = handler
                else:
                    sock = self._sock = self._makesocket()

        if sock is None:
            _TRACE("Deferring starting to listen until first send")
        else:
            self._really_start_listening(sock, handler)


    def stop_listening(self) -> None:
        """Stops listening if started. Idempotent. It will block if necessary until
        any background processig is complete"""
        with self._lock:
            sock = self._sock
            thread = self._listen_thread
            self._sock = None
            self._listen_thread = None
            self._deferred_listen_handler = None

        if sock:
            sock.close() # Will cause listening thread to break out of recvfrom

        if thread:
            _TRACE("Waiting for listen thread %s to exit...", str(thread))
            thread.join()
            _TRACE("Done waiting for listen thread %s to exit...", str(thread))

    def close(self) -> None:
        """Close the transport. Future sends will raise ValueError exceptions"""
        self.stop_listening()


    def send(self, msg, destination) -> None:
        """Sends a single text message. No exceptions are raised, however the
        first send error is logged as an error and subsequent errors generate trace
        messages"""
        try:

            # Get a hold of the socket AND see if we need to
            # start deferred background receive handling
            with self._lock:
                if not self._sock:
                    self._sock = self._makesocket()
                sock = self._sock
                recv_handler = self._deferred_listen_handler
                if recv_handler:
                    self._deferred_listen_handler = None

            if _TRACE.enabled():
                _TRACE("SEND_PKT_DATA msg=[%s] dest=[%s]", msg, str(destination))
            sock.sendto(msg.encode('utf8'), destination)

            # Potentially start previously-deferred listening
            if recv_handler:
                self._really_start_listening(sock, recv_handler)

        except Exception as exp: # pylint: disable=broad-except
            with self._lock:
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

    def _really_start_listening(self, sock, handler) -> None:
        """Start a thread to listen for incoming packets on socket {sock}"""

        def listen_threadfn():
            try:
                if self._local_port is not None:
                    # Non-ephimeral server port - we can bind to it
                    address = self._make_sock_address(self._local_host, self._local_port)
                    _TRACE("START_LISTEN Binding socket to address %s", address)
                    sock.bind(address)

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

        with self._lock:
            assert self._listen_thread is None
            assert self._deferred_listen_handler is None
            thread = threading.Thread(target=listen_threadfn,
                                      name="DGRAM-LISTEN",
                                      daemon=True)
            self._listen_thread = thread

        _TRACE("Starting background listen thread %s", str(thread))
        thread.start()


    @staticmethod
    def _makesocket():
        """Create a datagram socket"""
        return socket.socket(socket.AF_INET, socket.SOCK_DGRAM)


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

    DEFAULT_PORT = 41890 # Default Echo Server Port Number
    DEFAULT_CHANNEL = 'echo' # Default Echo Server Channel Name
    DEFAULT_BUFSIZE = 1024

    def __init__(self, hostname, *, port=DEFAULT_PORT,
                 recv_bufsize=DEFAULT_BUFSIZE,
                 channel_names=None):
        """
        Creates an echo server.
        Positional parameters:
            hostname - local host name associated with this server
        Optional keyword-only parameters:
            port - server port - defaults to EchoServer.DEFAULT_PORT
            recv_bufSize - Size in bytes of the internal buffer used to receive
                incoming data. If it is too small, data will be truncated.
            channel_names - sequence of channel names. Defaults to
                [EchoServer.DEFAULT_CHANNEL]
        """
        self.hostname = hostname
        self.port = port
        self._transport = UdpTransport(recv_bufsize=recv_bufsize, local_host=hostname,
                                       local_port=port)
        self._rcomm = RobotComm(self._transport, name="rc_server")
        if channel_names is None:
            channel_names = [EchoServer.DEFAULT_CHANNEL]
        self._channels = [self._rcomm.new_channel(name) for name in channel_names]


    def start(self) -> None:
        """Starts the server. It does not block. Rather it relies on the client
        repeatedly calling periodic_work to do its work.
        """
        _LOGGER.info("Starting server hostname: %s port: %s",
                     self.hostname, self.port)
        for chan in self._channels:
            chan.start_receiving_commands()
            chan.start_receiving_messages()
            def rt_handler(cmd): # Echo back RT commands
                cmd.respond(cmd.msgtype, cmd.message)
            chan.start_receiving_rtcommands(rt_handler)

        self._rcomm.start_listening()


    def periodic_work(self):
        """Perform periodic work"""
        self._rcomm.periodic_work()
        for chan in self._channels:

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
        _LOGGER.info("Stopping server hostname: %s port: %s",
                     self.hostname, self.port)
        self._rcomm.stop_listening()
        for chan in self._channels:
            chan.stop_receiving_commands()
            chan.stop_receiving_messages()
            chan.stop_receiving_rtcommands()

        for stats in self._rcomm.get_channel_statistics():
            _LOGGER.info("CHANNEL_STATS %s", str(stats))

        self._rcomm.close()
        self._transport.close()



class EchoClient: # pylint: disable=too-many-instance-attributes
    """Implements an echo client that generates messages,
    commands and rtcommands over a UDP transport and reports responses"""


    def __init__(self, server_name, *, server_port=EchoServer.DEFAULT_PORT,
                 recv_bufsize=EchoServer.DEFAULT_BUFSIZE,
                 channel=EchoServer.DEFAULT_CHANNEL, client_name='echoclient'):
        """
        Creates an echo client.
        Positional parameters:
            hostname, port - echo server server host name and port
            server channel_names - array of channel names
        Optional keyword-only parameters:
            server_name - name to associate with this server. For logging
                purposes only.
            server_port - server port - defaults to EchoServer.DEFAULT_PORT
            channel - name of channel. Defaults to EchoServer.DEFAULT_CHANNEL
            recv_bufSize - Size in bytes of the internal buffer used to receive
                incoming data. If it is too small, data will be truncated.
                 - used for logging
            client_name - identifying name of this client - used in logging and in
                generating message content.
        """
        self.client_name = client_name
        self._transport = UdpTransport(recv_bufsize=recv_bufsize)
        remotenode = self._transport.new_remote_node(server_name, server_port)
        self._rcomm = RobotComm(self._transport, name="rc_client")
        self._channel = self._rcomm.new_channel(channel)
        self._channel.bind_to_remote_node(remotenode)
        self.set_parameters() # Set defaults


    def set_parameters(self, *, count=4, size=None, rate=1, bodytype='hello',
                       body=None, response_timeout=1.0):
        """Sets optional send parameters. All default to None
            count - number to send. If None will send indifinately.
            size - approximate size of message/command body. If unspecified
                an appropriate size is chosen.
            rate - number of sends/submits per second
            bodytype - message/command type
            body - message/command body. If unspecified, it will be filled in with
                a message including a sequence number and timestamp.
            response_timeout - maximum time in seconds waiting for a response. This will
                determine how much time to wait before ending transmissions, but does not
                limit the rate of sends/submits
        """
        self.count = count
        self.size = size
        self.rate = rate # sends per second
        self.bodytype = bodytype
        self.body = body
        self.response_timeout = response_timeout


    def send_messages(self, response_handler=None):
        """Sends messages based on parameters set earlier (either defaults or
        in a call to set_parameters.  Will BLOCK until all messages are sent
        or an exception is thrown.  Will wait up to the response timeout
        (defaults to 1 second and settable via set_parameters) for
        responses.

        response_handler(msgtype:str, msg:str) - called whenever a
            response is received. There may not be any correspondence between
            sent and received messages.
        """
        _LOGGER.info("Echo client %s Starting to send %d messages",
                     self.client_name, self.count)
        counter = self._setup()
        try:
            channel = self._channel
            start = time.time()
            for i in counter:
                delta = time.time() - start
                # First time through predicted_delta is 0 ...
                predicted_delta = i / float(self.rate)
                # Sleep if needed to catch up with prediction
                if delta < predicted_delta:
                    amount = predicted_delta - delta
                    _TRACE("Going to sleep %0.3f seconds", amount)
                    time.sleep(amount)

                self._rcomm.periodic_work()
                msgtype = self.bodytype
                msgbody = self._make_message_body(i)
                _TRACE("ECHO_SEND_MSG msgtype: %s  msgbody: %s",
                       msgtype, msgbody)
                channel.send_message(msgtype, msgbody)
                recvmsg = channel.poll_received_message()
                if recvmsg:
                    _TRACE("ECHO_RECV_MSG msgtype: %s  msgbody: %s",
                           recvmsg.msgtype, recvmsg.msgbody)
                    if response_handler:
                        response_handler(recvmsg.msgtype, recvmsg.message)

            _LOGGER.info("Done sending %d messages", self.count)
        except KeyboardInterrupt:
            _LOGGER.info("KeyboardInterrupt raised. Quitting")
        self._teardown()


    _MESSAGE_TEMPLATE = "sn: {} ts: {}"
    _EXTENDED_MESSAGE_TEMPLATE = "{} -pad: {}" # for padding messages


    def _setup(self):
        """
        Starts listening and returns a counter to be used for
        sequencing messages.
        """
        self._rcomm.start_listening() # For responses
        self._channel.start_receiving_messages()

        if not self.rate:
            counter = range(0) # nothing to send if rate is None or 0
        elif self.count is None:
            counter = itertools.count() # Count for ever
        else:
            counter = range(self.count) # could still be 0
        _TRACE("client exiting setup")
        return counter

    def _teardown(self):
        """Shut down after sending"""
        self._channel.stop_receiving_messages()
        self._rcomm.stop_listening()

    def _make_message_body(self, index) -> str:
        """Construct a message body - suitable for a
        message, command or rt command"""
        if self.body is not None:
            return self.body # ---- EARLY RETURN ---

        timestamp = round(time.perf_counter() * (10**6)) # microseconds
        message = self._MESSAGE_TEMPLATE.format(index, timestamp)
        ext_template = self._EXTENDED_MESSAGE_TEMPLATE
        # This will attempt to pad the message so that it is
        # about self.size - it's not exact but good enough
        nextra = len(message) + len(ext_template) - 4 # -4 for 2 '{}' in template
        if self.size and nextra < self.size:
            message = ext_template.format(message, '-'*(self.size - nextra))
        return message

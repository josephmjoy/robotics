"""
A private module that implements concurrency-friendly versions of
queues, dictionaries and counters.
Author: JMJ
"""
import logging
import socket
import threading

from . import logging_helper
from .comm.common import DatagramTransport

_LOGNAME = "robotutils.udp"
_LOGGER = logging.getLogger(_LOGNAME)
_TRACE = logging_helper.LevelSpecificLogger(logging_helper.TRACELEVEL, _LOGGER)


class UdpTransport(DatagramTransport):
    """Implements the DatagramTransport interface over UDP"""


    # For setting up a server at a known address and port.
    def __init__(self, recv_bufsize, *, local_host=None, local_port=None):
        """For clients - ask system to pick local address and port.
        {local_address} is the DNS name or IP address. If not specified, the
        system will pick the default address. {local_port} is the port number.
        If unspecifid, the system will pick an ephemeral port.
        """
        self.lock = threading.Lock()
        self._listen_thread = None # background listening thread
        self._sock = None # Created on demand.
        self._local_address = self._make_sock_address(local_host, local_port)
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

        def listen_threadfn():
            try:
                while self._listen_thread:
                    _TRACE("RECV_WAIT Waiting to receive UDP packet...")
                    data, node = self._sock.recvfrom(self.recv_bufsize)
                    # We directly get the remote node in our 'node' format, which
                    # is (host, port), so don't need to call self.new_remote_node
                    msg = data.decode('utf-8')
                    _TRACE("RECV_PKT_DATA [%s] from %s", msg, str(node))
                    handler(msg, node)

            except Exception: # pylint: disable=broad-except
                if _TRACE.enabled() or not self._listen_thread:
                    _LOGGER.exception("Error in socket.recvfrom or handler")

        with self.lock:
            if self._listen_thread:
                raise ValueError("Attempt to listen when already listening")
            thread = threading.Thread(target=listen_threadfn,
                                      name="DGRAM-LISTEN",
                                      daemon=True)
            self._listen_thread = thread

        _TRACE("Starting background listen thread %s", str(thread))
        thread.run()


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
        """returns a (IP-address, port) tuple needed for socket.bind or socket.sendto.
        No DNS resolution is attemptd on {host}"""
        return (host, port)

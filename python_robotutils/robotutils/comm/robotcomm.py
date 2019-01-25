"""
This module implements robotcomm communications functionality. Ported by JMJ
from the Java implementation (class RobotComm)
"""
import abc
import collections
import threading
import random

from .channel import Channel
from ._commlogging import _logger, _trace

from . import _commlogmsgtypes as _LMT
from .. import concurrent_helper as ch
from . import _protocol

# TODO: Fix these...
# pylint: disable=invalid-name

class DatagramTransport(abc.ABC):
    """
    Interface for a datagram transport that is provided to an instance of
    RobotComm to provide the underlying raw communication.
    """

    class RemoteNode(abc.ABC):
        """Represents a remote node"""

        @abc.abstractmethod
        def send(self, msg) -> None:
            """Sends a single text message"""

        @abc.abstractmethod
        def address(self) -> str:
            """"Text representation of destination address"""

    @abc.abstractmethod
    def start_listening(self, handler) -> None:
        """
        Starts listening for incoming datagrams. This will likely use up
        resources, such as a dedicated thread, depending on the implementation.

        handler -- called when a message arrives. Will likely be called in
                    some other thread's context. The handler is expected NOT to
                    block. If time consuming operations need to be performed,
                    queue the message for further processing, or implement a
                    state machine. The handler *may* be reentered or called
                    concurrently from another thread.  Call stopListening to
                    stop new messages from being received.  """


    @abc.abstractmethod
    def stop_listening(self) -> None:
        """Stops listening"""


    @abc.abstractmethod
    def new_remotenode(self, address): # -> RemoteNode:  but pylint doesn't like it
        """
        Creates a new remote node from a string representation of the address
        WARNING: Depending on the transport and the string representation, this
        method can block as it attempts to perform network operations.
        """


    @abc.abstractmethod
    def close(self) ->None:
        """Closes all open listeners and remote notes."""


#
# End of Abstract Base Classes
#


ReceivedMessage = collections.namedtuple('ReceivedMessage',
                                         ('msgtype',
                                          'message',
                                          'remote_address',
                                          'received_timestamp',
                                          'channel'))
# Following requires Python 3.5+
ReceivedMessage.__doc__ += """: Incoming message as reported to client"""


ServerStatistics = collections.namedtuple('ServerStatistics',
                                          ('rcvdCommands',
                                           'rcvdCMDs',
                                           'sentCMDRESPs',
                                           'rcvdCMDRESPACKs',
                                           'curSvrRecvdCmdMapSize',
                                           'curSvrRcvdCmdIncomingQueueSize',
                                           'curSvrRcvdCmdCompletedQueueSize'))


ClientStatistics = collections.namedtuple('ClientStatistics',
                                          ('sentCommands',
                                           'sentCMDs',
                                           'rcvdCMDRESPs',
                                           'sentCMDRESPACKs',
                                           'curCliSentCmdMapSize',
                                           'curCliSentCmdCompletionQueueSize'))


ClientRtStatistics = collections.namedtuple('ClientRtStatistics',
                                            ('approxSentRtCommands',
                                             'approxSendRTCMDs',
                                             'approxRcvdRTCMDRESPs',
                                             'approxRtTimeouts',
                                             'curCliSentRtCmdMapSize'))


ChannelStatistics = collections.namedtuple('ChannelStatistics',
                                           ('channelName',
                                            'sentMessages',
                                            'rcvdMessages',
                                            'ClientStatistics',
                                            'clientStats',
                                            'clientRtStats',
                                            'serverStats'))

class RobotComm():
    """The top-level class for robotcomm"""


    def __init__(self, transport):
        """Initializes an instance of RobotComm with the specified transport."""

        self.commClosed = False # set to false when close() is called.
        self.listening = False  # Whether or not client is listening
        self.transport = transport
        self.listenLock = threading.Lock()
        self.rand = random.random
        self.channels = ch.ConcurrentDict()

    #
    # Public methods
    #

    def new_channel(self, channelName) -> Channel:
        """Returns a new channel"""
        if self.commClosed:
            raise  ValueError("Robot comm is closed!")
        if _protocol.containschars(channelName, _protocol.BAD_CHANNEL_CHARS):
            raise ValueError("channel name has invalid characters: [{}]"
                             .format(channelName))

        # We pass the Channel class as the function in the 2nd arg below! It
        # will be supplied with the initializer arguments if/when invoked.
        chan, created = self.channels.upsert(channelName, Channel, self,
                                             channelName, self.transport)
        if created:
            raise ValueError("Channel with name [{}] exists"
                             .format(channelName))
        return chan


    def start_listening(self) -> None:
        """Start listening for messages on all channels"""
        start = False
        with self.listenLock:
            if not self.listening:
                start = True
                self.listening = True
        if start:
            _logger.info("STARTING LISTENING")
            self.transport.start_listening(self._listen_handler)


    def stop_listening(self) -> None:
        """Stop listening"""
        stop = False
        with self.listenLock:
            if not self.listening:
                self.listening = False
                stop = True
        if stop:
            _logger.info("STOPPING LISTENING")
            self.transport.stop_listening()


    def is_listening(self):
        """Whether or not we are currently listening"""
        # Not synchronized as there is no point
        return self.listening


    def close(self) -> None:
        """Close.  THis will cause subsequent attempts to create channels to
        fail with an ValueError exception."""
        self.commClosed = True
        self.stop_listening()

        # Close all channels
        self.channels.process_all(lambda name, chan: chan.close())

        # Channels should pull themselves off the list as they close...
        assert self.channels.empty()

        self.transport.close()

    def periodic_work(self):
        """ MUST be called periodically so that periodic maintenance tasks can be
        done, chiefly handling of re-transmits."""
        if not self.commClosed and self.is_listening():
            self.channels.process_all(lambda name, chan: chan.periodic_work())

    def get_channel_statistics(self):
        """Gets an enumeration of channel statistics"""
        stats = []
        func = lambda name, chan: stats.append(chan.getStats())
        self.channels.process_all(func)
        return stats


    #
    # Private methods and attributes of RobotComm
    #


    def _listen_handler(self, msg, rn):
        """The handler Robotcomm provides to the underlying transport to handle
        incoming messages"""
        try:
            dgram = _protocol.datagram_from_str(msg)
            assert dgram
        except ValueError:
            _trace(_LMT.DROPPING_RECEIVED_MESSAGE, "Malformed header.")
            return  # ------------ EARLY RETURN ---------------

        chan = self.channels.get(dgram.channel)

        if not chan:
            _trace(_LMT.DROPPING_RECEIVED_MESSAGE,
                   "Unknown channel. channel: %s", dgram.channel)
        else:
            DgEnum = _protocol.DatagramType
            if dgram.dgType == DgEnum.RTCMD:
                chan.server.handleReceivedRTCMD(dgram, rn)
            elif dgram.dgType == DgEnum.RTCMDRESP:
                chan.client.handleReceivedRTCMDRESP(dgram, rn)
            elif dgram.dgType == DgEnum.MSG:
                chan.handleReceivedMessage(dgram, rn)
            elif dgram.dgType == DgEnum.CMD:
                chan.server.handleReceivedCMD(dgram, rn)
            elif dgram.dgType == DgEnum.CMDRESP:
                chan.client.handleReceivedCMDRESP(dgram, rn)
            elif dgram.dgType == DgEnum.CMDRESPACK:
                chan.server.handleReceivedCMDRESPACK(dgram, rn)
            else:
                # we have already validated the message, so shouldn't get here.
                assert False

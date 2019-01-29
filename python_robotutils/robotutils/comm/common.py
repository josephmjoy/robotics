"""Various abstract classes, named tuples and constants used multiple
other modules under .comm go here"""
import abc
from collections import namedtuple

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

        handler(msg: str, rn: RemoteNode) --
            called when a message arrives. Prototype: handler(msg: str, rn:
            RemoteNode): Will likely be called in some other thread's context.
            The handler is expected NOT to block. If time consuming operations
            need to be performed, queue the message for further processing, or
            implement a state machine. The handler *may* be reentered or called
            concurrently from another thread.  Call stop_listening to stop new
            messages from being received.  """

    @abc.abstractmethod
    def stop_listening(self) -> None:
        """Stops listening"""

    @abc.abstractmethod
    def close(self) ->None:
        """Closes all open listeners and remote notes."""


ReceivedMessage = namedtuple('ReceivedMessage',
                             ('msgtype',
                              'message',
                              'remote_node',
                              'received_timestamp',
                              'channel'))
# Following requires Python 3.5+
ReceivedMessage.__doc__ += """: Incoming message as reported to client"""


ServerStatistics = namedtuple('ServerStatistics',
                              ('rcvdCommands',
                               'rcvdCMDs',
                               'sentCMDRESPs',
                               'rcvdCMDRESPACKs',
                               'curSvrRecvdCmdMapSize',
                               'curSvrRcvdCmdIncomingQueueSize',
                               'curSvrRcvdCmdCompletedQueueSize'))


ClientStatistics = namedtuple('ClientStatistics',
                              ('sentCommands',
                               'sentCMDs',
                               'rcvdCMDRESPs',
                               'sentCMDRESPACKs',
                               'curCliSentCmdMapSize',
                               'curCliSentCmdCompletionQueueSize'))


ClientRtStatistics = namedtuple('ClientRtStatistics',
                                ('approxSentRtCommands',
                                 'approxSendRTCMDs',
                                 'approxRcvdRTCMDRESPs',
                                 'approxRtTimeouts',
                                 'curCliSentRtCmdMapSize'))


ChannelStatistics = namedtuple('ChannelStatistics',
                               ('channelName',
                                'sentMessages',
                                'rcvdMessages',
                                'clientStats',
                                'clientRtStats',
                                'serverStats'))

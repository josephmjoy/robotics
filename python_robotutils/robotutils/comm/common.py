"""Various abstract classes, named tuples and constants used multiple
other modules under .comm go here"""
import abc
from collections import namedtuple

class DatagramTransport(abc.ABC):
    """
    Interface for a datagram transport that is provided to an instance of
    RobotComm to provide the underlying raw communication.

    NOTE TO IMPLEMENTORS: The destination/remote node object is opaque
    to the clinents, however there are two requirements for this object:
    1. It must support str to obtain a text representation of the address
    2. It must be suitable for use as a key or set element, hence immutable.
       Strings and tuples work well for this.
    """

    @abc.abstractmethod
    def send(self, msg, destination) -> None:
        """Sends a single text message. {destination} is an opaque
        node object returned by get_remote_node or in the transpoet
        receive handler"""

    @abc.abstractmethod
    def start_listening(self, handler) -> None:
        """
        Starts listening for incoming datagrams. This will likely use up
        resources, such as a dedicated thread, depending on the implementation.

        handler(msg: str, remote_node: Object) --
            called when a message arrives. The handler will
            likely be called in some other thread's context.
            The handler MUST NOT block. If time consuming operations
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
                              ('rcvd_commands',
                               'rcvd_CMDs',
                               'sent_CMDRESPs',
                               'rcvd_CMDRESPACKs',
                               'cur_SvrRecvdCmdMap_size',
                               'cur_SvrRcvdCmdIncomingQueue_size',
                               'cur_SvrRcvdCmdCompletedQueue_size'))


ClientStatistics = namedtuple('ClientStatistics',
                              ('sent_commands',
                               'sent_CMDs',
                               'rcvd_CMDRESPs',
                               'sent_CMDRESPACKs',
                               'cur_CliSentCmdMap_size',
                               'cur_CliSentCmdCompletionQueue_size'))


ClientRtStatistics = namedtuple('ClientRtStatistics',
                                ('approx_sent_rtcommands',
                                 'approx_send_RTCMDs',
                                 'approx_rcvd_RTCMDRESPs',
                                 'approx_rttimeouts',
                                 'cur_CliSentRtCmdMap_size'))


ChannelStatistics = namedtuple('ChannelStatistics',
                               ('channel_name',
                                'sent_messages',
                                'rcvd_messages',
                                'client_stats',
                                'client_rtstats',
                                'server_stats'))

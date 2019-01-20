"""
This module implements robotcomm communications functionality. Ported by JMJ
from the Java implementation (class RobotComm)
"""
import abc
import collections


class DatagramTransport(abc.ABC):
    """
    Interface for a datagram transport that is provided to an instance of RobotComm
    to provide the underlying raw communication.
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
        Starts listening for incoming datagrams. This will likely use up resources,
        such as a dedicated
        thread, depending on the implementation.

        handler -- called when a message arrives. Will likely be called in some other
                    thread's context. The handler is expected NOT to block. If time
                    consuming operations need to be performed, queue the message for
                    further processing, or implement a state machine. The handler
                    *may* be reentered or called concurrently from another thread.
                    Call stopListening to stop new messages from being received.
        """


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


ReceivedMessage = collections.namedtuple('ReceivedMessage',
                                         'message msgtype remote_address '
                                         + 'received_timestamp channel')
ReceivedMessage.__doc__ += ":blah" # Requires Python 3.5+


class Channel(abc.ABC):
    """Interface to a RobotComm channel"""

    @abc.abstractmethod
    def bind_to_remote_node(self, address) -> None:
        """Binds to a remote node"""


    @abc.abstractmethod
    def start_receiving_messages(self, ) -> None:
        """Starts receiving messages"""

    @abc.abstractmethod
    def send_message(self, msgtype, message) -> None:
        """Send a message"""

    @abc.abstractmethod
    def poll_received_message(self) -> ReceivedMessage:
        """Returns a received message, None otherwise"""

    @abc.abstractmethod
    def stop_receiving_messages(self) -> None:
        """Stops receiving messages"""


#
# End of Abstract Base Classes
#


class RobotComm():
    """The top-level class for robotcomm"""

    def __init__(self, transport):
        """Initializes an instance of RobotComm with the specified transport."""
        self._channelstats = []
        self._transport = transport

    #
    # Abstract method implementations...
    #

    def new_channel(self, address) -> Channel:
        """Returns a new channel"""
        return self._ChannelImplementation(address)


    def start_listening(self) -> None:
        """Start listening for messages on all channels"""

    def stop_listening(self) -> None:
        """Stop listening"""

    def close(self) -> None:
        """Close"""

    def get_channel_statistics(self):
        """Gets an enumeration of channel statistics"""
        return self._channelstats

    #
    # End of abstract method implementations
    #

    class _ChannelImplementation(Channel):
        """Hello!"""

        def __init__(self, address):
            """Initialzies the channel object"""
        #
        # Abstract method implementations...
        #

        def bind_to_remote_node(self, address) -> None:
            """Binds to a remote node"""


        def start_receiving_messages(self, ) -> None:
            """Starts receiving messages"""

        def send_message(self, msgtype, message) -> None:
            """Send a message"""

        def poll_received_message(self) -> ReceivedMessage:
            """Returns a received message, None otherwise"""
            return ReceivedMessage()

        def stop_receiving_messages(self) -> None:
            """Stops receiving messages"""

        #
        # End of abstract method implementations
        #

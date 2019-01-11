"""
This module implements robotcomm communications functionality. Ported by JMJ
from the Java implementation (class RobotComm)
"""
import abc


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

#
#  ------------ Private Methods ----------------
#

"""
This module implements a robotcomm channel.Ported by JMJ
from the Java implementation (class Channel)
"""
#CIRCULAR: from . import robotcomm as rc


class Channel:
    """Hello!"""

    def __init__(self, rcomm, channel_name, transport):
        """[INTERNAL] Initialzies the channel object"""
        # It may get discarded if concurrently a channel is created with the same name
        _rcomm = rcomm
        _name = channel_name
        _transport = transport
    #
    # Public methods
    #

    def name(self) -> str:
        """Returns the name of the channel"""

    def remote_address(self) -> str:
        """Returns remote address if bound, None otherwise"""

    def bind_to_remote_node(self, address) -> None:
        """This channel will only communicate with the specified remote node,
        including received messages and commands.
        Can be changed on the fly. Pass in None to clear."""

    def start_receiving_messages(self, ) -> None:
        """Starts receiving messages"""

    def send_message(self, msgtype, message) -> None:
        """Send a message"""

    def send_mesage(self, msgtype, message, address):
        """Will drop message if not bound to a remote node."""

    def poll_received_message(self): #  -> ReceivedMessage:
        """Returns a received message, None otherwise"""
        return None # return ReceivedMessage()

    def stop_receiving_messages(self) -> None:
        """Stops receiving messages. Will drop incoming messages in queue."""

    def close(self) -> None:
        """Closes the channel. Any pending commands and messages may be dropped"""

    #
    # Private methods and attributes
    #

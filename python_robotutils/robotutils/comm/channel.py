"""
This module implements a robotcomm channel.Ported by JMJ
from the Java implementation (class Channel)
"""
import time

from ._commlogging import _logger, _trace
from ..concurrent_helper import ConcurrentDeque
from .common import ChannelStatistics, ReceivedMessage
from ._protocol import Datagram, DatagramType
from . import _protocol

#TODO remove this eventually...
# pylint: disable=fixme
# pylint: disable=invalid-name

class Channel: #pylint: disable=too-many-instance-attributes
    """A channel communications object.
    Read-only attributes: name, remote_node"""


    def __init__(self, rcomm, channel_name, transport):
        """[INTERNAL] Initialzies the channel object"""

        # Public read-only attribures
        self.name = channel_name
        self.remote_node = None # though this can change


        # It may get discarded if concurrently a channel is created with the
        # same name
        self._rcomm = rcomm
        self._transport = transport
        self._client = None
        self._server = None
        # self.client = new CommClientImplementation(channelName, log)
        # self.server = new CommServerImplementation(this, log)

        # Receiving messages
        self._pendingRecvMessages = ConcurrentDeque() # Messages flow left to right
        self._receiveMessages = False
        self._closed = False

        # These are purely for statistics reporting
        # They are not incremented atomically, so are approximate
        self._approxSentMessages = 0
        self._approxRcvdMessages = 0

    #
    # Public methods
    #

    def bind_to_remote_node(self, node) -> None:
        """This channel will only communicate with the specified remote node,
        including received messages and commands.
        Can be changed on the fly. Pass in None to clear."""
        self.remote_node = node # Could override an existing one. That's ok
        if self._client:
            self._client.bind_to_remote_node(self, node)

    def start_receiving_messages(self, ) -> None:
        """Starts receiving messages"""
        if self._closed:
            raise ValueError("Attempt to start receiving on a closed channel.")
        self._receiveMessages = True

    def send_message(self, msgtype, message, node=None) -> None:
        """Send a message to {node}. If node is unspecified, it will
        send a message to the bound remote node, if any. If {node}
        is unspecified and there is no bound node then message will be
        dropped."""
        node = node or self.remote_node
        if not node:
            raise ValueError("No node specified")
        if _protocol.containschars(msgtype, _protocol.BAD_FIELD_CHARS):
            raise ValueError("Invalid message type: [{}]".format(msgtype))
        if self._closed:
            _trace("Dropping message because channel %s closed", self.name)
        else:
            # TODO: We should directly generate the on-the-wire format - no
            # need to construct and then serialize a datagram!
            dgram = Datagram(DatagramType.MSG, self.name, msgtype,
                             None, None, message)
            self._approxSentMessages += 1
            node.send(_protocol.str_from_datagram(dgram))

    def poll_received_message(self): #  -> ReceivedMessage:
        """Returns a received message, None otherwise"""
        if not self._receiveMessages:
            raise ValueError("Polling when listening is not enabled.")
        if self._closed:
            return None
        try:
            return self._pendingRecvMessages.pop()
        except IndexError:
            pass # nothing in queue
        return None

    def stop_receiving_messages(self) -> None:
        """Stops receiving messages. Will drop incoming messages in queue."""
        self._receiveMessages = False

    def close(self) -> None:
        """Closes the channel. Any pending commands and messages may be
        dropped"""
        _trace("REMOVING_CHANNEL", "name: %s", self.name)
        # pylint: disable=protected-access # (for _channels access below)
        removed = self._rcomm._channels.remove_instance(self.name, self)
        if removed:
            if self._client:
                self._client.close()
            self._closed = True
        else:
            _logger.warning("Channel %s not closed. removed=%s",
                            self.name, removed)

    def getstats(self):
        """Returns a tuple-of-tuple containing various statistics counters"""
        clientStats = clientRtStats = serverStats = None
        allstats = (self._approxSentMessages, self._approxRcvdMessages,
                    clientStats, clientRtStats, serverStats)
        return ChannelStatistics(self.name, *allstats)

    def submit_command(self, cmdtype, command, client_context,
                       add_to_completion_queue): # -> SentCommand
        """Submit a command. If {add_to_completion_queue} then the command will
        be added to the completion queue, which can be polled by calling
        poll_completed_command.  Returns a SentCommand object"""
        return self._client.submit_command(cmdtype, command, client_context,
                                           add_to_completion_queue)

    def  submit_rtcommand(self, cmdtype, command, timeout,
                          on_complete): # -> SentCommand
        """Submit a real time command. Callback {on_complete} will be notified
        on commmand completion, supplied with single parameter of type
        SentCommand. Returns a SentCommand object."""
        return self._client.submit_rtcommand(cmdtype, command, timeout,
                                             on_complete)

    def poll_completed_command(self): # -> SentCommand
        """Returns the earliest completed command if any, None otherwise. Does
        not block"""
        return None if self._closed else self._client.poll_completed_command()

    def start_receiving_commands(self) -> None:
        """Start receiving commands"""
        # TODO: Implement

    def stop_receiving_commands(self) -> None:
        """Stop receiving commands"""
        # TODO: Implement

    def poll_received_command(self): # -> ReceivedCommand
        """Retrieve a queued received command if present, None otherwise"""
        return self._server.poll_received_command()

    def start_receiving_rtcommands(self, handler) -> None:
        """Indicate that received commands should  start being accepted"""
        self._server.start_receiving_rtcommands(handler)

    def stop_receiving_rtcommands(self) -> None:
        """Stop accepting incoming commands"""
        self._server.stop_receiving_rtcommands()

    #
    # End of public methods
    #

    def _handle_received_message(self, dgram, rn) -> None:
        """Robotcomm-internal method - called when a message arrives for this
        channel"""
        if self._receiveMessages:
            ts = int(1000 * time.time()) # MS since current unix epoch
            rm = ReceivedMessage(dgram.bodyType, dgram.body, rn, ts, self)
            self._approxRcvdMessages += 1
            self._pendingRecvMessages.appendleft(rm)

    def _periodic_work(self):
        """Called internally by Robotcomm to perform periodic work"""
        if not self._closed:
            self._client.periodic_work()
            self._server.periodic_work()

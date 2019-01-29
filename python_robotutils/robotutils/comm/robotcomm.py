"""
This module implements robotcomm communications functionality. Ported by JMJ
from the Java implementation (class RobotComm)
"""
import threading
import random

from ._commlogging import _logger, _trace

from . import _commlogmsgtypes as _LMT
from .. import concurrent_helper
from . import _protocol
from .channel import Channel



# TODO: Fix these...
# pylint: disable=invalid-name

class RobotComm():
    """The top-level class for robotcomm"""

    def __init__(self, transport):
        """Initializes an instance of RobotComm with the specified transport."""

        self.commClosed = False # set to false when close() is called.
        self.listening = False  # Whether or not client is listening
        self.transport = transport
        self.listenLock = threading.Lock()
        self.rand = random.random
        self.channels = concurrent_helper.ConcurrentDict()

    #
    # Public methods
    #

    def new_channel(self, channelName) -> Channel:
        """Returns a new channel"""
        if self.commClosed:
            raise  ValueError("Robot comm is closed!")
        if _protocol.containschars(channelName, _protocol.BAD_FIELD_CHARS):
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
            # pylint: disable=protected-access
            self.channels.process_all(lambda name, chan: chan._periodic_work())


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
            server = chan._server # pylint: disable=protected-access
            client = chan._client # pylint: disable=protected-access
            DgEnum = _protocol.DatagramType
            if dgram.dgType == DgEnum.RTCMD:
                server.handle_received_RTCMD(dgram, rn)
            elif dgram.dgType == DgEnum.RTCMDRESP:
                client.handle_received_RTCMDRESP(dgram, rn)
            elif dgram.dgType == DgEnum.MSG:
                 # pylint: disable=protected-access
                chan._handle_received_message(dgram, rn)
            elif dgram.dgType == DgEnum.CMD:
                server.handle_received_CMD(dgram, rn)
            elif dgram.dgType == DgEnum.CMDRESP:
                client.handle_received_CMDRESP(dgram, rn)
            elif dgram.dgType == DgEnum.CMDRESPACK:
                server.handle_received_CMDRESPACK(dgram, rn)
            else:
                # we have already validated the message, so shouldn't get here.
                assert False

"""
This module implements robotcomm communications functionality. Ported by JMJ
from the Java implementation (class RobotComm)
"""
import threading
import random
import logging

from .. import logging_helper
from .. import concurrent_helper
from . import _protocol
from .channel import Channel

_LOGNAME = "robotutils.comm"
_LOGGER = logging.getLogger(_LOGNAME)
_TRACE = logging_helper.LevelSpecificLogger(logging_helper.TRACELEVEL, _LOGGER)
_INFO = logging_helper.LevelSpecificLogger(logging.INFO, _LOGGER)

class RobotComm():
    """The top-level class for robotcomm"""

    def __init__(self, transport, *, name="robotcomm"):
        """Initializes an instance of RobotComm with the specified transport.
        {name} identifies this instance for logging purposes."""

        self._closed = False # set to false when close() is called.
        self.listening = False  # Whether or not client is listening
        self.transport = transport
        self.name = name
        self._listenlock = threading.Lock()
        self.rand = random.random
        self._channels = concurrent_helper.ConcurrentDict()

    #
    # Public methods
    #

    def new_channel(self, channel_name) -> Channel:
        """Returns a new channel"""
        if self._closed:
            raise  ValueError("Robot comm is closed!")
        if _protocol.containschars(channel_name, _protocol.BAD_FIELD_CHARS):
            raise ValueError("channel name has invalid characters: [{}]"
                             .format(channel_name))

        # We pass the Channel class as the function in the 2nd arg below! It
        # will be supplied with the initializer arguments if/when invoked.
        chan, created = self._channels.upsert(channel_name, Channel, self,
                                              channel_name, self.transport)
        if not created:
            raise ValueError("Channel with name [{}] exists"
                             .format(channel_name))
        return chan


    def start_listening(self) -> None:
        """Start listening for messages on all channels"""
        start = False
        with self._listenlock:
            if not self.listening:
                start = True
                self.listening = True
        if start:
            _INFO("START_LISTENING instance: %s", self.name)
            self.transport.start_listening(self._listen_handler)


    def stop_listening(self) -> None:
        """Stop listening"""
        stop = False
        with self._listenlock:
            if not self.listening:
                self.listening = False
                stop = True
        if stop:
            _INFO("STOP_LISTENING instance: %s)", self.name)
            self.transport.stop_listening()


    def is_listening(self):
        """Whether or not we are currently listening"""
        # Not synchronized as there is no point
        return self.listening


    def close(self) -> None:
        """Close.  THis will cause subsequent attempts to create channels to
        fail with an ValueError exception."""
        self._closed = True
        self.stop_listening()

        # Close all channels
        self._channels.process_all(lambda name, chan: chan.close())

        # Channels should pull themselves off the list as they close...
        assert self._channels.empty()

        self.transport.close()


    def periodic_work(self):
        """ MUST be called periodically so that periodic maintenance tasks can be
        done, chiefly handling of re-transmits."""
        if not self._closed and self.is_listening():
            # pylint: disable=protected-access
            self._channels.process_all(lambda name, chan: chan._periodic_work())


    def get_channel_statistics(self):
        """Gets an enumeration of channel statistics"""
        stats = []
        func = lambda name, chan: stats.append(chan.getstats())
        self._channels.process_all(func)
        return stats


    #
    # Private methods and attributes of RobotComm
    #


    def _listen_handler(self, msg, remotenode):
        """The handler Robotcomm provides to the underlying transport to handle
        incoming messages"""
        try:
            dgram = _protocol.datagram_from_str(msg)
            assert dgram
        except ValueError:
            _TRACE("DROP_RECV Malformed header.")
            return  # ------------ EARLY RETURN ---------------

        chan = self._channels.get(dgram.channel)

        if not chan:
            _TRACE("DROP_RECV Unknown channel. channel: %s", dgram.channel)
        else:
            server = chan._server # pylint: disable=protected-access
            client = chan._client # pylint: disable=protected-access
            DgEnum = _protocol.DatagramType
            dgtype = dgram.dgtype
            if dgtype == DgEnum.RTCMD:
                server.handle_received_RTCMD(dgram, remotenode)
            elif dgtype == DgEnum.RTCMDRESP:
                client.handle_received_RTCMDRESP(dgram, remotenode)
            elif dgtype == DgEnum.MSG:
                 # pylint: disable=protected-access
                chan._handle_received_message(dgram, remotenode)
            elif dgtype == DgEnum.CMD:
                server.handle_received_CMD(dgram, remotenode)
            elif dgtype == DgEnum.CMDRESP:
                client.handle_received_CMDRESP(dgram, remotenode)
            elif dgtype == DgEnum.CMDRESPACK:
                server.handle_received_CMDRESPACK(dgram, remotenode)
            else:
                # we have already validated the message, so shouldn't get here.
                assert False

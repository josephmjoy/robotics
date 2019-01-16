'''
Unit tests for the robotutils.robotcomm module.

This is a port of the JUnit test for the corresponding Java
robotutils class, ConfigurationHelper
Author: JMJ
'''

import unittest
import threading
import random
import logging
import collections

from . import robotcomm as rc
from ..conc import concurrent_helper as ch

logger = logging.getLogger(__name__) # pylint: disable=invalid-name

def tracefunc(*args, **kwargs):
    """simply call debug"""
    logger.debug(args, kwargs)
# set trace to None below to disable tracing in code
trace = tracefunc # pylint: disable=invalid-name



TransportStats = collections.namedtuple('TransportStats', 'sends recvs forcedrops randomdrops')

#pylint: disable=invalid-name
class MockRemoteNode(rc.DatagramTransport.RemoteNode):
    """Test remote node implementation"""


    def __init__(self, transport, address):
        """Construct a node under {transport} with the specified {address}"""
        self._transport = transport
        self._address = address


    def send(self, msg) -> None:
        """Sends {msg} over transport"""
        self._transport.numSends.next()
        if self._force_drop(msg):
            if trace:
                trace("TRANSPORT FORCEDROP:\n[%s]", msg)
            self._transport.numforcedrops.next()
            return # **************** EARLY RETURN *****

        if self._non_forcedrop():
            if trace:
                trace("TRANSPORT: RANDDROP\n[%s]", msg)
            self._transport.numRandomDrops.next()
            return # **************** EARLY RETURN *****

        self._transport.handleSend(msg)


    def address(self) -> str:
        """Text representation of destination address"""
        return self._address


    def _force_drop(self, msg) -> bool:
        """Whether or not to always drop this message"""
        return self._transport.ALWAYSDROP_TEXT in msg


    def _non_forcedrop(self) -> bool:
        """Whether to drop even if it's not a forced drop"""
        if not self._transport.closed and not self._transport.zeroFailures():
            return False
        return random.random() < self._transport.failurerate


class MockTransport(rc.DatagramTransport): # pylint: disable=too-many-instance-attributes
    """Implements the mock transport used in these tests"""

    ALWAYSDROP_TEXT = "TRANSPORT-MUST-DROP"

    def __init__(self):
        """Initialize MockTransport"""
        self.loopbacknode = MockRemoteNode(self, "loopback")
        self.recvqueue = ch.ConcurrentDeque()
        self.transport_timer = None
        self.numsends = ch.AtomicNumber(0)
        self.numrecvs = ch.AtomicNumber(0)
        self.numforcedrops = ch.AtomicNumber(0)
        self.numrandomdrops = ch.AtomicNumber(0)
        self.closed = False
        self.failurerate = 0
        self.maxdelay = 0
        #private BiConsumer<String, RemoteNode> clientRecv; we support only one listener at a time.
        self.client_recv = None # set in start_listening

    #
    # DatagramTransport methods
    #
    def start_listening(self, handler) -> None: # BiConsumer<String, RemoteNode> handler
        """Listen should only be called once."""
        assert not self.client_recv
        self.client_recv = handler

    def stop_listening(self) -> None:
        """Stop previously-started listening sessions."""
        assert self.client_recv
        self.client_recv = None

    def new_remotenode(self, address) -> rc.DatagramTransport.RemoteNode:
        """COnstruct and return a new remote node"""
        return MockRemoteNode(self, address)

    def close(self) -> None:
        """Close the transport"""
        self.closed = True
        if self.transport_timer:
            self.transport_timer.cancel()

    #
    # Methods to be used by tests
    #

    def setTransportCharacteristics(self, failurerate, maxdelay) -> None:
        """Changes transport characteristics: sends datagrams with {failurerate} failure
        rate, and {maxdelay} max delay per packet."""
        assert 0 <= failurerate <= 1
        assert maxdelay >= 0
        self.failurerate = failurerate
        self.maxdelay = maxdelay

    def zeroFailures(self) -> bool:
        """{failurerate} "close enough" to 0 is considered to be zero-failures."""
        return abs(self.failurerate) < 1e-7

    def noDelays(self) -> bool:
        """Returns True IFF the transport should never delay"""
        return self.maxdelay == 0


    def getStats(self) -> TransportStats:
        """Return transport statistics"""
        return TransportStats(
            sends=self.numsends.value(),
            recvs=self.numrecvs.value(),
            forcedrops=self.numforcedrops.value(),
            randomdrops=self.numrandomdrops.value())

    def getMaxDelay(self) -> float:
        """Return the max statistical delay between packets"""
        return self.maxdelay

    def getFailureRate(self) -> float:
        """Return the statistical packet failure rate"""
        return self.failurerate

    def handleSend(self, msg) -> None:
        """Internally handle a send request"""
        if self.closed:
            self.numforcedrops.next()
            return
        if self.noDelays():
            self._receiveData(msg)
        else:
            delay = random.random() * self.maxdelay
            def timertask():
                if not self.closed:
                    # Delayed send
                    self._receiveData(msg)
                else:
                    self.numforcedrops.next()

            self.transport_timer = threading.Timer(delay, timertask)
            self.transport_timer.start()

    #
    # Private methods
    #

    def _receiveData(self, s) -> None:
        """Internally handle a received message"""

        if self.client_recv:
            self.numrecvs.next()
            if trace:
                trace("TRANSPORT RECV:\n[{}]\n", s)
            self.client_recv(s, self.loopbacknode)
        else:
            self.numforcedrops.next()


class TestRobotComm(unittest.TestCase):
    """Container for robotcomm unit tests"""

    def test_mock_transport_simple(self):
        """Simple test of our own test mock transport!"""
        transport = MockTransport()
        transport.close()
        self.assertTrue(transport)

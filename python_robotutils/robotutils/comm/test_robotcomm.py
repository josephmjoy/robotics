'''
Unit tests for the robotutils.robotcomm module.

This is a port of the JUnit test for the corresponding Java
robotutils class, ConfigurationHelper
Author: JMJ
'''

import unittest
import random
import logging
import collections
import time

from . import robotcomm as rc
from ..conc import concurrent_helper as ch

logger = logging.getLogger(__name__) # pylint: disable=invalid-name

def trace(*args, **kwargs):
    """simply call debug"""
    logger.debug(args, kwargs)

def tracing():
    """ Whether to trace or not"""
    return True



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
        self._transport.numsends.next()
        if self._force_drop(msg):
            if tracing():
                trace("TRANSPORT FORCEDROP:\n[%s]", msg)
            self._transport.numforcedrops.next()
            return # **************** EARLY RETURN *****

        if self._non_forcedrop():
            if tracing():
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

    def __str__(self):
        return self._address


class MockTransport(rc.DatagramTransport): # pylint: disable=too-many-instance-attributes
    """Implements the mock transport used in these tests"""

    ALWAYSDROP_TEXT = "TRANSPORT-MUST-DROP"

    def __init__(self, local_address):
        """Initialize MockTransport"""
        self.loopbacknode = MockRemoteNode(self, local_address)
        self.recvqueue = ch.ConcurrentDeque()
        self.transport_timer = None
        self.numsends = ch.AtomicNumber(0)
        self.numrecvs = ch.AtomicNumber(0)
        self.numforcedrops = ch.AtomicNumber(0)
        self.numrandomdrops = ch.AtomicNumber(0)
        self.closed = False
        self.failurerate = 0
        self.maxdelay = 0
        self.client_recv = None # set in start_listening
        self.scheduler = ch.EventScheduler()

    #
    # DatagramTransport methods
    #
    def start_listening(self, handler) -> None: # BiConsumer<String, RemoteNode> handler
        """Listen should only be called once."""
        assert not self.client_recv
        self.client_recv = handler
        self.scheduler.start()

    def stop_listening(self) -> None:
        """Stop previously-started listening sessions."""
        assert self.client_recv
        self.client_recv = None

    def new_remotenode(self, address) -> rc.DatagramTransport.RemoteNode:
        """COnstruct and return a new remote node"""
        return MockRemoteNode(self, address)

    def close(self) -> None:
        """Close the transport"""
        self.scheduler.cancel_all()
        # print("Stopping scheduler...")
        self.scheduler.stop(block=True)
        # print("Scheduler stopped")
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

            self.scheduler.schedule(delay, timertask)

    #
    # Private methods
    #

    def _receiveData(self, s) -> None:
        """Internally handle a received message"""

        if self.client_recv:
            self.numrecvs.next()
            if tracing():
                trace("TRANSPORT RECV:\n[{}]\n", s)
            self.client_recv(s, self.loopbacknode)
        else:
            self.numforcedrops.next()


class TestRobotComm(unittest.TestCase):
    """Container for robotcomm unit tests"""

    LOCAL_ADDRESS = "loopback"

    @staticmethod
    def cointoss():
        """Rreturns True with 0.5 probabilyt"""
        return random.random() < 0.5

    def test_mock_transport_simple(self): # pylint: disable=too-many-locals
        """Simple test of our own test mock transport!"""
        msgcount = 10000
        expected_msgcount = 0

        def genmsg(x):
            nonlocal expected_msgcount
            msg = 'msg-' + str(x)
            if self.cointoss():
                expected_msgcount += 1
                return msg
            # force drop
            return msg + MockTransport.ALWAYSDROP_TEXT

        messages = {genmsg(i): ch.AtomicNumber(0) for i in range(msgcount)}
        recvcount = ch.AtomicNumber(0)
        errcount = ch.AtomicNumber(0) # count of unexpected messages recvd

        def receivemsg(msg, node):
            #print("Received message [{}] from {}".format(msg, node))
            if node.address != self.LOCAL_ADDRESS:
                errcount.next()
            recvcount.next()
            counter = messages.get(msg)
            if counter:
                counter.next()
            else:
                errcount.next() # unexpected message

        transport = MockTransport(self.LOCAL_ADDRESS)
        failurerate = 0
        maxdelay = 1
        transport.setTransportCharacteristics(failurerate, maxdelay)
        transport.start_listening(receivemsg)
        node = transport.new_remotenode("loopback")
        for msg in messages:
            node.send(msg) # We're sending the keys
        #print("Waiting...")
        while recvcount.value() < expected_msgcount:
            time.sleep(0.1)
        #print("Received {} messages".format(msgcount))
        self.assertEqual(recvcount.value(), expected_msgcount)

        # Check that expected messages are received exactly once, and
        # unexpected messages are not received.
        for msg, counter in messages.items():
            expected = 1
            if  MockTransport.ALWAYSDROP_TEXT in msg:
                expected = 0
            self.assertEqual(counter.value(), expected, "msg count for:" + msg)

        transport.close()
        print("transport_simple: received {}/{} messages".format(recvcount, msgcount))

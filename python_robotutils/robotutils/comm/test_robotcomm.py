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
import itertools
import concurrent.futures

from . import robotcomm as rc
from .. import concurrent_helper as conc
from .common import DatagramTransport


# TODO: track down and fix all the TODOs
# pylint: disable=fixme

logger = logging.getLogger(__name__) # pylint: disable=invalid-name


def getsome(func, maxnum=None, *, sentinel=None):
    """A generator that returns up to {maxum} things. It
    will return less if {func}():
     - raises an IndexError exception
     - raises a StopIteration exception
     - returns the sentinel value.
    If {maxnum} is not specified it defaults to infinity
    >>> for x in getsome((x for x in range(10)).__next__, 5):
    ...     print(x, end=' ')
    0 1 2 3 4
    >>>
    """
    try:
        iter_ = range(maxnum) if maxnum is not None else itertools.count()
        for _ in iter_:
            value = func()
            if value == sentinel:
                return
            yield value
    except (IndexError, StopIteration):
        pass


def _trace(*args, **kwargs):
    """simply call debug"""
    logger.debug(args, kwargs)

def _tracing():
    """ Whether to trace or not"""
    return True




TransportStats = collections.namedtuple('TransportStats',
                                        'sends recvs forcedrops randomdrops')

#pylint: disable=invalid-name
class MockRemoteNode(DatagramTransport.RemoteNode):
    """Test remote node implementation"""


    def __init__(self, transport, address):
        """Construct a node under {transport} with the specified {address}"""
        self._transport = transport
        self._address = address


    def send(self, msg) -> None:
        """Sends {msg} over transport"""
        self._transport.numsends.next()
        if self._force_drop(msg):
            _trace("TRANSPORT FORCEDROP:\n[%s]", msg)
            self._transport.numforcedrops.next()
            return # **************** EARLY RETURN *****

        if self._nonforce_drop():
            _trace("TRANSPORT: RANDDROP\n[%s]", msg)
            self._transport.numrandomdrops.next()
            return # **************** EARLY RETURN *****

        self._transport.handleSend(msg)


    def address(self) -> str:
        """Text representation of destination address"""
        return self._address

    def isrunning(self) -> bool:
        """Returns if the transport is able to send and receive packets"""
        return self._transport.isrunning()

    def _force_drop(self, msg) -> bool:
        """Whether or not to always drop this message"""
        return self._transport.ALWAYSDROP_TEXT in msg

    def _nonforce_drop(self) -> bool:
        """Whether randomly drop message"""
        if self._transport.closed:
            return True
        if self._transport.zeroFailures():
            return False
        return random.random() < self._transport.failurerate

    def __str__(self):
        return self._address


class MockTransport(DatagramTransport): # pylint: disable=too-many-instance-attributes
    """Implements the mock transport used in these tests"""

    ALWAYSDROP_TEXT = "TRANSPORT-MUST-DROP"

    def __init__(self, local_address):
        """Initialize MockTransport. {local_address}" can be any string."""
        self.loopbacknode = MockRemoteNode(self, local_address)
        self.recvqueue = conc.ConcurrentDeque()
        self.numsends = conc.AtomicNumber(0)
        self.numrecvs = conc.AtomicNumber(0)
        self.numforcedrops = conc.AtomicNumber(0)
        self.numrandomdrops = conc.AtomicNumber(0)
        self.closed = False
        self.failurerate = 0
        self.maxdelay = 0
        self.client_recv = None # set in start_listening
        self.scheduler = conc.EventScheduler()

    #
    # DatagramTransport methods
    #
    def start_listening(self, handler) -> None:
        """Listen should only be called once."""
        assert not self.client_recv
        self.client_recv = handler
        self.scheduler.start()

    def stop_listening(self) -> None:
        """Stop previously-started listening sessions."""
        assert self.client_recv
        self.client_recv = None

    def close(self) -> None:
        """Close the transport"""
        self.scheduler.cancel_all()
        # print("Stopping scheduler...")
        self.scheduler.stop(block=True)
        # print("Scheduler stopped")
        self.closed = True

    #
    # Methods to be used by tests
    #

    def new_remotenode(self, address) -> DatagramTransport.RemoteNode:
        """COnstruct and return a new remote node"""
        return MockRemoteNode(self, address)


    def setTransportCharacteristics(self, failurerate, maxdelay) -> None:
        """Changes transport characteristics: sends datagrams with {failurerate} failure
        rate, and {maxdelay} seconds max delay per packet."""
        assert 0 <= failurerate <= 1
        assert maxdelay >= 0
        self.failurerate = failurerate
        self.maxdelay = maxdelay

    def healthy(self) -> bool:
        """Returns if the underlying transport is healthy, i.e., can still send/recv messages."""
        return self.scheduler.healthy()

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
            _trace("TRANSPORT RECV:\n[{}]\n", s)
            self.client_recv(s, self.loopbacknode)
        else:
            self.numforcedrops.next()


class TestMockTransport(unittest.TestCase):
    """Container for mock transport unit tests"""

    LOCAL_ADDRESS = "loopback"

    @staticmethod
    def cointoss():
        """Rreturns True with 0.5 probabilyt"""
        return random.random() < 0.5

    def test_mock_transport_without_random_failures(self): # pylint: disable=too-many-locals
        """Sends a bunch of messages and verifies each one is received exactly once."""
        msgcount = 10000
        expected_msgcount = 0
        failurerate = 0
        maxdelay = 1

        def genmsg(x):
            nonlocal expected_msgcount
            msg = 'msg-' + str(x)
            if self.cointoss():
                expected_msgcount += 1
                return msg
            # force drop
            return msg + MockTransport.ALWAYSDROP_TEXT

        messages = {genmsg(i): conc.AtomicNumber(0) for i in range(msgcount)}
        recvcount = conc.AtomicNumber(0)
        errcount = conc.AtomicNumber(0) # count of unexpected messages recvd

        def receivemsg(msg, node):
            # print("Received message [{}] from {}".format(msg, node))
            if node.address() != self.LOCAL_ADDRESS:
                errcount.next()
            recvcount.next()
            counter = messages.get(msg)
            if counter:
                counter.next()
            else:
                errcount.next() # unexpected message

        transport = MockTransport(self.LOCAL_ADDRESS)
        transport.setTransportCharacteristics(failurerate, maxdelay)
        transport.start_listening(receivemsg)
        node = transport.new_remotenode(self.LOCAL_ADDRESS)
        for msg in messages:
            node.send(msg) # We're sending the keys
        # print("Waiting... for {} messages".format(expected_msgcount))
        while recvcount.value() < expected_msgcount and transport.healthy():
            time.sleep(0.1)
        # print("Received {} messages".format(msgcount))

        # Need to close before any test failure assertions, otherwise
        # scheduler thread will never exit
        transport.close()

        self.assertEqual(errcount.value(), 0)
        self.assertEqual(recvcount.value(), expected_msgcount)

        # Check that expected messages are received exactly once, and
        # unexpected messages are not received.
        for msg, counter in messages.items():
            expected = 1
            if  MockTransport.ALWAYSDROP_TEXT in msg:
                expected = 0
            self.assertEqual(counter.value(), expected, "msg count for:" + msg)

        print("transport_simple: received {}/{} messages".format(recvcount, msgcount))


    def test_mock_transport_with_random_failures(self):
        """Sends a bunch of messages with random transport failures."""
        msgcount = 10000
        failurerate = 0.3
        maxdelay = 1 # seconds

        recvcount = conc.AtomicNumber(0)
        messages = {'msg-' + str(i) for i in range(msgcount)}
        transport = MockTransport(self.LOCAL_ADDRESS)
        transport.setTransportCharacteristics(failurerate, maxdelay)

        # def receivemsg(msg, node):
        #     recvcount.next()
        transport.start_listening(lambda msg, node: recvcount.next())

        node = transport.new_remotenode("loopback")
        for msg in messages:
            node.send(msg) # We're sending the keys
        time.sleep(maxdelay)

        # Need to close before any test failure assertions, otherwise
        # scheduler thread will never exit
        transport.close()

        # Actual failure rate must match expected to within 0.1
        self.assertAlmostEqual(recvcount.value()/msgcount, 1-failurerate, 1)
        print("transport_simple: received {}/{} messages".format(recvcount, msgcount))


# Keeps track of a single test message
TestMessageRecord = collections.namedtuple('TestMessageRecord', 'id alwaysDrop msgType msgBody')


# Keeps track of a command and expected response
TestCommandRecord = collections.namedtuple('TestCommandRecord', 'cmdRecord respRecord rt timeout')


class StressTester: # pylint: disable=too-many-instance-attributes
    """Test engine that instantiates and runs robotcomm stress tests with
    various parameters"""

    def __init__(self, harness, nThreads, transport):
        """Initializes the stress tester"""
        self.nThreads = nThreads
        self.transport = transport
        self.harness = harness

        # Protected
        self.rand = random.Random()
        self.nextId = conc.AtomicNumber(1)
        self.scheduler = conc.EventScheduler()
        self.executor = concurrent.futures.ThreadPoolExecutor(nThreads)
        self.invoker = conc.ConcurrentInvoker(self.executor, logger)
        self.ch = None # set in open

        self.msgMap = conc.ConcurrentDict()
        self.droppedMsgs = conc.ConcurrentDeque()
        # TODO: implement
        # self.cmdMap = conc.ConcurrentDict()
        # self.cmdCliSubmitQueue = conc.ConcurrentDeque()
        # self.cmdSvrComputeQueue = conc.ConcurrentDeque()
        # self.droppedCommands = conc.ConcurrentDeque()
        # self.droppedResponses = conc.ConcurrentDeque()
        self.rc = rc.RobotComm(transport)


    def open(self):
        """Starts listening, etc."""
        remotenode = self.transport.new_remotenode("localhost")
        self.ch = self.rc.new_channel("testChannel")
        self.ch.bind_to_remote_node(remotenode) # TODO: change to set_destination
        self.rc.start_listening()

    # private
    def pruneDroppedMessages(self) -> None:
        """Keep count of dropped messages by removing the older
        half of them if their number grows larger than DROP_TRIGGER"""
        DROP_TRIGGER = 1000
        # Remember that the message map and queue of dropped messages are
        # concurrently modified so sizes are estimates.
        sizeEst = len(self.droppedMsgs) # warning: O(n) operation
        if sizeEst > DROP_TRIGGER:
            dropCount = sizeEst // 2
            _trace("PURGE", "Purging about %s force-dropped messages",
                   dropCount)
            for mr in getsome(self.droppedMsgs.pop, dropCount):
                self.msgMap.remove_instance(mr.id, mr) # O(log(n))

    # private
    def purgeAllDroppedMessages(self) -> None:
        """Remove all dropped messages (at least the ones that were in the
        queue when we entered this method)."""
        for mr in getsome(self.droppedMsgs.pop):
            self.msgMap.remove_instance(mr.id, mr) # O(log(n))

    # private
    def submitMessageBatch(self, nMessages, submissionTimespan,
                           alwaysDropRate) -> None:
        """sent a batch of messages over {submissionTimespan} seconds"""
        assert self.rc
        assert self.ch
        assert alwaysDropRate <= 1
        assert submissionTimespan >= 0

        _trace("SUBMIT", "Beginning to submit %d messages", nMessages)

        def delayed_send(id_, msgType, msgBody):
            def really_send():
                _trace("SEND", "Sending message with id %d", id_)
                self.invoker.invoke(self.ch.send_message, msgType, msgBody)
            delay = self.rand.random() * submissionTimespan # seconds
            self.scheduler.schedule(delay, really_send)

        # Send messages at random times
        for _ in range(nMessages):
            id_ = self.nextId.next()
            alwaysDrop = self.rand.random() < alwaysDropRate
            mr = new_message_record(id_, alwaysDrop)
            self.msgMap.set(id_, mr)
            if alwaysDrop:
                self.droppedMsgs.appendleft(mr)
            delayed_send(id_, mr.msgType, mr.msgBody)

        def receive_all():
            # Pick up all messages received so far and verify them...
            def process():
                for rm in getsome(self.ch.poll_received_messages):
                    self.processReceivedMessage(rm)
            self.invoker.invoke(process)

        # Poll for received messages at random times
        recvAttempts = max(nMessages // 100, 10)
        maxReceiveDelay = submissionTimespan + self.transport.getMaxDelay()
        for i in range(recvAttempts):
            delay = self.rand.random() * submissionTimespan
            # Special case of i == 0 - we schedule our final receive attempt to
            # be AFTER the last packet is actually sent by the transport (after
            # a potential delay)
            if i == 0:
                delay = maxReceiveDelay + 1
            self.scheduler.schedule(delay, receive_all)


    def submitMessages(self, nMessages, submissionRate, alwaysDropRate):
        """
        Stress test sending, receiving and processing of commands.
            nMessages - number of messages to submit
            submissionRate - rate of submission per second
            alwaysDropRate - rate at which datagrams are always dropped by
                             the transport
        """
        BATCH_SPAN_SECONDS = 1.0
        MAX_EXPECTED_TRANSPORT_FAILURES = 1000000
        # If more than LARGE_COUNT messages, there should be NO transport send
        # failures else our tracked messages will start to accumulate and take
        # up too much memory.
        expectedTransportFailures = nMessages * self.transport.getFailureRate()
        if expectedTransportFailures > MAX_EXPECTED_TRANSPORT_FAILURES:
            logger.error("Too many transport failures expected %d",
                         int(expectedTransportFailures))
            logger.error("Abandoning test.")
            self.harness.fail("Too much expected memory consumption to run this test.")

        self.ch.start_receiving_messages()

        messagesLeft = nMessages
        while messagesLeft >= submissionRate:
            t0 = time.time() # UTC, seconds
            self.submitMessageBatch(submissionRate, BATCH_SPAN_SECONDS,
                                    alwaysDropRate)
            messagesLeft -= submissionRate
            t1 = time.time()
            sleepTime = max(BATCH_SPAN_SECONDS * 0.1,
                            BATCH_SPAN_SECONDS - (t1 - t0))
            time.sleep(sleepTime)
            self.pruneDroppedMessages()

        timeLeftSeconds = messagesLeft / submissionRate
        self.submitMessageBatch(messagesLeft, timeLeftSeconds, alwaysDropRate)
        time.sleep(timeLeftSeconds)

        # Having initiated the process of sending all the messages, we now
        # have to wait for ??? and verify that exactly those messages that
        # are expected to be received are received correctly.
        maxReceiveDelay = self.transport.getMaxDelay()
        _trace("WAITING", "Waiting to receive up to %d messages", nMessages)
        time.sleep(maxReceiveDelay)
        for _ in range(10): # retry 10 times
            stats = self.transport.getStats()
            if len(self.msgMap) <= stats.randomdrops:
                break
            time.sleep(0.250)
            self.purgeAllDroppedMessages()

        # Now we do final validation.
        self.finalSendMessageValidation()

        self.cleanup()

    # private
    def processReceivedMessage(self, rm):
        """
        Extract id from message.
        Look it up - we had better find it in our map.
        Verify that we expect to get it - (not alwaysDrop)
        Verify we have not already received it.
        Verify message type and message body is what we expect.
        If all ok, remove this from the hash map.
        """
        msgType = rm.msgType()
        msgBody = rm.message()
        try:
            nli = msgBody.index('\n')
            strId = msgBody[nli+1:]
            id_ = int(strId, 16)
            _trace("RECVMSG", "Received message with id %d", id)
            mr = self.msgMap.get(id_)
            self.harness.assertTrue(mr)
            self.harness.assertEqual(mr.msgType, msgType)
            self.harness.assertEqual(mr.msgBody, msgBody)
            self.harness.assertFalse(mr.alwaysDrop)
            self.msgMap.remove_instance(id_, mr)
        except Exception as e:
            logger.exception("error attempting to parse received message")
            raise e


    # private
    def finalSendMessageValidation(self):
        """
        Verify that the count of messages that still remain in our map
        is exactly equal to the number of messages dropped by the transport -
        i.e., they were never received.
        """
        stats = self.transport.getStats()
        randomDrops = stats.randomdrops
        forceDrops = stats.forcedrops
        mapSize = len(self.msgMap)
        msg = "Final verification ForceDrops: %d RandomDrops: %d MISSING: %d"
        logger.info(msg, forceDrops, randomDrops, (mapSize - randomDrops))

        if randomDrops != mapSize:
            # We will fail this test later, but do some logging here...
            logger.info("Drop queue size: %d", len(self.droppedMsgs))

            def logmr(id_, mr):
                logger.info("missing mr id: %s  drop: %d", id_, mr.alwaysDrop)

            self.msgMap.process_all(logmr)

        self.harness.assertEqual(randomDrops, mapSize)


    def cleanup(self):
        """Cleanup our queues and maps so we can do another test"""
        self.harness.assertFalse(self.invoker.exceptions) # no exceptions
        self.msgMap.clear()
        self.droppedMsgs.clear()
        self.ch.stop_receiving_messages()

        # TODO: enable
        #self.cmdMap.clear()
        #self.cmdCliSubmitQueue.clear()
        #self.cmdSvrComputeQueue.clear()
        #self.droppedCommands.clear()
        #self.ch.stop_receiving_commands()

    def close(self):
        """Close the stress tester"""
        self.cleanup()
        self.scheduler.cancel_all()
        self.scheduler.stop(block=True)
        self.executor.shutdown(wait=True)


def new_message_record(id_, alwaysdrop):
    """Creates and returns a new test message record"""

    def rand32():
        return random.randint(0, 1<<32)

    def random_body():
        """First line in message body is the internal 'id'. Remainder is random
        junk."""
        extra = random.randint(1, 9)
        sequence = (hex(rand32()) for _ in range(extra))
        return hex(id_) + '\n' + '\n'.join(sequence)

    def random_type():
        x = hex(rand32())
        return x[:random.randint(0, len(x))] # can be the empty string

    body = MockTransport.ALWAYSDROP_TEXT if alwaysdrop else random_body()
    return TestMessageRecord(id=id_, alwaysDrop=alwaysdrop,
                             msgType=random_type(), msgBody=body)

class TestRobotComm(unittest.TestCase):
    """Container for RobotComm unit tests"""

    def test_message_basic(self):
        """Sends and receives a single message"""
        transport = MockTransport("localhost")
        rcomm = rc.RobotComm(transport)
        channel = rcomm.new_channel("testChannel")
        remotenode = transport.new_remotenode("localhost")
        channel.bind_to_remote_node(remotenode)
        try:
            rcomm.start_listening()

            testMessage = "test message"
            channel.start_receiving_messages()
            channel.send_message("MYTYPE", testMessage)

            rm = None
            while not rm:
                rm = channel.poll_received_message()
                time.sleep(0.01)

            for stats in rcomm.get_channel_statistics():
                print(stats)

            channel.stop_receiving_messages()
        finally:
            rcomm.stop_listening()
            rcomm.close()
        self.assertTrue(rm)
        self.assertEqual(testMessage, rm.message)

    def test_xyz_stress_send_and_receive_messages_trivial(self):
        """Sends a single message with no failures or delays; single thread"""
        nThreads = 1
        nMessages = 1
        messageRate = 10000
        dropRate = 0
        transportFailureRate = 0
        maxTransportDelay = 0 # seconds
        transport = MockTransport("localhost")
        stresser = StressTester(self, nThreads, transport)
        stresser.open()
        try:
            transport.setTransportCharacteristics(transportFailureRate,
                                                  maxTransportDelay)
            stresser.submitMessages(nMessages, messageRate, dropRate)
        finally:
            stresser.close()
            transport.close()

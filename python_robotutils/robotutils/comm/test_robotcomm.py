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
from .. import logging_helper
from .common import DatagramTransport

_LOGNAME = "test"
_LOGGER = logging.getLogger(_LOGNAME)
_TRACE = logging_helper.LevelSpecificLogger(logging_helper.TRACELEVEL, _LOGGER)

# TODO: track down and fix all the TODOs
# pylint: disable=fixme

#_LOGLEVEL = logging_helper.TRACELEVEL
_LOGLEVEL = logging.ERROR

logging.basicConfig(level=_LOGLEVEL)


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



TransportStats = collections.namedtuple('TransportStats',
                                        'sends recvs forcedrops randomdrops')


# Mock transport tracing
_MTTRACE = logging_helper.LevelSpecificLogger(logging_helper.TRACELEVEL, _LOGNAME+'.transport')

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
            _MTTRACE("SEND_FORCEDROP\n[%s]", msg)
            self._transport.numforcedrops.next()
            return # **************** EARLY RETURN *****

        if self._nonforce_drop():
            _MTTRACE("SEND_RANDDROP\n[%s]", msg)
            self._transport.numrandomdrops.next()
            return # **************** EARLY RETURN *****

        _MTTRACE("SEND_SENDING\n[%s]", msg)
        self._transport.handle_send(msg)


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
        if self._transport.zero_failures():
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
        _MTTRACE.pause() # transport tracing paused by default

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


    def set_transport_characteristics(self, failurerate, maxdelay) -> None:
        """Changes transport characteristics: sends datagrams with {failurerate} failure
        rate, and {maxdelay} seconds max delay per packet. {maxdelay} value less than
        0.001 is considered to be 0, i.e., no delay.
        """
        assert 0 <= failurerate <= 1
        assert maxdelay >= 0
        self.failurerate = failurerate
        self.maxdelay = 0 if maxdelay < 0.001 else maxdelay # note 0 is an integer

    def healthy(self) -> bool:
        """Returns if the underlying transport is healthy, i.e., can still send/recv messages."""
        return self.scheduler.healthy()

    def zero_failures(self) -> bool:
        """{failurerate} "close enough" to 0 is considered to be zero-failures."""
        return abs(self.failurerate) < 1e-7

    def no_delays(self) -> bool:
        """Returns True IFF the transport should never delay"""
        return self.maxdelay == 0 # maxdelay was inited int(0) if 'close to 0'


    def get_stats(self) -> TransportStats:
        """Return transport statistics"""
        return TransportStats(
            sends=self.numsends.value(),
            recvs=self.numrecvs.value(),
            forcedrops=self.numforcedrops.value(),
            randomdrops=self.numrandomdrops.value())

    def get_maxdelay(self) -> float:
        """Return the max statistical delay between packets"""
        return self.maxdelay

    def get_failure_rate(self) -> float:
        """Return the statistical packet failure rate"""
        return self.failurerate

    def handle_send(self, msg) -> None:
        """Internally handle a send request"""
        if self.closed:
            self.numforcedrops.next()
            return
        if self.no_delays():
            self._receive_data(msg)
        else:
            delay = random.random() * self.maxdelay
            def timertask():
                if not self.closed:
                    # Delayed send
                    self._receive_data(msg)
                else:
                    self.numforcedrops.next()

            self.scheduler.schedule(delay, timertask)

    #
    # Private methods
    #

    def _receive_data(self, txt) -> None:
        """Internally handle a received message"""

        if self.client_recv:
            self.numrecvs.next()
            _MTTRACE("RECV:\n[%s]\n", txt)
            self.client_recv(txt, self.loopbacknode)
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
        transport.set_transport_characteristics(failurerate, maxdelay)
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
        transport.set_transport_characteristics(failurerate, maxdelay)

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
TestMessageRecord = collections.namedtuple('TestMessageRecord', 'id alwaysdrop msgtype msgbody')


# Keeps track of a command and expected response
TestCommandRecord = collections.namedtuple('TestCommandRecord', 'cmdrecord resprecord rt timeout')


class StressTester: # pylint: disable=too-many-instance-attributes
    """Test engine that instantiates and runs robotcomm stress tests with
    various parameters"""

    def __init__(self, harness, nthreads, transport):
        """Initializes the stress tester"""
        self.nthreads = nthreads
        self.transport = transport
        self.harness = harness

        # Protected
        self.rand = random.Random()
        self.nextid = conc.AtomicNumber(1)
        self.scheduler = conc.EventScheduler()
        self.scheduler.start()
        self.executor = concurrent.futures.ThreadPoolExecutor(nthreads)
        self.invoker = conc.ConcurrentInvoker(self.executor, _LOGGER)
        self.chan = None # set in open

        self.msgmap = conc.ConcurrentDict()
        self.droppedmsgs = conc.ConcurrentDeque()
        # TODO: implement
        # self.cmdMap = conc.ConcurrentDict()
        # self.cmdCliSubmitQueue = conc.ConcurrentDeque()
        # self.cmdSvrComputeQueue = conc.ConcurrentDeque()
        # self.droppedCommands = conc.ConcurrentDeque()
        # self.droppedResponses = conc.ConcurrentDeque()
        self.rcomm = rc.RobotComm(transport)


    def open(self):
        """Starts listening, etc."""
        remotenode = self.transport.new_remotenode("localhost")
        self.chan = self.rcomm.new_channel("testChannel")
        self.chan.bind_to_remote_node(remotenode) # TODO: change to set_destination
        self.rcomm.start_listening()

    DROP_TRIGGER = 1000

    # private
    def prune_dropped_messages(self) -> None:
        """Keep count of dropped messages by removing the older
        half of them if their number grows larger than DROP_TRIGGER"""
        # Remember that the message map and queue of dropped messages are
        # concurrently modified so sizes are estimates.
        sizeest = len(self.droppedmsgs) # warning: O(n) operation
        if sizeest > self.DROP_TRIGGER:
            dropcount = sizeest // 2
            _TRACE("PURGE Purging about %s force-dropped messages",
                   dropcount)
            for mrec in getsome(self.droppedmsgs.pop, dropcount):
                self.msgmap.remove_instance(mrec.id, mrec) # O(log(n))

    # private
    def purce_all_dropped_messages(self) -> None:
        """Remove all dropped messages (at least the ones that were in the
        queue when we entered this method)."""
        for mrec in getsome(self.droppedmsgs.pop):
            self.msgmap.remove_instance(mrec.id, mrec) # O(log(n))

    # private
    def batch_submit_messages(self, nmessages, submission_timespan,
                              alwaysdrop_rate) -> None:
        # pylint: disable=too-many-locals
        """sent a batch of messages over {submissionTimespan} seconds"""
        assert self.rcomm
        assert self.chan
        assert alwaysdrop_rate <= 1
        assert submission_timespan >= 0

        _LOGGER.info("SUBMIT Beginning to submit %d messages", nmessages)


        def delayed_send(msgtype, msgbody):
            def really_send():
                self.invoker.invoke(self.chan.send_message, msgtype, msgbody)
            delay = self.rand.random() * submission_timespan # seconds
            _TRACE("SCHEDULING_SEND delay: %f", delay)
            self.scheduler.schedule(delay, really_send)

        # Send messages at random times
        for _ in range(nmessages):
            id_ = self.nextid.next()
            alwaysdrop = self.rand.random() < alwaysdrop_rate
            mrec = new_message_record(id_, alwaysdrop)
            self.msgmap.set(id_, mrec)
            if alwaysdrop:
                self.droppedmsgs.appendleft(mrec)
            delayed_send(mrec.msgtype, mrec.msgbody)

        def receive_all():
            # Pick up all messages received so far and verify them...
            def process():
                for rmsg in getsome(self.chan.poll_received_message):
                    self.process_received_message(rmsg)
            self.invoker.invoke(process)

        # Poll for received messages at random times
        recv_attempts = max(nmessages // 100, 10)
        max_receivedelay = submission_timespan + self.transport.get_maxdelay()
        for i in range(recv_attempts):
            delay = self.rand.random() * submission_timespan
            # Special case of i == 0 - we schedule our final receive attempt to
            # be AFTER the last packet is actually sent by the transport (after
            # a potential delay)
            extra_delay = 1 # TODO: need to compute this more carefully
            if i == 0:
                delay = max_receivedelay + extra_delay
            self.scheduler.schedule(delay, receive_all)


    BATCH_SPAN_SECONDS = 1.0
    MAX_EXPECTED_TRANSPORT_FAILURES = 1000000


    def submit_messages(self, nmessages, submission_rate, alwaysdrop_rate):
        """
        Stress test sending, receiving and processing of commands.
            nmessages - number of messages to submit
            submission_rate - rate of submission per second
            alwaysdrop_rate - rate at which datagrams are always dropped by
                             the transport
        """
        _LOGGER.info("Submitting %d messages. rate: %d: alwaysDropRate: %d",
                     nmessages, submission_rate, alwaysdrop_rate)

        # If more than LARGE_COUNT messages, there should be NO transport send
        # failures else our tracked messages will start to accumulate and take
        # up too much memory.
        expected_transport_failures = nmessages * self.transport.get_failure_rate()
        if expected_transport_failures > self.MAX_EXPECTED_TRANSPORT_FAILURES:
            _LOGGER.error("Too many transport failures expected %d",
                          int(expected_transport_failures))
            _LOGGER.error("Abandoning test.")
            self.harness.fail("Too much expected memory consumption to run this test.")

        self.chan.start_receiving_messages()

        messagesleft = nmessages
        while messagesleft >= submission_rate:
            time0 = time.time() # UTC, seconds
            self.batch_submit_messages(submission_rate, self.BATCH_SPAN_SECONDS,
                                       alwaysdrop_rate)
            messagesleft -= submission_rate
            time1 = time.time()
            sleeptime = max(self.BATCH_SPAN_SECONDS * 0.1,
                            self.BATCH_SPAN_SECONDS - (time1 - time0))
            time.sleep(sleeptime)
            self.prune_dropped_messages()

        timeleft_seconds = messagesleft / submission_rate
        self.batch_submit_messages(messagesleft, timeleft_seconds, alwaysdrop_rate)
        time.sleep(timeleft_seconds)

        # Having initiated the process of sending all the messages, we now
        # have to wait for ??? and verify that exactly those messages that
        # are expected to be received are received correctly.
        max_receivedelay = self.transport.get_maxdelay()
        _LOGGER.info("WAITING Waiting to receive up to %d messages", nmessages)
        time.sleep(max_receivedelay)
        for _ in range(120): # retry for 1 minute
            stats = self.transport.get_stats()
            if len(self.msgmap) <= stats.randomdrops:
                _LOGGER.info("Breaking out. msgmap: %d", len(self.msgmap))
                break
            time.sleep(0.5)
            self.purce_all_dropped_messages()

        # Now we do final validation.
        self.final_send_message_validation()

        self.cleanup()


    # private
    def process_received_message(self, rmsg):
        """
        Extract id from message.
        Look it up - we had better find it in our map.
        Verify that we expect to get it - (not alwaysdrop)
        Verify we have not already received it.
        Verify message type and message body is what we expect.
        If all ok, remove this from the hash map.
        """
        msgtype = rmsg.msgtype
        msgbody = rmsg.message
        try:
            try:
                nli = msgbody.index('\n')
                strid = msgbody[:nli]
            except IndexError:
                strid = msgbody
            id_ = int(strid, 16)
            _TRACE("RECVMSG Received message with id %d", id_)
            mrec = self.msgmap.get(id_)
            self.harness.assertTrue(mrec)
            # Need to convert empty strings to None for 2 comparisions below
            self.harness.assertEqual(mrec.msgtype or None, msgtype)
            self.harness.assertEqual(mrec.msgbody or None, msgbody)
            self.harness.assertFalse(mrec.alwaysdrop)
            self.msgmap.remove_instance(id_, mrec)
        except Exception as exp:
            _LOGGER.exception("error attempting to parse received message")
            raise exp


    # private
    def final_send_message_validation(self):
        """
        Verify that the count of messages that still remain in our map
        is exactly equal to the number of messages dropped by the transport -
        i.e., they were never received.
        """
        stats = self.transport.get_stats()
        randomdrops = stats.randomdrops
        forcedrops = stats.forcedrops
        mapsize = len(self.msgmap)
        msg = "Final verification ForceDrops: %d RandomDrops: %d MISSING: %d"
        _LOGGER.info(msg, forcedrops, randomdrops, (mapsize - randomdrops))

        if randomdrops != mapsize:
            # We will fail this test later, but do some logging here...
            _LOGGER.info("Drop queue size: %d", len(self.droppedmsgs))

            if _TRACE.enabled():
                def logmr(id_, mrec):
                    _TRACE("missing mr id: %s  drop: %d", id_, mrec.alwaysdrop)
                self.msgmap.process_all(logmr)

        self.harness.assertEqual(randomdrops, mapsize)


    def cleanup(self):
        """Cleanup our queues and maps so we can do another test"""
        self.msgmap.clear()
        self.droppedmsgs.clear()
        self.chan.stop_receiving_messages()

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
    return TestMessageRecord(id=id_, alwaysdrop=alwaysdrop,
                             msgtype=random_type(), msgbody=body)

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

            testmessage = "test message"
            channel.start_receiving_messages()
            channel.send_message("MYTYPE", testmessage)

            rmsg = None
            while not rmsg:
                rmsg = channel.poll_received_message()
                time.sleep(0.01)

            for stats in rcomm.get_channel_statistics():
                print(stats)

            channel.stop_receiving_messages()
        finally:
            rcomm.stop_listening()
            rcomm.close()
        self.assertTrue(rmsg)
        self.assertEqual(testmessage, rmsg.message)

    # @unittest.skip('temporary')
    def test_stress_send_and_receive_messages_trivial(self):
        """Sends a single message with no failures or delays; single thread"""
        nthreads = 1
        nmessages = 1
        message_rate = 1000
        drop_rate = 0
        transport_failure_rate = 0
        max_transport_delay = 0 # seconds
        transport = MockTransport("localhost")
        stresser = StressTester(self, nthreads, transport)
        stresser.open()
        transport.set_transport_characteristics(transport_failure_rate,
                                                max_transport_delay)
        stresser.submit_messages(nmessages, message_rate, drop_rate)
        self.assertFalse(stresser.invoker.exceptions) # no exceptions
        stresser.close()
        transport.close()


    def test_stress_send_and_receive_messages_short(self):
        """Sends 1000 messages with delays and drops; 10 threads"""
        nthreads = 10
        nmessages = 1000
        message_rate = 5000 # About the max on a decent laptop
        drop_rate = 0.1
        transport_failure_rate = 0.1
        max_transport_delay = 0.25 # seconds
        transport = MockTransport("localhost")
        stresser = StressTester(self, nthreads, transport)
        stresser.open()
        transport.set_transport_characteristics(transport_failure_rate,
                                                max_transport_delay)
        stresser.submit_messages(nmessages, message_rate, drop_rate)
        self.assertFalse(stresser.invoker.exceptions) # no exceptions
        stresser.close()
        transport.close()


    def test_stress_send_and_receive_messages_medium(self):
        """Sends 10000 messages with delays and drops; 10 threads"""
        nthreads = 10
        nmessages = 10000
        message_rate = 5000 # About the max on a decent laptop
        drop_rate = 0.1
        transport_failure_rate = 0.1
        max_transport_delay = 1.5 # seconds
        transport = MockTransport("localhost")
        stresser = StressTester(self, nthreads, transport)
        stresser.open()
        transport.set_transport_characteristics(transport_failure_rate,
                                                max_transport_delay)
        stresser.submit_messages(nmessages, message_rate, drop_rate)
        self.assertFalse(stresser.invoker.exceptions) # no exceptions
        stresser.close()
        transport.close()

    def test_stress_send_and_receive_messages_xyz(self):
        """Working send-and-receive messages"""
        nthreads = 1
        nmessages = 1 # Working: 100000
        message_rate = 10000000
        drop_rate = 0.1
        transport_failure_rate = 0.1
        max_transport_delay = 1 # 0.1 # 1 fails at 40K # seconds
        transport = MockTransport("localhost")
        stresser = StressTester(self, nthreads, transport)
        stresser.open()
        transport.set_transport_characteristics(transport_failure_rate,
                                                max_transport_delay)
        stresser.submit_messages(nmessages, message_rate, drop_rate)
        self.assertFalse(stresser.invoker.exceptions) # no exceptions
        stresser.close()
        transport.close()

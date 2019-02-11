'''
Unit tests for the robotutils.comm_helper module.
Ported from Java CommUtilsTest
Author: JMJ
'''
import time
import logging
import unittest
import concurrent.futures

#Pylint complains about import order of .context, but if it is put later,
#unittest (with -k) can't find robotutils, because it hasn't loaded .context
#when discovering other tests
#from .context import robotutils

from robotutils.comm_helper import UdpTransport, EchoServer, EchoClient
from robotutils import concurrent_helper as conc

from .context import logging_helper # also to ensure .context gets loaded

_LOGNAME = "test"
_LOGGER = logging.getLogger(_LOGNAME)
_TRACE = logging_helper.LevelSpecificLogger(logging_helper.TRACELEVEL, _LOGGER)

# Uncomment one of these to set the global trace level for ALL unit tests, not
# just the ones in this file.
# logging.basicConfig(level=logging.INFO)
#logging.basicConfig(level=logging_helper.TRACELEVEL)

SERVER_IP_ADDRESS = "127.0.0.1"
SERVER_PORT = 41899 + 3
MAX_PACKET_SIZE = 1024

class CommUtilsTest(unittest.TestCase):
    """Unit tests for comm_helper"""

    # Echo client-server tests use these channel names.
    ECHO_CHANNEL_A = "A"
    MSG_PREFIX = "MSG"

    def test_udp_transport_simple(self):
        """Simple test of the UDP transport"""
        # The client sends string versions of integers 1 to N
        # The server parses and adds these up and in the end
        # the test verifies that the sum is as expected.
        client = UdpTransport(recv_bufsize=MAX_PACKET_SIZE)
        server = UdpTransport(recv_bufsize=MAX_PACKET_SIZE,
                              local_host=SERVER_IP_ADDRESS,
                              local_port=SERVER_PORT)
        count = 1000
        total = conc.AtomicNumber(0)
        latch = conc.CountDownLatch(count)

        def process_message(msg, node):
            _TRACE("Server got message [{}] from {}".format(msg, node))
            val = int(msg[len(self.MSG_PREFIX):]) # skip past prefix
            total.add(val)
            latch.count_down()

        server.start_listening(process_message)

        remote = client.new_remote_node(SERVER_IP_ADDRESS, SERVER_PORT)
        for i in range(1, count+1):
            msg = self.MSG_PREFIX + str(i)
            client.send(msg, remote)
            _TRACE("Client sent message [{}]".format(msg))
            # time.sleep(0.1)

        _LOGGER.info("Waiting for all messages to arrive...")
        if latch.wait(1):
            _LOGGER.info("Done waiting for all %d messages to arrive.", count)
            expected = count * (count + 1) // 2 # sum of 1 to count
            self.assertEqual(total.value(), expected)
        else:
            msg = "TIMED OUT waiting for all {} messages to arrive.".format(count)
            self.fail(msg)


        client.close()
        server.close()

        self.assertEqual(client._send_errors, 0) # pylint: disable=protected-access
        self.assertEqual(server._send_errors, 0) # pylint: disable=protected-access


    def test_only_echo_client(self):
        """Test the UDP echo client sending to nowhere"""
        client = EchoClient('localhost')
        num_sends = 4
        # send_messages will block until done...
        _TRACE("GOING TO SEND MESSAGES")
        client.send_messages(num_sends)
        _TRACE("DONE SENDING MESSAGES")
        client.close()
        self.assertTrue(bool(client)) # replace with some better check


    def test_only_echo_server(self):
        """Test the UDP echo server waiting for a client never shows up"""
        server = EchoServer('localhost')
        stop_server = False

        with concurrent.futures.ThreadPoolExecutor(1) as executor:
            def runserver():
                server.start()
                while not stop_server:
                    server.periodic_work()
                    time.sleep(0.1)
                server.stop()

            executor.submit(runserver)

            time.sleep(1)
            stop_server = True
            _TRACE("Waiting for server to shut down")
        self.assertTrue(stop_server) # replace with some better check


    def test_echo_nomsgs(self):
        """Test the UDP echo client and server with 0 messages"""
        self.run_echo_test(num_sends=0)

    def test_echo_simple(self):
        """Test the UDP echo client and server with a small number of messages"""
        self.run_echo_test(num_sends=1)

    def test_echo_stress(self):
        """Test the UDP echo client and server with a small number of messages"""
        self.run_echo_test(num_sends=1000, rate=5000)

    def run_echo_test(self, *, num_sends, rate=1):
        """Parametrized echo client server test"""

        server = EchoServer('localhost')
        client = EchoClient('localhost')
        client.set_parameters(rate=rate)
        stop_server = False
        receive_count = 0
        server.start()

        with concurrent.futures.ThreadPoolExecutor(1) as executor:
            def runserver():
                while not stop_server:
                    server.periodic_work()
                    time.sleep(0.1)

            executor.submit(runserver)
            time.sleep(0.1) # Give some time for server to get started

            def response_handler(resptype, respbody):
                _TRACE("GOT RESPONSE (%s, %s)", resptype, respbody)
                nonlocal receive_count
                receive_count += 1 # assume call to hander is serialized

            # send_messages will block until done...
            try:
                _TRACE("GOING TO SEND MESSAGES")
                client.send_messages(num_sends, response_handler=response_handler)
                _TRACE("DONE SENDING MESSAGES")
            except Exception: # pylint: disable=broad-except
                _LOGGER.exception("While sending messages")
                self.fail("Exception thrown")
            finally:
                client.close()
                server.stop()
                stop_server = True
            _TRACE("Waiting for server to shut down")

        _LOGGER.info("Echo test complete. Sent: %d  Received: %d", num_sends, receive_count)
        if num_sends > 2: # We may lose the 1st or last message based on timing
            self.assertGreater(receive_count, 0) # Should receive at least 1 message"

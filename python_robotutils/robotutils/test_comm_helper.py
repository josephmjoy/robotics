'''
Unit tests for the robotutils.comm_helper module.
Ported from Java CommUtilsTest
Author: JMJ
'''

import unittest
import logging

from . import concurrent_helper as conc
from . import logging_helper
from .comm_helper import UdpTransport

_LOGNAME = "test"
_LOGGER = logging.getLogger(_LOGNAME)
_TRACE = logging_helper.LevelSpecificLogger(logging_helper.TRACELEVEL, _LOGGER)
_LOGLEVEL = logging_helper.TRACELEVEL
#_LOGLEVEL = logging.INFO

logging.basicConfig(level=_LOGLEVEL)

SERVER_IP_ADDRESS = "127.0.0.1"
SERVER_PORT = 41899 + 3
MAX_PACKET_SIZE = 1024

class CommUtilsTest(unittest.TestCase):
    """Unit tests for comm_helper"""

    # Echo client-server tests use these channel names.
    ECHO_CHANNEL_A = "A"

    def test_udp_transport_simple(self):
        """Simple test of the UDP transport"""
        client = UdpTransport(MAX_PACKET_SIZE)
        server = UdpTransport(MAX_PACKET_SIZE,
                              local_host=SERVER_IP_ADDRESS,
                              local_port=SERVER_PORT)
        count = 100
        latch = conc.CountDownLatch(count)

        def process_message(msg, node):
            _TRACE("Server got message [{}] from {}".format(msg, node))
            latch.count_down()

        server.start_listening(process_message)

        remote = client.new_remote_node(SERVER_IP_ADDRESS, SERVER_PORT)
        for i in range(count):
            msg = "Test message #" + str(i)
            client.send(msg, remote)
            _TRACE("Client sent message [{}]".format(msg))
            # time.sleep(0.1)

        print("Waiting for all messages to arrive...")
        if latch.wait(1):
            print("Done waiting for all {} messages to arrive.".format(count))
        else:
            print("TIMED OUT waiting for all {} messages to arrive.".format(count))

        client.close()
        server.close()

        self.assertEqual(client._send_errors, 0) # pylint: disable=protected-access
        self.assertEqual(server._send_errors, 0) # pylint: disable=protected-access

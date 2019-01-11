'''
Unit tests for the robotutils.robotcomm module.

This is a port of the JUnit test for the corresponding Java
robotutils class, ConfigurationHelper
Author: JMJ
'''

import unittest

from . import robotcomm as rc
from . import atomic


#TODO see if we can use some unittest feature to replace these
#_assertion_failure = ''
#_assertion_failure_string = ''


class FakeRemoteNode(rc.DatagramTransport.RemoteNode):
    """Test remote node implementation"""
    def send(self, msg) -> None:
        """Sends a test message"""
        print("Sending message[{}]", msg)

    def address(self) -> str:
        """"Text representation of destination address"""
        return "ADDRESS"


class TestRobotComm(unittest.TestCase):
    """Container for robotcomm unit tests"""

    def test_stuff(self):
        """Test stuff"""

'''
Unit tests for the robotutils.strmap_helper module.

This is a port of the JUnit test for the corresponding Java
robotutils class, StringmapHelper
Author: JMJ
'''

import unittest
import re

from . import strmap_helper

_REGEX_VALIDNAME = re.compile(r"\w+")
_STRING_DEFAULT = 'WOAH!!!'

_INT_MIN = -10
_INT_MAX = 10
_INT_DEFAULT = _INT_MAX * 10

_FLOAT_MIN = -1.0
_FLOAT_MAX = 1.0
_FLOAT_DEFAULT = _FLOAT_MAX * 10

class TestStringmapHelper(unittest.TestCase):
    """Container class for unittest tests."""


    def test_empty_map(self):
        """Checks against empty map - should return defaults"""
        empty = strmap_helper.StringDictHelper(dict())

        self.assertEqual("bar", empty.get_as_str("foo", "bar"))
        self.assertEqual(True, empty.get_as_bool("foo", True))

        # Same deal, with the more complex versions...
        self.assertEqual("bar", empty.get_as_str("foo", "bar", _REGEX_VALIDNAME))

'''        Map<String, String> emptyMap = new HashMap<String, String>();
        StringmapHelper empthSmh = new StringmapHelper(emptyMap);

        // Checks against empty map - should return defaults
        assertEquals("bar", empthSmh.getAsString("foo", "bar"));
        assertEquals(true, empthSmh.getAsBoolean("foo", true));
        assertEquals(1, empthSmh.getAsInt("foo", 1));
        assertEquals(1, empthSmh.getAsLong("foo", 1), 1);
        double dd = 1.0; // double equality check is finicky
        assertEquals(dd, empthSmh.getAsDouble("foo", dd));

        // Same deal, with the more complex versions...
        assertEquals(1, empthSmh.getAsInt("foo", 0, 100, 1));
        assertEquals(1, empthSmh.getAsLong("foo", 0, 100, 1), 1);
        assertEquals(dd, empthSmh.getAsDouble("foo", -1.0, 1.0, dd));
'''

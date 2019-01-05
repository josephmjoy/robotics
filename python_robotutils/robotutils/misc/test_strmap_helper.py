'''
Unit tests for the robotutils.strmap_helper module.

This is a port of the JUnit test for the corresponding Java
robotutils class, StringmapHelper
Author: JMJ
'''

import unittest
import re
import random

#import strmap_helper
from . import strmap_helper

_REGEX_VALIDNAME = re.compile(r"\w+")
_STRING_DEFAULT = 'WOAH!!!'
_BOOL_DEFAULT = True

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
        self.assertEqual(42, empty.get_as_num("foo", 42))
        self.assertAlmostEqual(3.14, empty.get_as_num("foo", 3.14))

        # Same deal, with the more complex versions...
        self.assertEqual("bar", empty.get_as_str("foo", "bar", _REGEX_VALIDNAME))
        self.assertEqual(42, empty.get_as_num("foo", 42, 0, 100))
        self.assertAlmostEqual(3.14, empty.get_as_num("foo", 3.14, 0.0, 100.0))



    @staticmethod
    def buildRandomInput(count):
        """Construct and return {count} random test inputs in an iterable"""
        randomInput = [] # contains (k, v) tuples
        for i in range(count):
            key = "key" + str(i);
            k = random.randint(0, 3)
            if k == 0:
                # String. The following can't have any embedded digits, so substrings can never
                # be valid ints, longs or doubles. Also the two can't have overlapping
                # character sets,
                # and only goodS must pass the string validity test.
                goodS = "jfAGsrhGGhAhafHaahwHghqgQghqh"
                badS = "!@#$%^&*)&^#**%&%*@(*&*&*&*&!#%^*"
                s = goodS if random.randint(0,1) else badS
                assert _REGEX_VALIDNAME.fullmatch(goodS)
                assert not _REGEX_VALIDNAME.match(badS)
                rs = s[:random.randint(0, len(s))]
                kvi = (key, rs);
                randomInput.append(kvi)
            elif k == 1:
                # int
                ri = int(2 * (_INT_MIN + random.random() * (_INT_MAX - _INT_MIN)))
                kvi = (key, ri)
                randomInput.append(kvi)
            elif k == 2:
                # float
                df = 2 * (_FLOAT_MIN + random.random() * (_FLOAT_MAX - _FLOAT_MIN))
                kvi = (key, df)
                randomInput.append(kvi)
            else:
                # bool
                rb = True if random.randint(0,1) else False
                kvi = (key, rb)
                randomInput.append(kvi)
        return randomInput

    @staticmethod
    def buildRandomDict(kv_infos):
        """Build a random dictionary of (k,v) pairs"""
        return {k: str(v) for k, v in kv_infos}

    def run_gauntlet(self, smg, kvi): 
        """Run tests using all the (key, value) pairs in kv_infos"""
        key, expected_val = kvi

        # Test get_as_str...
        got_val = smg.get_as_str(key, _STRING_DEFAULT, _REGEX_VALIDNAME)
        isValid = _REGEX_VALIDNAME.fullmatch(str(expected_val))
        if isValid:
            self.assertEqual(got_val, str(expected_val))
        else:
            self.assertEqual(got_val, _STRING_DEFAULT)

        # Test get_as_bool...
        got_val = smg.get_as_bool(key, _BOOL_DEFAULT)
        isValid = isinstance(expected_val, bool)
        if isValid:
            self.assertEqual(got_val, expected_val)
        else:
            self.assertEqual(got_val, _BOOL_DEFAULT)

        # TODO
        # Test get_as_num: int without min, max
        # Test get_as_num: int with min, max
        # Test get_as_num: float without min, max
        # Test get_as_num: float with min, max


    def test_bigTest(self):
        """Create many (k,v) pairs and run the gauntlet test on them."""
        kv_infos =  self.buildRandomInput(20)
        smap =   self.buildRandomDict(kv_infos)
        smh = strmap_helper.StringDictHelper(smap)
        for kv in kv_infos:
            self.run_gauntlet(smh, kv)

if __name__ == '__main__':
    L = TestStringmapHelper.buildRandomInput(5)
    print(L)
    D = TestStringmapHelper.buildRandomDict(L)
    print(D)

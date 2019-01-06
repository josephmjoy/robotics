'''
Unit tests for the robotutils.strmap_helper module.

This is a port of the JUnit test for the corresponding Java
robotutils class, StringmapHelper
Author: JMJ
'''

import unittest
import re
import random

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
    def build_random_input(count):
        """Construct and return {count} random test inputs in an iterable"""
        inputs = [] # contains (k, v) tuples
        for i in range(count):
            key = "key" + str(i)
            k = random.randint(0, 3)
            if k == 0:
                # String. The following can't have any embedded digits, so substrings can never
                # be valid ints, longs or doubles. Also the two can't have overlapping
                # character sets,
                # and only good must pass the string validity test.
                good = "jfAGsrhGGhAhafHaahwHghqgQghqh"
                bad = "!@#$%^&*)&^#**%&%*@(*&*&*&*&!#%^*"
                s = good if random.randint(0, 1) else bad
                assert _REGEX_VALIDNAME.fullmatch(good)
                assert not _REGEX_VALIDNAME.match(bad)
                rs = s[:random.randint(0, len(s))]
                kvi = (key, rs)
                inputs.append(kvi)
            elif k == 1:
                # int
                ri = int(2 * (_INT_MIN + random.random() * (_INT_MAX - _INT_MIN)))
                kvi = (key, ri)
                inputs.append(kvi)
            elif k == 2:
                # float
                df = 2 * (_FLOAT_MIN + random.random() * (_FLOAT_MAX - _FLOAT_MIN))
                kvi = (key, df)
                inputs.append(kvi)
            else:
                # bool
                rb = bool(random.randint(0, 1))
                kvi = (key, rb)
                inputs.append(kvi)
        return inputs

    @staticmethod
    def build_random_dict(kv_infos):
        """Build a random dictionary of (k,v) pairs"""
        return {k: str(v) for k, v in kv_infos}

    def run_gauntlet(self, smg, kvi):
        """Run tests using all the (key, value) pairs in kv_infos"""
        key, expected_val = kvi

        # Test get_as_str without REGEX...
        got_val = smg.get_as_str(key, _STRING_DEFAULT)
        self.assertEqual(got_val, str(expected_val))

        # Test get_as_str with REGEX...
        got_val = smg.get_as_str(key, _STRING_DEFAULT, _REGEX_VALIDNAME)
        valid = _REGEX_VALIDNAME.fullmatch(str(expected_val))
        if valid:
            self.assertEqual(got_val, str(expected_val))
        else:
            self.assertEqual(got_val, _STRING_DEFAULT)

        # Test get_as_bool...
        got_val = smg.get_as_bool(key, _BOOL_DEFAULT)
        valid = isinstance(expected_val, bool)
        if valid:
            self.assertEqual(got_val, expected_val)
        else:
            self.assertEqual(got_val, _BOOL_DEFAULT)

        # Test get_as_num: int without min, max
        valid_int = isinstance(expected_val, int) and not isinstance(expected_val, bool)
        got_val = smg.get_as_num(key, _INT_DEFAULT)
        if valid_int:
            self.assertEqual(got_val, expected_val)
        elif not isinstance(expected_val, float):
            self.assertEqual(got_val, _INT_DEFAULT)

        # Test get_as_num: int with min, max
        got_val = smg.get_as_num(key, _INT_DEFAULT, _INT_MIN, _INT_MAX)
        valid = valid_int and expected_val >= _INT_MIN and expected_val <= _INT_MAX
        if valid:
            self.assertEqual(got_val, expected_val)
        elif not isinstance(expected_val, float):
            self.assertEqual(got_val, _INT_DEFAULT)

        # Test get_as_num: float without min, max
        got_val = smg.get_as_num(key, _FLOAT_DEFAULT)
        valid = isinstance(expected_val, float) or valid_int
        if valid:
            self.assertAlmostEqual(got_val, expected_val)
        else:
            self.assertAlmostEqual(got_val, _FLOAT_DEFAULT)

        # Test get_as_num: float with min, max
        got_val = smg.get_as_num(key, _FLOAT_DEFAULT, _FLOAT_MIN, _FLOAT_MAX)
        validtype = isinstance(expected_val, float) or valid_int
        valid = validtype and expected_val >= _FLOAT_MIN and expected_val <= _FLOAT_MAX
        if valid:
            self.assertAlmostEqual(got_val, expected_val)
        else:
            self.assertAlmostEqual(got_val, _FLOAT_DEFAULT)


    def test_complex_cases(self):
        """Create many (k,v) pairs and run the gauntlet test on them."""
        kv_infos = self.build_random_input(20)
        smap = self.build_random_dict(kv_infos)
        smh = strmap_helper.StringDictHelper(smap)
        for kv in kv_infos:
            self.run_gauntlet(smh, kv)


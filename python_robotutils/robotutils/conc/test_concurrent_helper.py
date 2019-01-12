'''
Unit tests for the robotutils.concurrent_helper module.
Author: JMJ
'''

import unittest

from . import concurrent_helper as ch


class TestAtomicNumber(unittest.TestCase):
    """Container for AtomicNumber unit tests"""

    def test_sequential_use(self):
        """Single-threaded test"""
        count1 = ch.AtomicNumber() # default initial value is 0
        count2 = ch.AtomicNumber(-10)
        count3 = ch.AtomicNumber(3.14)
        counts = ((0, count1), (-10, count2), (3.14, count3))
        for initial, count in counts:
            for _ in range(10):
                count.next()
            self.assertAlmostEqual(count.next(), initial+11)
            self.assertEqual(repr(count), repr(initial+11))

'''
Unit tests for the robotutils.concurrent_helper module.
Author: JMJ
'''

import unittest
import concurrent.futures
import random

from . import concurrent_helper as ch


class TestAtomicNumber(unittest.TestCase):
    """Container for AtomicNumber tests"""

    def test_sequential(self):
        """Single-threaded test"""
        counter1 = ch.AtomicNumber() # default initial value is 0
        counter2 = ch.AtomicNumber(-10)
        counter3 = ch.AtomicNumber(3.14)
        counters = ((0, counter1), (-10, counter2), (3.14, counter3))
        for initial, counter in counters:
            for _ in range(10):
                counter.next()
            self.assertAlmostEqual(counter.next(), initial+11)
            self.assertEqual(repr(counter), repr(initial+11))

    def test_concurrent(self):
        """Multithreaded test"""
        counter = ch.AtomicNumber()
        n_submits = 4
        n_nexts_per_submit = 100000
        max_workers = 3

        total = 0
        with concurrent.futures.ThreadPoolExecutor(max_workers) as ex:
            sum_many = lambda n: sum(counter.next() for _ in range(n))
            # Sum up result of concurrently mapping multiple calls to sum_many
            total = sum(ex.map(sum_many, (n_nexts_per_submit for _ in range(n_submits))))
        n = n_submits * n_nexts_per_submit
        n_expected = n * (n + 1) // 2  # sum OF 1 TO n - integer division
        self.assertEqual(total, n_expected)


class TestConcurrentDeque(unittest.TestCase):
    """Container for ConcurrentDeque tests"""

    def test_sequential(self):
        """Single-threaded test"""

        cdq = ch.ConcurrentDeque()
        self.assertEqual(len(cdq), 0)

        #clear empty list
        cdq.clear()
        self.assertEqual(len(cdq), 0)


        #add and remove a single element using appendleft and pop
        cdq.appendleft(42)
        self.assertEqual(len(cdq), 1)
        self.assertEqual(cdq.pop(), 42)
        self.assertEqual(len(cdq), 0)

        #add and remove a single element using append and popleft
        cdq.append(42)
        self.assertEqual(len(cdq), 1)
        self.assertEqual(cdq.popleft(), 42)
        self.assertEqual(len(cdq), 0)


        #process empty list
        cdq.process_all(lambda: self.assertTrue(False)) #func should never get called

        #process nonempty list
        num = 10
        cdq = self._make_deque(num)
        total = 0
        def addone(x):
            nonlocal total
            total += x
        cdq.process_all(addone)
        self.assertEqual(total, num * (num + 1) // 2)

    def test_concurrent(self):
        """Multithreaded test"""
        # - Each thread appends elements from a unique increasing sequence to
        #   the left of the deque.
        # - Similarly, each thread appends elements
        #   from a unique *decreasing* sequence to the *right* of the deque.
        # - Every thread randomly removes elements on the left - and verifies that
        #   the removed element is greater than the previous element removed from that
        #   unique sequence.
        # - Similarly thread randomly removes elements on
        #   the *right* - and verifies that the removed element is greater
        #   than the previous element removed from that unique sequence.
        # - Each thread occasionally processes all the elements in the queue and
        #   verifies the snapshot invariant that all elements from a particular
        #   unique sequence are increasing left-to-right.
        # - Each thread very occasionally clears the deque by calling clear.
        seq_length = 1
        cdq = ch.ConcurrentDeque()
        quit_ = False

        def worker(name):
            """Thread function with unique name {name}"""

            prevleft = dict()
            prevright = dict()
            leftseq = ((name + '_L', i) for i in range(seq_length))
            rightseq = ((name + '_R', -i) for i in range(seq_length))

            def examine_deque_element(element):
                """Examine deque element from snapshot of deque"""
                seqname, value = element
                self.assertTrue(isinstance(seqname, str))
                self.assertTrue(isinstance(value, int))

                try:
                    while not quit_:
                        if random.random() < 0.5:
                            cdq.appendleft(leftseq.next())  # append left
                        if random.random() < 0.5:
                            cdq.append(rightseq.next())     # append right
                        if random.random() < 0.4:
                            (seqname, value) = cdq.pop()    # pop left
                            prev = prevright.get(seqname)
                            if prev:
                                self.assertGreater(prevright[seqname], value)
                            prevright[seqname] = value
                        if random.random() < 0.4:
                            (seqname, value) = cdq.popleft()  # pop right
                            prev = prevleft.get(seqname)
                            if prev:
                                self.assertTrue(prevleft[seqname] > value)
                            prevleft[seqname] = value
                        if random.random() < 0.1:
                            cdq.process_all(examine_deque_element) # process all
                        if random.random() < 0.01:
                            cdq.clear()                      # clear
                        if random.random() < 0.1:
                            len(cdq) # just exercise  len    # calculate length
                except StopIteration:
                    pass # we quit when any of our sequences end

    def _make_deque(self, n):
        """Makes a deque containing 1 to {n} in increasing order"""
        cdq = ch.ConcurrentDeque()
        for x in range(1, n+1):
            cdq.appendleft(x)
        self._verify_increasing_dequeue(cdq)
        return cdq

    def _verify_increasing_dequeue(self, cdq):
        """Verifies that deque contains only increasing values (if any)
        WARNING: we reach into the underlying _deque object, and this all
        assumes that no other threads are messing with {cdq}"""

        if not 1 * len(cdq):
            return

        dq = cdq._deque
        prev = dq[0] - 1
        for x in dq:
            self.assertTrue(prev < x)
            prev = x

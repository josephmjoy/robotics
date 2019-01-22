'''
Unit tests for the robotutils.concurrent_helper module.
Author: JMJ
'''

import unittest
import concurrent.futures
import random
import traceback
import time

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
                if random.randint(0, 1):
                    counter.next()
                else:
                    counter.add(1)
            self.assertAlmostEqual(counter.next(), initial+11)
            self.assertEqual(repr(counter), repr(initial+11))
            self.assertEqual(counter.value(), initial+11)

    def test_concurrent_next(self):
        """Multithreaded test for next"""
        counter = ch.AtomicNumber()
        n_submits = 4
        n_nexts_per_submit = 100000
        max_workers = 3

        total = 0
        with concurrent.futures.ThreadPoolExecutor(max_workers) as ex:
            sum_many = lambda n: sum(counter.next() for _ in range(n))
            # Sum up result of concurrently mapping multiple calls to sum_many
            total = sum(ex.map(sum_many, (n_nexts_per_submit for _ in range(n_submits))))
        # The end result is that count.next has been called
        # n_submits * n_nexts_per_submit times. The return value of *each* of
        # those calls are summed up...
        n = n_submits * n_nexts_per_submit
        n_expected = n * (n + 1) // 2  # sum OF 1 TO n - integer division
        self.assertEqual(total, n_expected)

    def test_concurrent_add(self):
        """Multithreaded test for add"""
        counter = ch.AtomicNumber()
        n_submits = 4
        max_addend = 100000
        max_workers = 3

        def add_many():
            for addend in range(max_addend + 1): # [0 ... max_addend]
                counter.add(addend)

        with concurrent.futures.ThreadPoolExecutor(max_workers) as ex:
            # All the workers do the exact same thing - summing from 0 to max_addend
            for _ in range(n_submits):
                ex.submit(add_many)
        # The end result is that count.add has been called to
        # add values from 0 to max_addend (inclusive). This is done
        # by each worker in the pool, i.e., n_submit times.
        n_expected = n_submits * max_addend * (max_addend + 1) // 2
        total = counter.value()
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
        cdq.process_all(lambda: self.fail('func call unexpected'))

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
        # - Every thread randomly removes elements on the left and the right.
        # - Each thread occasionally processes all the elements in the queue and
        #   verifies the snapshot invariant that all elements from a particular
        #   unique sequence are increasing left-to-right.
        # - Each thread very occasionally clears the deque by calling clear.
        cdq = ch.ConcurrentDeque()
        opcount = ch.AtomicNumber(0)
        max_workers = 10
        num_workers = 10

        with concurrent.futures.ThreadPoolExecutor(max_workers) as ex:
            futures = [ex.submit(self._worker, 'task'+str(i), cdq, opcount)
                       for i in range(num_workers)]
            for future in futures:
                # supressed - print("Waiting for exception...")
                exception = future.exception() # Will wait until task completes
                self.assertFalse(exception)

    def _worker(self, name, cdq, opcount):
        """Thread function with unique name {name}"""
        seq_length = 1000
        leftseq = ((name + '_L', -i)  for i in range(seq_length))
        rightseq = ((name + '_R', i) for i in range(seq_length))

        def examine_deque_element(element):
            """Examine deque element from snapshot of deque"""
            seqname, value = element
            self.assertTrue(isinstance(seqname, str))
            self.assertTrue(isinstance(value, int))

        local_opcount = 0

        def do_operation(): # pylint: disable=too-many-branches
            nonlocal local_opcount
            if random.random() < 0.5:
                cdq.appendleft(next(leftseq))  # append left
                local_opcount += 1
            if random.random() < 0.5:
                cdq.append(next(rightseq))     # append right
                local_opcount += 1
            if random.random() < 0.4:
                try:
                    element = cdq.popleft()  # pop left
                    examine_deque_element(element)
                except IndexError:
                    pass
                finally:
                    local_opcount += 1
            if random.random() < 0.4:
                try:
                    element = cdq.pop()    # pop right
                    examine_deque_element(element)
                except IndexError:
                    pass
                finally:
                    local_opcount += 1
            if random.random() < 0.1:
                cdq.process_all(examine_deque_element) # process all
                local_opcount += 1
            if random.random() < 0.01:
                cdq.clear()                      # clear
            if random.random() < 0.1:
                len(cdq) # just exercise  len    # calculate length
                local_opcount += 1

        try:
            while True: # exit on exception
                # print("xx-{}:{}".format(name, cdq._deque))
                if random.random() < 0.1:
                    self._verify_tuple_deque(cdq) # we don't do this everytime because it locks
                do_operation()

        except StopIteration:
            pass # we quit when any of our sequences end
        opcount.add(local_opcount)
        # print("Task {} completed. Opcount: {}".format(name, local_opcount))

    def _make_deque(self, n):
        """Makes a deque containing 1 to {n} in increasing order"""
        cdq = ch.ConcurrentDeque()
        for x in range(1, n+1):
            cdq.append(x)
        self._verify_increasing_deque(cdq)
        return cdq

    def _verify_increasing_deque(self, cdq):
        """Verifies that deque contains only increasing values (if any)
        WARNING: we reach into the underlying _deque object, and this all
        assumes that no other threads are messing with {cdq}"""

        if not len(cdq): # pylint: disable=len-as-condition
            return

        _deque = cdq._deque # pylint: disable=protected-access
        prev = _deque[0] - 1
        for x in _deque:
            self.assertTrue(prev < x)
            prev = x

    def _verify_tuple_deque(self, cdq):
        """Verify that each of the 'streams' in cdq are increasing"""
        prev_values = dict() # last seen value per stream

        def verify_element(element):
            """Verify that an element is greater than previousl seen
            value for that stream"""
            seqname, value = element # seqname is the name of the stream
            prev = prev_values.get(seqname, value-1)
            self.assertLess(prev, value, 'pooka')
            prev_values[seqname] = value

        cdq.process_all(verify_element)


class TestConcurrentDict(unittest.TestCase):
    """Container for ConcurrentDict tests"""

    def test_sequential_cdict(self):
        """Single-threaded ConcurrentDict test"""
        cdict = ch.ConcurrentDict()
        self.assertEqual(len(cdict), 0)

        cdict.set('1', 1)
        self.assertEqual(len(cdict), 1)
        cdict.clear()
        self.assertEqual(len(cdict), 0)
        self.assertEqual(cdict.get('1'), None)


        num_elements = 1
        kvdata = [(str(x), x) for x in range(num_elements)]

        # pylint: disable=invalid-name
        # (for all the v's)

        for k, v in kvdata:
            cdict.set(k, -v)
            cdict.set(k, v)
        self.assertEqual(len(cdict), num_elements)

        for k, v in kvdata:
            v1 = cdict.get(k, -1)
            v2 = cdict.get(k)
            v3 = cdict.pop(k)
            v4 = cdict.pop(k, -1) # should not find it
            self.assertEqual(v, v1)
            self.assertEqual(v, v2)
            self.assertEqual(v, v3)
            self.assertEqual(v4, -1)

        for k in range(num_elements, 2 * num_elements):
            v1 = cdict.get(k, -1)
            v2 = cdict.get(k)
            v3 = cdict.pop(k, -1) # should not find it
            self.assertEqual(v1, -1)
            self.assertEqual(v2, None)
            self.assertEqual(v3, -1)
            self.assertRaises(KeyError, cdict.pop, k)

        total = 0

        def process_func(x):
            nonlocal total
            total += x

        cdict.process_all(process_func)
        expected = (num_elements - 1) * num_elements // 2 # sum of 0 to (num-1)
        self.assertEqual(total, expected)

    def test_concurrent_cdict(self):
        """Concurrent test for ConcurrentDict"""
        # Create two distinct set of keys - shared and private
        # Partition the private set among the workers
        # Workers attempt to set, get and delete keys randomly from the
        # shared set - if they succeeed they verify the value.
        # They also keep a local dictionay of their private keys to keep track
        # of whether or not they hav set or deleted a specific key - and verify
        # this.
        # Periodically workers process the dictionary, picking out the keys that
        # they own and verifying the list against their local dictionary.
        #
        # Self validating keys and values:
        #     Shared key: 'shared_{n}', eg 'shared_25'
        #     Unshared key: '{task_id}_{n}', eg 'task2_42'
        #     Value: ({key}, {random-integer})', eg ('task2_42', 136369)
        # All can be generated from three integers: num_shared, num_private, num_workers
        num_shared = 100 # number of shared keys
        num_private = 100 # number of private keys per worker
        assert num_shared and num_private # need to be nonzero for random.choice to work
        num_workers = 3 # number of  workers
        max_workers = 3 # max number of workers executing concurrently
        num_iterations = 10000 # number of test operations performed by a worker

        cdict = ch.ConcurrentDict()
        opcount = ch.AtomicNumber(0)

        sizes = (num_shared, num_private, num_iterations) # check the order!

        with concurrent.futures.ThreadPoolExecutor(max_workers) as ex:
            futures = [ex.submit(self._worker, 'task'+str(i), cdict, sizes, opcount)
                       for i in range(num_workers)]
            for future in futures:
                # supressed - print("Waiting for exception...")
                exception = future.exception() # Will wait until task completes
                self.assertFalse(exception)

        print("opcount: " + str(opcount))

    def _worker(self, name, cdict, sizes, opcount):
        """A worker task, runs concurrently"""
        # pylint: disable=too-many-locals,too-many-branches,too-many-statements

        num_shared, num_private, num_iterations = sizes
        shared_keys = self._make_shared_keys(num_shared)
        private_keys = self._make_private_keys(name, num_private)
        prev_privates = dict() # keeps track of previous values
        local_ops = 0

        def cointoss():
            return random.randint(0, 1)

        try:
            for _ in range(num_iterations):
                if cointoss(): # get shared
                    key = random.choice(shared_keys)
                    value = cdict.get(key)
                    if value:
                        self._validate_kv_pair(key, value)
                    value = cdict.get(key, self._genvalue(key))
                    self._validate_kv_pair(key, value)
                    local_ops += 1
                if cointoss(): # set shared
                    key = random.choice(shared_keys)
                    value = self._genvalue(key)
                    cdict.set(key, value)
                    local_ops += 1
                if cointoss(): # pop shared
                    key = random.choice(shared_keys)
                    value = cdict.pop(key, self._genvalue(key))
                    self._validate_kv_pair(key, value)
                    local_ops += 1
                if cointoss(): # get private
                    key = random.choice(private_keys)
                    prev_value = prev_privates.get(key)
                    value = cdict.get(key)
                    self.assertEqual(prev_value, value) # could be None
                if cointoss(): # set private
                    key = random.choice(private_keys)
                    value = self._genvalue(key)
                    cdict.set(key, value)
                    prev_privates[key] = value
                    local_ops += 1
                if cointoss(): # pop private
                    key = random.choice(private_keys)
                    prev_value = prev_privates.pop(key, None) # could be None
                    try:
                        value = cdict.pop(key)
                        self.assertTrue(value) # if we get here, value is non-null
                        self.assertEqual(prev_value, value)
                    except KeyError as exc:
                        self.assertFalse(prev_value,
                                         "KeyError; Expecting key {}".format(prev_value))
                if random.random() < 0.5:
                    private_snapshot = dict() # new one each time, hence pylint disable below
                    def copy_privates(key, value):
                        """Collect all private kv pairs into prev_kvs"""
                        if key.startswith(name):
                            # private key
                            private_snapshot[key] = value # pylint: disable=cell-var-from-loop
                    cdict.process_all(copy_privates)
                    # comparing entire dictionaries below!
                    self.assertEqual(prev_privates, private_snapshot)
                    local_ops += 1


        except Exception as exc:
            print("Worker {}: UNCAUGHT EXCEPTION {}", name, exc)
            traceback.print_exc()
            raise exc
        finally:
            opcount.add(local_ops)
            print("Task {} completed. local_ops: {}".format(name, local_ops))


    @staticmethod
    def _make_shared_keys(num) -> list:
        """Make {num} shared keys. Returns a list."""
        return ['shared_{}'.format(i) for i in range(num)]

    @staticmethod
    def _make_private_keys(name, num) -> list:
        """Make {num} private keys for worker {name}. Returns a list."""
        return ['{}_{}'.format(name, i) for i in range(num)]

    @staticmethod
    def _genvalue(key):
        """Generate a valid random value given key {key}"""
        return (key, random.randint(0, 1<<32))

    def _validate_kv_pair(self, key, value):
        """Assert that this is a valid k-v pair"""
        vkey, vvalue = value
        self.assertEqual(key, vkey)
        self.assertIsInstance(vvalue, int)


class TestEventScheduler(unittest.TestCase):
    """Container for EventScheduler tests"""

    def test_scheduler_trivial(self):
        """Simple test of EventScheduler"""
        scheduler = ch.EventScheduler()
        scheduler.start()
        x = False

        def eventfunc():
            nonlocal x
            x = True

        scheduler.schedule(0.1, eventfunc)
        scheduler.stop(block=True)
        self.assertTrue(x)

    def test_scheduler_nocancel(self):
        """Schedule lots of events without canceling"""
        scheduler = ch.EventScheduler()
        scheduler.start()
        count = 0

        def eventfunc():
            nonlocal count
            count += 1 # Only one thread is modifying count

        numevents = 20000
        timespan = 1 # second
        for _ in range(numevents):
            delay = random.random()*timespan
            scheduler.schedule(delay, eventfunc)
            if random.random() < 10/numevents:
                time.sleep(0.1) # induce context switch every now and then
        scheduler.stop(block=True)
        self.assertTrue(scheduler.healthy())
        self.assertEqual(count, numevents)
        print("scheduler NOCANCEL: count={}/{}".format(count, numevents))

    def test_scheduler_cancel(self):
        """Tests calling cancel_all in the midst of scheduling lots of events"""
        scheduler = ch.EventScheduler()
        scheduler.start()
        count = 0

        def eventfunc():
            nonlocal count
            count += 1 # Only one thread is modifying count
            #print("----IN EVENTFUNC!!!!----")

        numevents = 100000
        timespan = 100 # seconds, i.e. a long time
        for _ in range(numevents):
            delay = random.random()*timespan
            scheduler.schedule(delay, eventfunc)
            if random.random() < 10/numevents:
                time.sleep(0.1) # induce context switch every now and then
        # print("MAIN: done submitting events")
        time.sleep(1) # 1 second into 100 seconds we cancel...
        scheduler.cancel_all()
        scheduler.stop(block=True)
        print("scheduler CANCEL: count={}/{}".format(count, numevents))
        self.assertTrue(scheduler.healthy())
        self.assertGreater(count, 0)

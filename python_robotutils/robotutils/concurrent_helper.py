"""
A private module that implements concurrency-friendly versions of
queues, dictionaries and counters.
Author: JMJ
"""

import collections
import logging
import time
import threading
import sched

_NO_DEFAULT = object() # To check if an optional parameter was specified in selected method calls

_logger = logging.getLogger(__name__) # pylint: disable=invalid-name
def trace(*args, **kwargs):
    """simply call debug"""
    _logger.debug(*args, **kwargs)
    #print(*args, **kwargs)

def tracing():
    """ Whether to trace or not"""
    return True

def critical(*args, **kwargs):
    """Wrapper to log critical error messages"""
    _logger.critical(*args, **kwargs)

class AtomicNumber:
    """Supports various atomic operations on numbers
       Adapted by JMJ from https://gist.github.com/benhoyt/8c8a8d62debe8e5aa5340373f9c509c7"""

    def __init__(self, initial=0):
        """Initialize a new atomic number to given initial value (default 0)."""
        self._value = initial
        self._lock = threading.Lock()

    def next(self):
        """
        Atomically increment the counter and return the new value
        >>> counter = AtomicNumber(40)
        >>> counter.next()
        41
        >>> counter.next()
        42
        """
        with self._lock:
            self._value += 1
            return self._value

    def add(self, addend) -> None:
        """
        Atomically add {addend} to the counter. Returns nothing.
        >>> counter = AtomicNumber(40)
        >>> counter.add(2) # returns nothing
        >>> counter.value()
        42
        """
        with self._lock:
            self._value += addend

    def __repr__(self):
        """ Returns string representation of value"""
        return repr(self._value)


    def value(self):
        """
        Returns a snapshot of the number without attempting
        to take the internal lock.
        >>> counter = AtomicNumber(42)
        >>> counter.value()
        42
        """
        return self._value


class ConcurrentDeque:
    """
    A thread-safe deque. It implements a subset of deque methods.
    >>> dq = ConcurrentDeque()
    >>> dq.appendleft(42)
    >>> print(len(dq))
    1
    >>> print(dq.pop())
    42
    >>> dq.append(100)
    >>> dq.process_all(lambda x: print(x))
    100
    >>> print(dq.popleft())
    100
    >>> dq.clear()
    >>> print(len(dq))
    0
    """

    #
    # IMPLEMENTATION NOTE: the lock MUST be held for all the calls below,
    # even if the GIL guarantees the calls (like self._deque.append) are themselves
    # thread safe. This is because iteration over elements in the deque may not
    # be able to deal with modifications done by other threads (deque documentation
    # does not say that iteration is thread safe)
    #

    def __init__(self):
        """Initialize a new empty deque"""
        self._deque = collections.deque()
        self._lock = threading.Lock()


    def append(self, x):
        """Add x to the right side of the deque."""
        with self._lock:
            return self._deque.append(x)

    def appendleft(self, x):
        """Add x to the left side of the deque."""
        with self._lock:
            return self._deque.appendleft(x)

    def clear(self):
        """Remove all elements from the deque leaving it with length 0."""
        with self._lock:
            return self._deque.clear()

    def __len__(self):
        """Returns a snapshot of the dequeue length."""
        with self._lock:
            return len(self._deque)

    def pop(self):
        """Remove and return an element from the right side of the deque.
        If no elements are present, raises an IndexError."""
        with self._lock:
            return self._deque.pop()

    def popleft(self):
        """Remove and return an element from the left side of the deque.
        If no elements are present, raises an IndexError."""
        with self._lock:
            return self._deque.popleft()

    def process_all(self, func):
        """Applies {func} to a snapshot of all elements.
        This is a heavyweight function. It makes a temporary
        copy of the entire dqueue with the deque lock held, but it does not
        keep the lock held when calling {func} This also means that it is possible
        that items may have been removed (or during) the function call, and items
        may have been concurrently added that are not processsed."""
        with self._lock:
            elements = list(self._deque) # Snapshot of entire deque
        for x in elements:
            func(x)


class ConcurrentDict:
    """A thread-safe dictionary, implementing a subset of dict methods
    >>> cd = ConcurrentDict()
    >>> cd.set('a', 1)
    >>> cd.get('a')
    1
    >>> cd.set('b', 2)
    >>> cd.get('b')
    2
    >>> len(cd)
    2
    >>> cd.get('c', 42)
    42
    >>> cd.process_all(lambda k, y: print(k, y))
    a 1
    b 2
    >>> cd.pop('a')
    1
    >>> cd.pop('a', 42) # key 'a' is no longer present
    42
    >>> cd.upsert('b', lambda x: x, 10) # 'b' exists, value is 2
    (2, False)
    >>> cd.upsert('e', lambda x: x, 10) # 'c' does not exist
    (10, True)
    >>> cd.remove_instance('e', 11) # shouldn't get deleted
    False
    >>> cd.get('e')
    10
    >>> cd.remove_instance('e', 10) # should get deleted
    True
    >>> cd.get('e') # doesn't exist, so None is returned, which isn't printed
    >>> cd.clear()
    >>> cd.empty()
    True
    >>>
    """

    #
    # IMPLEMENTATION NOTE: the lock MUST be held for all the calls below,
    # even if the GIL guarantees the calls (like self._dict.get) are themselves
    # thread safe. This is because the dict state must remain unchanged during iteration.
    #

    def __init__(self):
        """Initialize a new empty deque"""
        self._dict = dict()
        self._lock = threading.Lock()

    def get(self, key, value=None):
        """Return the value for key if key is in the dictionary, else default.
        If default is not given, it defaults to None, so that this method never raises a KeyError.
        """
        with self._lock:
            return self._dict.get(key, value)

    def set(self, key, value):
        """Sets the value of key {key} to {value}. The previous value, if any
        will be lost. Another option is upsert."""
        with self._lock:
            self._dict[key] = value

    def pop(self, key, default=_NO_DEFAULT):
        """ If key is in the dictionary, remove it and return its value,
        else return default. If default is not given and key is not in
        the dictionary, a KeyError is raised.
        """
        with self._lock:
            if default is _NO_DEFAULT:
                return self._dict.pop(key)
            return self._dict.pop(key, default)

    def remove_instance(self, key, value) -> bool:
        """ Remove ({key}, {value}) the specific instance of {value} is
        present, otherwise does nothing. Returns True IFF value was
        actually deleted."""
        with self._lock:
            try:
                obj = self._dict[key]
                if obj is value:
                    del self._dict[key]
                    return True
            except KeyError:
                pass
            return False

    def upsert(self, key, valuefunc, *args, **kwargs):
        """ Atomically, if there is no value or None associated with {key}, then
        call {valuefunc}(*args, **args) to generate and set a new value 'newvalue' and
        return tuple (newvalue, True).
        If, on the other hand, there was a non None previous value `prevvalue`, return
        (`prevvalue`, False). Thus, the return value is a tuple. The first element is the
        new or previous value, and the second element is True if the value was created and
        False if the value was preexisting. NOTE: {valuefunc} is called WITHOUT the dictionary lock
        being held. There is a small chance that {valuefunc} may be called but the resultant
        object discarded. There is also the chance that the previous or newly set value
        may be deleted concurrently by another thread before upsert returns.
        """
        # Note that if the previous value is None it will be replaced.
        # The new value also may potentially be None - if {valuefunc} returns None.
        # We don't examine the value returned by {valuefunc}
        curvalue = self.get(key) # default is None
        created = False
        if curvalue is None:
            newvalue = valuefunc(*args, **kwargs)
            with self._lock:
                curvalue = self._dict.get(key)
                if curvalue is None:
                    created = True
                    curvalue = self._dict[key] = newvalue
                # (else we discard newvalue)
        return (curvalue, created)


    def clear(self):
        """Remove all elements from the dictionary."""
        with self._lock:
            return self._dict.clear()

    def empty(self) -> bool:
        """Returns True if the dictionary is empty at this instant, False otherwise."""
        with self._lock:
            return not self._dict

    def __len__(self):
        """Returns a snapshot of the number of items in the dictionary."""
        with self._lock:
            return len(self._dict)

    def process_all(self, func):
        """Applies {func}(key, value) to a snapshot of all elements.
        This is a heavyweight function. It makes a temporary
        copy of the entire dqueue with the deque lock held, but it does not
        keep the lock held when calling {func}. This also means that it is possible
        that items may have been removed (or during) the function call, and items
        may have been concurrently added that are not processed.."""
        with self._lock:
            keys = list(self._dict) # Snapshot of all keys
        for key in keys:
            val = self._dict.get(key)
            if val is not None:
                func(key, val)



class EventScheduler:
    """
    Each instance manages events scheduled on a single background thread.
    >>> scheduler = EventScheduler()
    >>> scheduler.start()
    >>> x = False
    >>> def myevent(): global x; x = True
    >>> scheduler.schedule(0.1, myevent)
    >>> scheduler.stop(block=True)
    >>> print(x)
    True
    """

    #
    # IMPLEMENTATION NOTE: To keep the background thread active - i.e., re-running
    # self._scheduler.run(), various calls that the client calls all signal
    # self._event (threading.Event, not to be confused with client events!).
    # Lock self._lock is used to atomically check/set the value
    # of self._quit, self._event and relevant self._scheduler calls. The
    # sequence of signalling the threading event in relation to various
    # other calls is quite important. This ensures that:
    #  (a) a scheduled client event will always be serviced 'immediately' by the
    #      background thread.
    #  (b) No client events can sneak in concurrently with `cancel_all` being
    #      called and risk remaining scheduled in the queue.
    #  (c) The background thread will always exit 'immediately' after 'cancel_all'
    #      is called and will not be left waiting in some corner case.
    #

    def __init__(self):
        """Initialize an EventScheduler."""

        self._scheduler = sched.scheduler()
        self._lock = threading.Lock()
        self._event = threading.Event()
        self._cancelevent = threading.Event()
        self._event_exception = False
        self._thread = None
        self._quit = False


    def start(self):
        """Starts the scheduler. It starts a background thread in which context
        the scheduling is done"""

        def threadfn():
            done = False
            while not done:
                try:
                    prequit = self._quit
                    delay = self._scheduler.run(blocking=False)
                    if delay:
                        # Next event in queue happens in {delay} seconds
                        if self._cancelevent.wait(delay):
                            trace("threadfn: CANCELING")
                            done = True # we  are done - abandon the rest
                    else:
                        # Nothing in queue
                        if prequit:
                            # All done
                            trace("threadfn: NORMAL EXIT")
                            done = True
                        else:
                            # Queue is empty, let's wait for more
                            self._event.wait() # wait for more events...
                            self._event.clear()
                except Exception: # pylint: disable=broad-except
                    self._event_exception = True
                    # This would log an ERROR message with details about the exception.
                    # By default this prints to stderr, so we get to reports of this error
                    _logger.exception("EventScheduler: Client's threadfn threw exception")
                    break  # Get out of the loop
            trace("Exiting threadfn")

        with self._lock:
            if self._thread:
                raise ValueError("Scheduler seems already started")
            else:
                self._thread = threading.Thread(target=threadfn)
                self._thread.daemon = True
                self._thread.start()


    def schedule(self, delay, func) -> None:
        """Schedule event {func} to run after waiting {delay} from the point this
    call was made OR the scheduler was started, whichever happened later."""
        with self._lock:
            if self._thread and not self._quit and not self._event_exception:
                self._scheduler.enter(delay, priority=0, action=func) # will not block
                self._event.set() # wake up background thread if necessary
            else:
                raise ValueError("Cannot schedule event in current state")

    def cancel_all(self):
        """Cancel any pending and future events"""
        trace("cancel_all: enter")
        self._quit = True
        self._event.set() # wake up background thread if necessary
        self._cancelevent.set() # get out of scheduler.run() if necessary
        trace("cancel_all: exit")

    def stop(self, block=False):
        """Stop running once all pending events have been scheduled. If {block}
        then block until all events are completed. Once stopped, the scheduler
        cannot be restarted."""
        trace("stop: entered")
        with self._lock: # we don't NEED to lock, but locking anyway
            self._quit = True
            self._event.set() # wake up background thread if necessary
        if block and self._thread:
            trace("stop: waiting for thread to complete")
            # This works even if the thread has exited prematurely
            self._thread.join()
            trace("stop:thread completed")

    def healthy(self) -> bool:
        """ Returns if the scheduler is stopped or running OK. If
        a client event handler threw an exception it returns True."""
        return not self._event_exception


class ConcurrentInvoker:
    """For invoking a function in a different context and logging errors.
    Invocation is supressed if an earlier error occurred. A list of exceptions is
    available in self.exceptions (up to about MAX_EXCEPTIONS)"""

    # (Approximate) max exceptions to save in self.exceptions. It is
    # technically approximate because of concurrent execution
    MAX_EXCEPTIONS = 5

    def __init__(self, executor, logger=None):
        """Initialize the invoker"""
        self._executor = executor
        self._logger = logger
        self.exceptions = []

    def invoke(self, func, *args, **kwargs) -> None:
        """Invoke {func(*args, **kwargs)} in a different execution context.
        Invocation is suppressed if an earlier exception occurred. If an
        exception is raised by invoking {func}, the exception may be
        added to list {self.exceptions} and future invocations will be supressed."""
        self.tagged_invoke(None, func, *args, **kwargs)

    def tagged_invoke(self, tag, func, *args, **kwargs) -> None:
        """Same as invoke, with the addition that {tag} is logged to help
        distinguish one invocation of {func} from another."""

        prefix = str(tag)+ ', ' if tag else ''

        def rootfunc():
            if self.exceptions:
                if self._logger:
                    msg = "invocation(%s%s): SUPPRESSED because of earlier exception"
                    self._logger.error(msg, prefix, func.__name__)
                return
            try:
                func(*args, **kwargs)
            except Exception as exp:
                if len(self.exceptions) < ConcurrentInvoker.MAX_EXCEPTIONS:
                    self.exceptions.append(exp)
                if self._logger:
                    self._logger.error("Invocation(%s%s) raised exception",
                                       prefix, func.__name__, exc_info=True)
                raise exp

        self._executor.submit(rootfunc)


class CountDownLatch():
    """Emulation of Java's CountDownLatch.
        >>> latch = CountDownLatch(2)
        >>> latch.wait(0.01) # Haven't counted down to 0; should timeout.
        False
        >>> latch.count_down()
        >>> latch.wait(0.01) # Haven't counted down to 0; should timeout.
        False
        >>> latch.count_down()
        >>> latch.wait(0.01) # We've counted down to 0; should succeed
        True
        >>> latch.wait() # We've counted down to 0; should succeed
        True
    """

    def __init__(self, count):
        """Initializes the latch with the specified count"""
        self.count = count
        self.cond = threading.Condition()

    def count_down(self) -> None:
        """Counts down by 1. Count MUST be greater than 1 else
        a ValueError exception is thrown"""
        with self.cond:
            if self.count < 1:
                raise ValueError("Attempt to count down below 0")
            self.count -= 1
            if self.count <= 0:
                self.cond.notifyAll()

    def wait(self, timeout=None) -> bool:
        """Waits until the count goes to zero, or until the timeout expires.
        If {timeout} is None, there is no timeout.
        Return value: True unless timeout expired, in which case it returns False.
        Note: timer resolution is time.time(), but also based on thread scheduling
        latencies.
        """

        if timeout is None:
            # simple case of infinite timeout
            with self.cond:
                while self.count > 0:
                    self.cond.wait()
                return True  # -------- EARLY RETURN --------

        timeleft = timeout
        timed_out = False
        with self.cond:
            # {{Inv: timeout not hit and count > 0}}
            start = time.time()
            while not timed_out and self.count > 0:
                signalled = self.cond.wait(timeleft)
                if signalled:
                    if  self.count > 0:
                        # Oops - we were signaled, but the
                        # count is still not 0. We'll have to
                        # try again. Update time left
                        delta = time.time() - start
                        timeleft = timeout - delta
                        timed_out = timeleft <= 0
                else:
                    # We timed out.
                    timed_out = True

        return not timed_out


if __name__ == '__main__':
    import doctest
    doctest.testmod()

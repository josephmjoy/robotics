"""
A private module that implements concurrency-friendly versions of
queues, dictionaries and counters.
"""

import threading
import collections

_NO_DEFAULT = object() # To check if an optional parameter was specified in selected method calls

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
    # Implementation note: the lock MUST be held for all the calls below,
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
    >>> len(cd)
    1
    >>> cd.set('b', 2)
    >>> cd.get('b')
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
    >>> cd.clear()
    >>> len(cd)
    0
    >>>
    """


    #
    # Implementation note: the lock MUST be held for all the calls below,
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
        """Add x to the left side of the deque."""
        with self._lock:
            self._dict[key] = value

    def pop(self, key, default=_NO_DEFAULT):
        """
        If key is in the dictionary, remove it and return its value,
        else return default. If default is not given and key is not in
        the dictionary, a KeyError is raised.
        """
        with self._lock:
            if default is _NO_DEFAULT:
                return self._dict.pop(key)
            return self._dict.pop(key, default)

    def clear(self):
        """Remove all elements from the dictionary."""
        with self._lock:
            return self._dict.clear()

    def __len__(self):
        """Returns a snapshot of the dictionary length."""
        with self._lock:
            return len(self._dict)

    def process_all(self, func):
        """Applies {func}(key, value) to a snapshot of all elements.
        This is a heavyweight function. It makes a temporary
        copy of the entire dqueue with the deque lock held, but it does not
        keep the lock held when calling {func}. This also means that it is possible
        that items may have been removed (or during) the function call, and items
        may have been concurrently added that are not processsed.."""
        with self._lock:
            keys = list(self._dict) # Snapshot of all keys
        for key in keys:
            val = self._dict.get(key)
            if val is not None:
                func(key, val)


if __name__ == '__main__':
    import doctest
    doctest.testmod()
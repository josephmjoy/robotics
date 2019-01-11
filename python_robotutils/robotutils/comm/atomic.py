"""
A private module that implements various atomic operations
Adapted by JMJ from https://gist.github.com/benhoyt/8c8a8d62debe8e5aa5340373f9c509c7
"""
import threading

class AtomicNumber:
    """Supports various atomic operations on numbers"""

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


if __name__ == '__main__':
    import doctest
    doctest.testmod()

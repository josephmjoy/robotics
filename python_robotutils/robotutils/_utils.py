"""
Internal Utilities
"""
import itertools

def getsome(func, maxnum=None, *, sentinel=None):
    """A generator that returns up to {maxum} things. It
    will return less if {func}():
     - raises an IndexError exception
     - raises a StopIteration exception
     - returns the sentinel value.
    If {maxnum} is not specified it defaults to infinity
    >>> for x in getsome((x for x in range(10)).__next__, 5):
    ...     print(x, end=' ')
    0 1 2 3 4
    >>>
    """
    try:
        iter_ = range(maxnum) if maxnum is not None else itertools.count()
        for _ in iter_:
            value = func()
            if value == sentinel:
                return
            yield value
    except (IndexError, StopIteration):
        pass


def containschars(str_, charset) -> bool:
    """Returns if {str} contains any chars in {chars}"""
    for char in str_:
        if char in charset:
            return True
    return False

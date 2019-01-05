"""
This module contains a helper to extract various kinds of primitive data types
from a dictionary of strings.
"""


class StringDictHelper:
    """Helper class to extract primitive types from a dictionary of strings. This is a port
       of Java robotutils class StringmapHelper"""

    def __init__(self, dct):
        """Constructs a helper for the given dict {dct}"""
        self._dct = dct

    def get_as_str(self, key, default, pattern=None):
        """
        Returns a string - either parsed from map of {key} or {defaultValue}.

        param key -- key to lookup.
        default   -- default value to use if the key did not exist, the value was not
                 parseable or did not match {pattern}. This value does not need
                 match {pattern}.
        pattern   -- [If not None] Regex.Pattern object representing valid strings to accept.
        """
        ret = str(self._dct.get(key, default))
        if pattern:
            return ret if pattern.search(ret) else default
        return ret

    def get_as_bool(self, key, default):
        """
        Returns a bool - either parsed from map of {key} or {defaultValue}.

        param key -- key to lookup.
        default   -- default value to use if the key did not exist or the value was not
                 parseable.
        """
        ret = self._dct.get(key)
        if ret:
            ret = ret.lower()
        if ret == 'true':
            ret = True
        elif ret == 'false':
            ret = False
        else:
            ret = default
        return ret


if __name__ == '__main__':
    D = dict(a='abc', b='true', c=42)
    H = StringDictHelper(D)
    AV = H.get_as_str('a', '')
    BV = H.get_as_bool('b', False)
    assert AV == 'abc'
    assert BV is True
    print("StringDictHelper seems to work")

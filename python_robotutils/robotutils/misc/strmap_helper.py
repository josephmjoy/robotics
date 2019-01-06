"""
This module contains a helper to extract various kinds of primitive data types
from a dictionary of strings.
"""


class StringDictHelper:
    """
       Helper class to extract primitive types from a dictionary of strings. This is a port
       of Java robotutils class StringmapHelper. The special values 'true' and 'false' (in
       any combinations of case) represent  boolean True and False. This MUST NOT be changed
       as it is part of the 'structured message' used in the robotcomm protocol and in
       in configuration and logging - across multiple languages.
    """

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
            return ret if pattern.fullmatch(ret) else default
        return ret

    def get_as_bool(self, key, default):
        """
        Returns a bool - either parsed from map of {key} or {default}.

        key       -- key to lookup.
        default   -- default value to use if the key did not exist or the value was not
                 parseable.
        """
        val = self._dct.get(key)
        ret = default
        if val:
            val = val.lower()
        if val == 'true':
            ret = True
        elif val == 'false':
            ret = False
        return ret

    def get_as_num(self, key, default, minval=None, maxval=None):
        """
        Returns a number - either parsed from map of {key} or {default}.

        key -- key to lookup.
        default -- default value to use if the key did exist, the value was not
                parseable or out of bounds. This value does not need to be between
                {minval} and {maxval}.
                NOTE: The *type* of this default value is used to
                determine the type of return value. So, if a floating point value is expected,
                specify a float default value!
        [minval]  -- Optional inclusive minimum to accept.
        [maxval]  -- Optional inclusive (not exclusive) maximum to accept.
        """
        val = self._dct.get(key)
        ret = default
        if val:
            try:
                # Below we extract type (int or float or ??) and use it to construct the result!
                type_ = type(default)
                ret1 = type_(val)
                valid = (minval is None or ret1 >= minval) and (maxval is None or ret1 <= maxval)
                ret = ret1 if valid else default
            except ValueError:
                ret = default

        return ret


if __name__ == '__main__':
    D = dict(a='abc', b='true', c=42, d=1.5)
    H = StringDictHelper(D)
    AV = H.get_as_str('a', '')
    BV = H.get_as_bool('b', False)
    CV = H.get_as_num('c', 100)
    DV = H.get_as_num('d', 0.0)
    assert AV == 'abc'
    assert BV is True
    assert CV == 42
    assert abs(DV-1.5) < 1E-10
    print("StringDictHelper seems to work")

"""
Utility functions to convert between the string and dictionary representations
of 'structured messages'. For example, to convert between
'key1:value1 key2:value2' and {'key1':value1, 'key2':'value2'}
"""
import re

_COLON = ':'
_ERRORS_KEY = '_errors'
_COLON_PAT = re.compile(_COLON)
_KEY_PAT = re.compile(r'\S+\s*$')


def str_to_dict(msg):
    '''
        Convert from a structured message (string representation) to a dictionary.
        Example structured message: 'key1:value1 key2:value2" converts to
        {'key1' : 'value', 'key2' : 'value2'}.
        This is equivalent to the Java robotutils StructuredMessageMapper.toHashMap
        method.
    '''
    parts = msg.split(':')
    n = len(parts)
    assert n >= 1 # Even with an empty message, we should get one part.
    pairs = n - 1
    map1 = dict()
    if pairs <= 0:
        # No colons, so no key-value pairs.
        if msg:
            map1[_ERRORS_KEY] = "no keys"
            return map1 # ************* EARLY RETURN ************

    prev_key = ''
    empty_key = False
    duplicate_key = False
    for i in range(0, pairs+1):
        pre = parts[i]
        value = None
        key = ''
        if i < pairs:
            match = _KEY_PAT.search(pre)
            if match:
                k_start = match.start()
                value = pre[:k_start].strip()
                key = pre[k_start:].strip()
                assert key
            else:
                # Hmm, empty key
                empty_key = True

        if not value:
            value = pre.strip()

        if prev_key:
            if map1.get(prev_key):
                # Hmm, duplicate key
                duplicate_key = True
            else:
                map1[prev_key] = value
        prev_key = key

    if empty_key or duplicate_key:
        errors = map1.get(_ERRORS_KEY, '')
        if empty_key:
            errors += '; empty key(s)'
        if duplicate_key:
            errors += '; duplicate key(s)'
        map1[_ERRORS_KEY] = errors

    return map1


def dict_to_str(dct, keys=None):
    '''
        Convert a structured message map from a dictionary to a string
        representation).
        Example:  Dictionary {'key1' : 'value', 'key2' : 'value2'}
        converts to structured text message 'key1:value1 key2:value2".

        This is equivalent to the Java robotutils
        StructuredMessageMapper.toString
    '''
    keys = dct.keys()
    # picked up following ideom from https://waymoot.org/home/python_string/
    return str.join(' ', [k + ':' + str(dct[k]) for k in keys])


if __name__ == '__main__':
    S = 'key1:value1 key2:value2'
    D = str_to_dict(S)
    print('str_to_dict maps "' + S + '" to ' + str(D))
    S2 = dict_to_str(D)
    print('dict_to_str maps ' + str(D) + 'to "'+ str(S) + '"')
    assert S == S2 # we should get back to the original string.

"""
Utility functions to convert between the string and dictionary representations
of 'structured messages'. For example, to convert between
'key1:value1 key2:value2' and {'key1':value1, 'key2':'value2'}
"""
import re

_COLON = ':'
_ERRORS_KEY = '_errors'
_COLON_PAT = re.compile(_COLON)
_KEY_PAT = re.compile(r'\S+\s*$');


def str_to_dict (msg):
    '''
        Convert from a structured message (string representation) to a dictionary.
        Example structured message: 'key1:value1 key2:value2" converts to
        {'key1' : 'value', 'key2' : 'value2'}.
        This is equivalent to the Java robotutils StructuredMessageMapper.toHashMap
        method.
    '''
    parts = msg.split(':')
    n = len(parts)
    assert n>=1 # Even with an empty message, we should get one part.
    pairs = n - 1
    map1 = dict()
    if pairs <= 0:
        # No colons, so no key-value pairs.
        if len(msg) > 0:
            map1[_ERRORS_KEY] = "no keys"
            return map1 # ************* EARLY RETURN ************

    prevKey = ''
    emptyKey = False
    duplicateKey = False
    for i in range(0, pairs+1):
        pre = parts[i]
        value = None
        key = ''
        if i<pairs:
            m = _KEY_PAT.search(pre)
            if m:
                kStart = m.start()
                value = pre[:kStart].strip()
                key = pre[kStart:].strip()
                assert len(key)> 0
            else:
                # Hmm, empty key
                emptyKey = True

        if not value:
            value = pre.strip()

        if len(prevKey) > 0:
            if map1.get(prevKey):
                # Hmm, duplicate key
                duplicateKey = True
            else:
                map1[prevKey] = value
        prevKey = key

    if emptyKey or duplicateKey:
        errors = map1.get(_ERRORS_KEY, '')
        if emptyKey:
            errors += '; empty key(s)'
        if duplicateKey:
            errors += '; duplicate key(s)'
        map1[_ERRORS_KEY] = errors

    return map1


def dict_to_str (d, keys=None):
    '''
        Convert a structured message map from a dictionary to a string
        representation).
        Example:  Dictionary {'key1' : 'value', 'key2' : 'value2'}
        converts to structured text message 'key1:value1 key2:value2".
        
        This is equivalent to the Java robotutils
        StructuredMessageMapper.toString
    '''
    keys = d.keys()
    # picked up following ideom from https://waymoot.org/home/python_string/
    return str.join(' ', [k + ':' + str(d[k]) for k in keys])


if __name__ == '__main__':
    s = 'key1:value1 key2:value2'
    d = str_to_dict(s)
    print('str_to_dict maps "' + s + '" to ' + str(d))
    s2 = dict_to_str(d)
    print('dict_to_str maps ' + str(d) + 'to "'+ str(s) + '"')
    assert (s == s2) # we should get back to the original string.

"""
Utilities for reading configuration data.
Ported by Joseph M. Joy (https://github.com/josephmjoy) from
the Java version (class ConfigurationHelper)
"""
import sys
import re


_COLONSPACE = ": " # space after : is MANDATORY
_REGEX_WHITESPACE = re.compile(r"\s+")
_HYPHENSPACE = "- " # space after - is MANDATORY


def read_section(reader, section_name, keys) -> dict:
    """
    Loads the specified section from the specified reader. It will not raise an
    exception. On error (OS exception or not being able to find the section) it
    will return an empty dict. It leaves the reader in the same seek position
    as it did on entry, unless there was an OS exception, in which case the
    state of the file pointer will be unknown

    If {keys} is non-null it will clear it and fill it with the keys in the order
    that they were found in the input.

    Returns: dict representing mapping of keys to values for that specified section.
    """
    mapping = {}
    if keys:
        keys.clear()
    # We work with a copy becaues we have to seek ahead
    # looking for the section
    # OBSOLETE with io.TextIOWrapper(reader) as reader2:
    try:
        start = reader.tell()
        if _find_section(section_name, reader):
            _process_section(reader, mapping, keys)
        reader.seek(start)
    except OSError as err:
        _printerr(err) #nothing to do

    return mapping


def write_section(section_name, section, keys, writer) -> bool:
    """
    Saves the specified section to the specified writer starting at the current
    point in the writer. It will not throw an exception. On error (IO exception
    or not being able to write the section) it will return false. WARNING: It can
    not scan the destination to see if this section has already been written, so
    typically this method is called when writing out an entire configuration with
    multiple sections in sequence.

    Returns True on success and False on failure.
    """
    keys = keys if keys else section.keys()
    ret = False

    # OBSOLETE with io.TextIOWrapper(writer) as writer2:
    try:
        writer.write(section_name + ":\n")
        for k in keys:
            val = section.get(k)
            if val:
                output = "  " + k + _COLONSPACE + val + "\n"
                writer.write(output)
        ret = True
    except OSError as err:
        _printerr(err) # Just return false

    return ret


def read_list(section_name, reader) -> list:
    """
    Loads the specified list-of-strings section from the specified reader.
    It will not throw an exception. On error (OS xception or not being able to find
    the section) it will return an empty list.
    It leaves the reader in the same seek position as it did on entry,
    unless there was an OS exception, in which case the state of the file pointer will be unknown.
    Return: List of strings in the section
    """
    list_items = []
    # OBSOLETE with io.TextIOWrapper(reader) as reader2:
    try:
        start = reader.tell()
        if _find_section(section_name, reader):
            _process_list_section(reader, list_items)
        reader.seek(start)
    except OSError as err:
        _printerr(err) # Just return false
    return list_items


def write_list(section_name, list_items, writer) -> bool:
    """
    Saves the specified list-of-strings section to the specified writer starting
    at the current point in the writer. It will not throw an exception. On error
    (OS exception or not being able to write the section) it will return false.
    WARNING: It cannot scan the destination to see if this section has already
    been written, so typically this method is called when writing out an entire
    configuration with multiple sections in sequence.

    Returns: True on success and False on failure.
    """
    ret = False

    #OBSOLETE with io.TextIOWrapper(writer) as writer2:
    try:
        writer.write(section_name + ":\n")
        for item in list_items:
            output = "  " + _HYPHENSPACE + item + "\n"
            writer.write(output)
        ret = True
    except OSError as err:
        _printerr(err) # Just return false

    return ret

#
#  ------------ Private Methods ----------------
#

def _find_section(section_name, reader) -> bool:
    """
    Seeks to the start of the specified section. If it does not find the section
    the reader's file pointer will be in an unknown state.
    Returns: True on success and False on failure.
    """
    if not section_name or ' ' in section_name or ':' in section_name or '\t' in section_name:
        # Bogus section name. Note that colons are not allowed within section names,
        # though that is valid YAML.
        # This is because this is a configuration reader, not general YAML parser.
        return False # ******************** EARLY RETURN *******************

    ret = False
    for line in reader:
        if line.startswith("..."):
            break
        if _matches_section_name(line, section_name):
            remaining = line[len(section_name):]
            remaining = _trim_comment(remaining).strip()
            if ':' in remaining:
                icolon = remaining.index(':')
                precolon = remaining[:icolon].strip()
                postcolon = remaining[icolon + 1:].strip()
                ret = not (precolon or postcolon)
                break
        # doesn't match, keep looking...

    return ret


def _matches_section_name(line, section_name) -> bool:
    """Checks if {line} starts with the specified section name """
    if not line.startswith(section_name):
        return False

    # Line STARTS with {section_name} - promising, but perhaps it's a prefix of
    # a longer section name or something badly garbled...
    if len(line) > len(section_name):
        char = line[len(section_name)] # char right after sectioName
        if not char in "\t :#":
            # 1st post char could be part of a longer section name, so let's just keep
            # looking
            return False
    return True


def _trim_comment(line) -> str:
    """Remove comment from end of line, if any"""
    if '#' in line:
        icomment = line.index('#')
        line = line[:icomment]
    return line


def _process_section(reader, mapping, keys) -> None:
    """
    Read in a section. Place any (k,v) pairs in to dict {mapping}. If {keys} add any
    keys read, in the order they were read.
    """
    indentation = -1 # we will set it when we find the first child of the section.
    for line in reader:
        if line.startswith("..."):
            break
        line = _trim_comment(line)
        validkv = False
        pre = post = ""

        if line and not _REGEX_WHITESPACE.fullmatch(line):

            icolon = line.index(_COLONSPACE) if _COLONSPACE in line else -1
            if icolon:
                pre = line[:icolon].strip()
                post = line[icolon + 1:].strip() # +1 for space after colon
            else:
                # The other case is the line *ends* in a colon...
                if line.endswith(':'):
                    pre = line[:-1].strip()
                    post = ""

            if pre:

                this_indentation = line.index(pre)
                if indentation < 0:
                    indentation = this_indentation
                    assert indentation >= 0

                if indentation < 1 or this_indentation < indentation:
                    # We are done because indentation level has popped up.
                    break

                validkv = indentation == this_indentation and post

        if validkv:
            mapping[pre] = post
            if keys:
                keys.append(pre)


def _process_list_section(reader, items) -> None:
    """
    Read a list of strings and put them into {items}. Quit early if encountering
    anything unexpected
    """
    indentation = -1 # we will set it when we find the first child of the section.
    quit_ = False
    for line in reader:
        if quit_ or line.startswith("..."):
            break
        line = _trim_comment(line)
        if line and not _REGEX_WHITESPACE.fullmatch(line):
            quit_ = True
            if _HYPHENSPACE in line:
                ihyphen = line.index(_HYPHENSPACE)
                pre = line[:ihyphen].strip()
                post = line[ihyphen + 1:].strip() # +1 for space after hyphen

                if not pre:

                    this_indentation = ihyphen
                    if indentation < 0:
                        indentation = this_indentation
                        assert indentation >= 0

                    # We expect strictly indented lines with exactly the same
                    # indentation.
                    if indentation >= 1 and this_indentation == indentation:
                        items.append(post) # Empty strings are added too.
                        quit_ = False


def _printerr(*args):
    """print error message"""
    print(*args, file=sys.stderr)

"""
Utilities for reading configuration data.
Ported by Joseph M. Joy (https://github.com/josephmjoy) from
the Java version (class ConfigurationHelper)
"""
import io
import sys
import re


_COLONSPACE = ": " # space after : is MANDATORY
_REGEX_WHITESPACE = re.compile(r"\s+")
_HYPHENSPACE = "- " # space after - is MANDATORY


def read_section(reader, section_name, keys) -> dict:
    """
    Loads the specified section from the specified reader. It will not raise an
    exception. On error (IO exception or not being able to find the section) it
    will return an empty dict.

    If {keys} is non-null it will clear it and fill it with the keys in the order
    that they were found in the input.

    Returns: dict representing mapping of keys to values for that specified section.
    """
    mapping = {}
    if keys:
        keys.clear()
    # We work with a copy becaues we have to seek ahead
    # looking for the section
    with io.TextIOWrapper(reader) as reader2:
        try:
            if _find_section(section_name, reader2):
                _process_section(reader2, mapping, keys)
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

    with io.TextIOWrapper(writer) as writer2:
        try:
            writer2.write(section_name + ":\n")
            for k in keys:
                val = section.get(k)
                if val:
                    output = "  " + k + _COLONSPACE + val + "\n"
                    writer2.write(output)
            ret = True
        except OSError: # as err:
            _printerr(err) # Just return false

    return ret


def _find_section(section_name, reader):
    """find section"""

def _process_section(reader, mapping, keys):
    """find section"""

def _printerr(*args):
    """print error message"""
    print(*args, file=sys.stderr)

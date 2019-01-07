'''
Unit tests for the robotutils.config_helper module.

This is a port of the JUnit test for the corresponding Java
robotutils class, ConfigurationHelper
Author: JMJ
'''

import unittest
import io

from . import config_helper

class TestConfigHelper(unittest.TestCase):
    """Container for config_helper unit tests"""

    def test_simple_section_usage(self):
        """Test simple non-empty section"""
        inp = "\n".join(("mySection:", "  sk: sv", "  ik: 10\n"))
        reader = io.StringIO(inp) # in-memory stream input
        keys = []
        mapper = config_helper.read_section(reader, "mySection", keys)
        writer = io.StringIO() # in-memory stream output
        bresult = config_helper.write_section("mySection", mapper, keys, writer)
        self.assertTrue(bresult)
        output = writer.getvalue()
        self.assertEqual(inp, output)

    def test_simple_list_usage(self):
        """Test a simple list of two items"""
        inp = "\n".join(("myList:", "  - item1", "  - item2\n"))

        reader = io.StringIO(inp) # in-memory stream input
        lines = config_helper.read_list("myList", reader)
        writer = io.StringIO() # in-memory stream output
        bresult = config_helper.write_list("myList", lines, writer)
        self.assertTrue(bresult)
        output = writer.getvalue()
        self.assertEqual(inp, output)

    def test_messy_yaml_input(self):
        """Test a much more complicated config file"""
        inp = """
# This is a comment
---

# Section 1
section1:
  k1a: v1a
  k1b: v1b
sectionx:# empty section
section2: # with comment
    k2a:     v2a
    k2b:     v2b

section3: # with comment
    kComplex: #complex section to be ignored
      with complex floating text
       complex1: 1
       complex2: 2
    k3a:     v3a # added comment
# some comment and empty lines and lines with spacecs
  # comment

               \t
    k3b:     v3b

section4: # with comment

    k4a: v4a
    k4b: v4b,    
  badSect: badVal
    skip: skip
# List 1
list1:
  - v1a
  - v1b
listx:# empty section
list2: # with comment
    -     v2a
    -   v2b

list3: # with comment
    -     v3a # added comment
# some comment and empty lines and lines with spacecs
  # comment

               \t
    -   v3b
    kComplex: #complex section will trigger early quitting
      with complex floating text
       complex1: 1
       complex2: 2
    - random item
                
list4: # with comment

    - v4a
    - v4b,    
  - badVal
    - skip
"""

        keys = []
        writer = io.StringIO() # in-memory stream output
        reader = io.StringIO(inp) # in-memory stream input

        # Process sections
        for i in range(1, 5):
            stri = str(i)
            section = "section" + stri
            mapping = config_helper.read_section(reader, section, keys)
            ret = config_helper.write_section(section, mapping, keys, writer)
            self.assertEqual(True, ret)

            # We expect certain keys and values to be there.
            self.assertEqual(2, len(mapping))
            self.assertEqual("v"+i+"a", mapping.get("k" + stri + "a"))
            self.assertEqual("v"+i+"b", mapping.get("k" + stri + "b"))

        # Process lists
        for i in range(1, 5):
            stri = str(i)
            section = "list" + stri
            items = config_helper.read_list(section, reader)
            ret = config_helper.write_list(section, items, writer)
            self.assertTrue(ret)

            # We expect certain list items to be there.
            self.assertEqual(2, len(items))
            self.assertEqual("v"+i+"a", items[0])
            self.assertEqual("v"+i+"b", items[1])

        # Creat new input from what was just written
        input2 = writer.getvalue()
        writer2 = io.StringIO()

        # Process sections a 2nd time - from the new input
        reader = io.StringIO(input2)

        for i in range(1, 5):
            section = "section" + str(i)
            mapping = config_helper.read_section(reader, section, keys)
            ret = config_helper.write_section(section, mapping, keys, writer2)
            self.assertTrue(ret)

        # Process lists a 2nd time - from the new input
        for i in range(1, 5):
            section = "list" + str(i)
            items = config_helper.read_list(section, reader)
            ret = config_helper.write_list(section, items, writer2)
            self.assertTrue(ret)


        output2 = writer2.getvalue()
        self.assertEqual(input2, output2)

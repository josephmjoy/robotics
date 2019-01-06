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

'''
    @Test
    void testSimpleListUsage() {
        String input = String.join("\n", "myList:", "  - item1", "  - item2\n");

        Reader r = new StringReader(input);
        List<String> lines = ConfigurationHelper.readList("myList", r);
        Writer w = new StringWriter();
        boolean b = ConfigurationHelper.writeList("myList", lines, w);
        assertEquals(true, b);
        String output = w.toString();
        assertEquals(input, output);
    }
    
    @Test
    void testMessyYamlInput() {
    
        String input = String.join("\n", 
                "# This is a comment",
                "---",
                "",
                "# Section 1",
                "section1:",
                "  k1a: v1a",
                "  k1b: v1b",
                "sectionx:# empty section",
                "section2: # with comment",
                "    k2a:     v2a",
                "    k2b:     v2b",
                "",
                "section3: # with comment",
                "    kComplex: #complex section to be ignored",
                "      with complex floating text",
                "       complex1: 1",
                "       complex2: 2",
                "    k3a:     v3a # added comment",
                "# some comment and empty lines and lines with spacecs",
                "  # comment",
                "",
                "               \t",
                "    k3b:     v3b",
                "",
                "section4: # with comment",
                "",
                "    k4a: v4a",
                "    k4b: v4b",    
                "  badSect: badVal",
                "    skip: skip",
                "# List 1",
                "list1:",
                "  - v1a",
                "  - v1b",
                "listx:# empty section",
                "list2: # with comment",
                "    -     v2a",
                "    -   v2b",
                "",
                "list3: # with comment",
                "    -     v3a # added comment",
                "# some comment and empty lines and lines with spacecs",
                "  # comment",
                "",
                "               \t",
                "    -   v3b",
                "    kComplex: #complex section will trigger early quitting",
                "      with complex floating text",
                "       complex1: 1",
                "       complex2: 2",
                "    - random item",
                                "",
                "list4: # with comment",
                "",
                "    - v4a",
                "    - v4b",    
                "  - badVal",
                "    - skip"
                );

        ArrayList<String> keys = new ArrayList<>();
        Writer w = new StringWriter();
        
        // Process Sections
        for (int i = 1; i <= 4; i++) {
            Reader r = new StringReader(input);
            String section = "section" + i;
            Map<String, String> map = ConfigurationHelper.readSection(r, section, keys);
            boolean b = ConfigurationHelper.writeSection(section, map, keys, w);
            assertEquals(true, b);
            
            // We expect certain keys and values to be there.
            assertEquals(2, map.size());
            assertEquals("v"+i+"a", map.get("k" + i + "a"));
            assertEquals("v"+i+"b", map.get("k" + i + "b"));
        }
        
        // Process Lists
        for (int i = 1; i <= 4; i++) {
            Reader r = new StringReader(input);
            String section = "list" + i;
            List<String> li = ConfigurationHelper.readList(section, r);
            boolean b = ConfigurationHelper.writeList(section, li, w);
            assertEquals(true, b);
            
            // We expect certain list items to be there.
            assertEquals(2, li.size());
            assertEquals("v"+i+"a", li.get(0));
            assertEquals("v"+i+"b", li.get(1));
        }
        
        String input2 = w.toString();
        Writer w2 = new StringWriter();
        for (int i = 1; i <= 4; i++) {
            Reader r = new StringReader(input2);
            String section = "section" + i;
            Map<String, String> map = ConfigurationHelper.readSection(r, section, keys);
            boolean b = ConfigurationHelper.writeSection(section, map, keys, w2);
            assertEquals(true, b);
         }
        //String output2 = w2.toString();
        //assertEquals(input2, output2);
       
        
        for (int i = 1; i <= 4; i++) {
            Reader r = new StringReader(input2);
            String section = "list" + i;
            List<String> li = ConfigurationHelper.readList(section, r);
            boolean b = ConfigurationHelper.writeList(section, li, w2);
            assertEquals(true, b);
         }
        
        String output2 = w2.toString();
        assertEquals(input2, output2);

    }

'''

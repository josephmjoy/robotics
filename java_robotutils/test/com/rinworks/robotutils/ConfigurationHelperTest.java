package com.rinworks.robotutils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConfigurationHelperTest {

    @Test
    void testSimpleUsage() {
        String input = String.join("\n", "mySection:", "  sk: sv", "  ik: 10\n");

        Reader r = new StringReader(input);
        ArrayList<String> keys = new ArrayList<>();
        Map<String, String> map = ConfigurationHelper.readSection("mySection", r, keys);
        Writer w = new StringWriter();
        boolean b = ConfigurationHelper.writeSection("mySection", map, keys, w);
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
                "    skip: skip"
                );

        ArrayList<String> keys = new ArrayList<>();
        Writer w = new StringWriter();
        
        for (int i = 1; i <= 4; i++) {
            Reader r = new StringReader(input);
            String section = "section" + i;
            Map<String, String> map = ConfigurationHelper.readSection(section, r, keys);
            boolean b = ConfigurationHelper.writeSection(section, map, keys, w);
            assertEquals(true, b);
            
            // We expect certain keys and values to be there.
            assertEquals(2, map.size());
            assertEquals("v"+i+"a", map.get("k" + i + "a"));
            assertEquals("v"+i+"a", map.get("k" + i + "a"));
        }
        
        String input2 = w.toString();
        Writer w2 = new StringWriter();
        for (int i = 1; i <= 4; i++) {
            Reader r = new StringReader(input2);
            String section = "section" + i;
            Map<String, String> map = ConfigurationHelper.readSection(section, r, keys);
            boolean b = ConfigurationHelper.writeSection(section, map, keys, w2);
            assertEquals(true, b);
         }
        String output2 = w2.toString();
        assertEquals(input2, output2);

    }

}

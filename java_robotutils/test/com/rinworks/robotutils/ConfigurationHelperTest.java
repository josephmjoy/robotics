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

}

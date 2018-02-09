package com.rinworks.robotutils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Reader;
import java.io.Writer;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConfigurationHelperTest {

    @Test
    void testSimpleUsage() {
        Reader r = null;
        Map<String, String> map = ConfigurationHelper.readSection("mySection", r);
        Writer w = null;
        boolean b = ConfigurationHelper.writeSection("mySection", map, w);
        assertEquals(true, b);
    }

}

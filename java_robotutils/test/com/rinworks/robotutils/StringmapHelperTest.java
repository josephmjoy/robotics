package com.rinworks.robotutils;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

class StringmapHelperTest {

    @Test
    void testSimpleUsage() {
        Map<String, String> emptyMap = new HashMap<String, String>();
        StringmapHelper empthSmh = new StringmapHelper(emptyMap);
        Pattern pat = Pattern.compile("\\w+"); // alnum and _

        // Checks against empty map - should return defaults
        assertEquals("bar", empthSmh.getAsString("foo", "bar"));
        assertEquals(true, empthSmh.getAsBoolean("foo", true));
        assertEquals(1, empthSmh.getAsInt("foo", 1));
        assertEquals(1, empthSmh.getAsLong("foo", 1), 1);
        double dd = 1.0; // double equality check is finicky
        assertEquals(dd, empthSmh.getAsDouble("foo", dd));

    }

}

package com.rinworks.robotutils;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

class StringmapHelperTest {

    final Pattern REGEX_VALIDNAME = Pattern.compile("\\w+");
    final String STRING_DEFAULT = "WOAH!!!";

    final int INT_MIN = -10;
    final int INT_MAX = 10;
    final int INT_DEFAULT = INT_MAX * 10;

    final int LONG_MIN = -100;
    final int LONG_MAX = 100;
    final int LONG_DEFAULT = LONG_MAX * 10;
    final double DOUBLE_MIN = -1.0;
    final double DOUBLE_MAX = 1.0;
    final double DOUBLE_DEFAULT = DOUBLE_MAX * 10;

    class KVInfo {
        final String k;
        final String v;
        final int intVal;
        final long longVal;
        final double doubleVal;
        final boolean isInt;
        final boolean isLong;
        final boolean isDouble;

        KVInfo(String k, String v) {
            this.k = k;
            this.v = v;
            this.isInt = this.isLong = this.isDouble = false;
            this.doubleVal = this.longVal = this.intVal = 0;
        }

        KVInfo(String k, int v) {
            this.k = k;
            this.v = "" + v;
            this.isInt = this.isLong = this.isDouble = true;
            this.doubleVal = this.longVal = this.intVal = v;
        }

        KVInfo(String k, long v) {
            this.k = k;
            this.v = "" + v;
            this.isInt = this.isLong = this.isDouble = true;
            this.doubleVal = this.longVal = v;
            this.intVal = (int) v;
            assert (long) this.intVal == v;
        }

        KVInfo(String k, double v) {
            this.k = k;
            String tv = "" + v;
            // Make sure it has a decimal point so it doesn't parse as int or long...
            if (tv.indexOf('.') < 0) {
                tv += ".0";
            }
            this.v = tv;
            this.isInt = this.isLong = false;
            this.isDouble = true;
            this.doubleVal = v;
            this.longVal = this.intVal = 0;
        }
    }

    ArrayList<KVInfo> buildRandomInput(int count) {
        ArrayList<KVInfo> randomInput = new ArrayList<KVInfo>();
        for (int i = 0; i < count; i++) {
            String key = "key" + i;
            int k = (int) (Math.random() * 4);
            if (k == 0) {
                // String. The following can't have any embedded digits, so substrings can never
                // be valid ints, longs or doubles. Also the two can't have overlapping
                // character sets,
                // and only goodS must pass the string validity test.
                String goodS = "jfAGsrhGGhAhafHaahwHghqgQghqh";
                String badS = "!@#$%^&*)&^#**%&%*@(*&*&*&*&!#%^*";
                String s = (Math.random() < 0.5) ? goodS : badS;
                assert REGEX_VALIDNAME.matcher(goodS).matches();
                assert !REGEX_VALIDNAME.matcher(badS).matches();
                String rs = s.substring((int) (Math.random() * s.length()));

                KVInfo kvi = new KVInfo(key, rs);
                randomInput.add(kvi);
            } else if (k == 1) {
                // int
                int ri = (int) (2 * (INT_MIN + Math.random() * (INT_MAX - INT_MIN)));
                KVInfo kvi = new KVInfo(key, ri);
                randomInput.add(kvi);
            } else if (k == 2) {
                // long
                long rl = (long) (2 * (LONG_MIN + Math.random() * (LONG_MAX - LONG_MIN)));
                KVInfo kvi = new KVInfo(key, rl);
                randomInput.add(kvi);
            } else if (k == 3) {
                // double
                double dl = 2 * (DOUBLE_MIN + Math.random() * (DOUBLE_MAX - DOUBLE_MIN));
                KVInfo kvi = new KVInfo(key, dl);
                randomInput.add(kvi);
            }
        }
        return randomInput;
    }

    Map<String, String> buildRandomMap(ArrayList<KVInfo> infos) {
        HashMap<String, String> hm = new HashMap<>();
        for (KVInfo kvi : infos) {
            hm.put(kvi.k, kvi.v);
        }
        return hm;
    }

    @Test
    void testEmptyMap() {
        Map<String, String> emptyMap = new HashMap<String, String>();
        StringmapHelper empthSmh = new StringmapHelper(emptyMap);

        // Checks against empty map - should return defaults
        assertEquals("bar", empthSmh.getAsString("foo", "bar"));
        assertEquals(true, empthSmh.getAsBoolean("foo", true));
        assertEquals(1, empthSmh.getAsInt("foo", 1));
        assertEquals(1, empthSmh.getAsLong("foo", 1), 1);
        double dd = 1.0; // double equality check is finicky
        assertEquals(dd, empthSmh.getAsDouble("foo", dd));

        // Same deal, with the more complex versions...
        assertEquals("bar", empthSmh.getAsString("foo", REGEX_VALIDNAME, "bar"));
        assertEquals(1, empthSmh.getAsInt("foo", 0, 100, 1));
        assertEquals(1, empthSmh.getAsLong("foo", 0, 100, 1), 1);
        assertEquals(dd, empthSmh.getAsDouble("foo", -1.0, 1.0, dd));
    }

    @Test
    void bigTest() {
        ArrayList<KVInfo> infos = buildRandomInput(20);
        Map<String, String> smap = buildRandomMap(infos);
        StringmapHelper smh = new StringmapHelper(smap);

        for (KVInfo kvi : infos) {
            runGauntlet(smh, kvi);
        }
    }

    void runGauntlet(StringmapHelper smh, KVInfo kvi) {

        // Testing getAsString...
        String gotV = smh.getAsString(kvi.k, REGEX_VALIDNAME, STRING_DEFAULT);
        boolean validS = REGEX_VALIDNAME.matcher(kvi.v).matches();
        // System.out.println("STR: " + kvi.v + " -> " + gotV);
        if (validS) {
            assertEquals(gotV, kvi.v);
        } else {
            assertEquals(gotV, STRING_DEFAULT);
        }

        // Testing getAsInt... (can throw exception)
        int gotIntVal = smh.getAsInt(kvi.k, INT_MIN, INT_MAX, INT_DEFAULT);
        boolean validInt = kvi.isInt && kvi.intVal >= INT_MIN && kvi.intVal <= INT_MAX;
        // System.out.println("INT: " + kvi.v + " -> " + gotIntVal);
        if (validInt) {
            assertEquals(kvi.intVal, gotIntVal);
        } else {
            assertEquals(INT_DEFAULT, gotIntVal);
        }

        // Testing getAsLong... (can throw exception)
        long gotLongVal = smh.getAsLong(kvi.k, LONG_MIN, LONG_MAX, LONG_DEFAULT);
        boolean validLong = kvi.isLong && kvi.longVal >= LONG_MIN && kvi.longVal <= LONG_MAX;
        // System.out.println("LONG: " + kvi.v + " -> " + gotLongVal);
        if (validLong) {
            assertEquals(kvi.longVal, gotLongVal);
        } else {
            assertEquals(LONG_DEFAULT, gotLongVal);
        }

        // Testing getAsDouble... (can throw exception)
        double gotDoubleVal = smh.getAsDouble(kvi.k, DOUBLE_MIN, DOUBLE_MAX, DOUBLE_DEFAULT);
        boolean validDouble = kvi.isDouble && kvi.doubleVal >= DOUBLE_MIN && kvi.doubleVal <= DOUBLE_MAX;
        // System.out.println("DBL: " + kvi.v + " -> " + gotDoubleVal);
        if (validDouble) {
            assertEquals(kvi.doubleVal, gotDoubleVal);
        } else {
            assertEquals(DOUBLE_DEFAULT, gotDoubleVal);
        }

    }
}

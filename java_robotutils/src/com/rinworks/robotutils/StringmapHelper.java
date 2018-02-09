//
// Utility class for converting from Map<String,String> to other representations.
// Created by Joseph M. Joy (https://github.com/josephmjoy)
//
package com.rinworks.robotutils;

import java.util.Map;
import java.util.regex.Pattern;

public class StringmapHelper {

    private final Map<String, String> map;

    public StringmapHelper(Map<String, String> map) {
        this.map = map;
    }

    public String getAsString(String key, String defaultValue) {
        String value = map.get(key);
        if (value != null) {
            return value;
        } else {
            return defaultValue;
        }
    }

    /**
     * Returns an String - either parsed from map of {key} or {defaultValue}.
     * 
     * @param key
     *            - key to lookup.
     * @param pattern
     *            - A Regex.Pattern object representing valid strings to accept.
     * @param defaultValue
     *            - default value to use if the key did exist, the value was not
     *            parseable or did not match {pattern}. This value does not need
     *            match {pattern}.
     * @return
     */
    public String getAsString(String key, Pattern pattern, String defaultValue) {
        String v = this.getAsString(key, defaultValue);
        return pattern.matcher(v).matches() ? v : defaultValue;
    }

    public boolean getAsBoolean(String key, boolean defaultValue) {
        String value = map.get(key);
        if (value != null) {
            if (value.equalsIgnoreCase("true")) {
                return true;
            } else if (value.equalsIgnoreCase("false")) {
                return false;
            }
        }
        return defaultValue;
    }

    public int getAsInt(String key, int defaultValue) {
        int v = defaultValue;
        String value = map.get(key);
        if (value != null) {
            try {
                v = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                // Keep default value.
            }
        }
        return v;
    }

    /**
     * Returns an integer - either parsed from map of {key} or {defaultValue}.
     * 
     * @param key
     *            - key to lookup.
     * @param min
     *            - inclusive minimum to accept.
     * @param max
     *            - inclusive (not exclusive) maximum to accept.
     * @param defaultValue
     *            - default value to use if the key did exist, the value was not
     *            parseable or out of bounds. This value does not need to be between
     *            {min} and {max}
     * @return
     */
    public int getAsInt(String key, int min, int max, int defaultValue) {
        int v = this.getAsInt(key, defaultValue);
        return (v >= min && v <= max) ? v : defaultValue;
    }

    public long getAsLong(String key, long defaultValue) {
        long v = defaultValue;
        String value = map.get(key);
        if (value != null) {
            try {
                v = Long.parseLong(value);
            } catch (NumberFormatException e) {
                // Keep default value.
            }
        }
        return v;
    }

    /**
     * Returns a long - either parsed from map of {key} or {defaultValue}.
     * 
     * @param key
     *            - key to lookup.
     * @param min
     *            - inclusive minimum to accept.
     * @param max
     *            - inclusive (not exclusive) maximum to accept.
     * @param defaultValue
     *            - default value to use if the key did exist, the value was not
     *            parseable or out of bounds. This value does not need to be between
     *            {min} and {max}
     * @return
     */
    public long getAsLong(String key, long min, long max, long defaultValue) {
        long v = this.getAsLong(key, defaultValue);
        return (v >= min && v <= max) ? v : defaultValue;
    }

    public double getAsDouble(String key, double defaultValue) {
        double v = defaultValue;
        String value = map.get(key);
        if (value != null) {
            try {
                v = Double.parseDouble(value);
            } catch (NumberFormatException e) {
                // Keep default value.
            }
        }
        return v;
    }

    /**
     * Returns a double - either parsed from map of {key} or {defaultValue}.
     * 
     * @param key
     *            - key to lookup.
     * @param min
     *            - minimum to accept.
     * @param max
     *            - maximum to accept.
     * @param defaultValue
     *            - default value to use if the key did exist, the value was not
     *            parseable or out of bounds. This value does not need to be between
     *            {min} and {max}
     * @return
     */
    public double getAsDouble(String key, double min, double max, double defaultValue) {
        double v = this.getAsDouble(key, defaultValue);
        return (v >= min && v <= max) ? v : defaultValue;
    }

}

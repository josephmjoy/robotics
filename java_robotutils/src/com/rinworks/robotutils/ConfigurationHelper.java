//
// Utilities for reading configuration data.
// Created by Joseph M. Joy (https://github.com/josephmjoy)
//
package com.rinworks.robotutils;

import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

public class ConfigurationHelper {

    /**
     * Loads the specified section from the specified reader. It will not throw an
     * exception. On error (IO exception or not being able to find the section) it
     * will return an empty map.
     * 
     * @return map of keys to values for that specified section.
     */
    public static Map<String, String> readSection(String sectionName, Reader r) {
        HashMap<String, String> hm = new HashMap<>();
        return hm;
    }

    /**
     * Saves the specified section to the specified writer starting at the current
     * point in the writer. It will not throw an exception. On error (IO exception
     * or not being able to write the section) it will return false. WARNING: It can
     * not scan the destination to see if this section has already been written, so
     * typically this method is called when writing out an entire configuration with
     * multiple sections in sequence.
     * 
     * @return true on success and false on failure.
     */
    public static boolean writeSection(String sectionName, Map<String, String> map, Writer w) {
        return true;
    }

    // To disallow public instance creation.
    private ConfigurationHelper() {
        assert false;
    }

}

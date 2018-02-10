//
// Utilities for reading configuration data.
// Created by Joseph M. Joy (https://github.com/josephmjoy)
//
package com.rinworks.robotutils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class ConfigurationHelper {

    private static final String COLONSPACE = ": "; // space after : is MANDATORY.
    private static final Pattern REGEX_WHITESPACE = Pattern.compile("\\s");

    /**
     * Loads the specified section from the specified reader. It will not throw an
     * exception. On error (IO exception or not being able to find the section) it
     * will return an empty map.
     * 
     * @return map of keys to values for that specified section.
     */
    public static Map<String, String> readSection(String sectionName, Reader r, List<String> keys) {
        HashMap<String, String> hm = new HashMap<>();
        if (keys != null) {
            keys.clear();
        }
        try (BufferedReader br = new BufferedReader(r)) {
            if (findSection(sectionName, br)) {
                processSection(br, hm, keys);
            }
        } catch (IOException e) {
            // Nothing to do as br will be automatically closed.
           System.err.println(e);
        }
        return hm;
    }

    private static void processSection(BufferedReader br, HashMap<String, String> hm, List<String> keys)
            throws IOException {

        int indentation = -1; // we will set it when we find the first child of the section.
        String line;
        while ((line = br.readLine()) != null && !line.startsWith("...")) {
            line = trimComment(line);
            boolean validKV = false;
            String pre = "";
            String post = "";

            if (line.length() > 0 && !REGEX_WHITESPACE.matcher(line).matches()) {

                int iColon = line.indexOf(COLONSPACE);
                if (iColon > 0) {
                    pre = line.substring(0, iColon).trim();
                    post = line.substring(iColon + 1).trim(); // +1 for space after colon
                } else {
                    // The other case is the line *ends* in a colon...
                    int lastIndex = line.length()-1;
                    if (line.charAt(lastIndex) == ':') {
                        pre = line.substring(0, lastIndex).trim();
                        post = "";
                    }
                }

                if (pre.length() > 0) {

                    int thisIndentation = line.indexOf(pre);
                    if (indentation < 0) {
                        indentation = thisIndentation;
                        assert indentation >= 0;
                    }

                    if (indentation < 1 || thisIndentation < indentation) {
                        // We are done because indentation level has popped up.
                        break;
                    }

                    validKV = indentation == thisIndentation && post.length() > 0;
                }
            }

            if (validKV) {
                hm.put(pre, post);
                if (keys != null) {
                    keys.add(pre);
                }
            }
        }
    }

    private static boolean findSection(String sectionName, BufferedReader br) throws IOException {
        String line;

        if (sectionName == null || sectionName.indexOf(' ') >= 0 || sectionName.indexOf(':') >= 0
                || sectionName.indexOf('\t') >= 0) {
            // Bogus section name. Note that colons are not allowed within section names,
            // though that
            // is valid YAML. This is because this is a configuration reader, not general
            // YAML parser.
            return false; // ******************** EARLY RETURN *******************
        }

        boolean ret = false;
        while ((line = br.readLine()) != null && !line.startsWith("...")) {
            if (matchesSectionName(line, sectionName)) {
                String remaining = line.substring(sectionName.length());
                remaining = trimComment(remaining).trim();
                int iColon = remaining.indexOf(':');
                if (iColon >= 0) {
                    String preColon = remaining.substring(0, iColon).trim();
                    String postColon = remaining.substring(iColon+1).trim();
                    ret = preColon.length() == 0 && postColon.length() == 0;
                }
                break;
            }
            // doesn't match, keep looking...
        }
        return ret;
    }

    private static boolean matchesSectionName(String line, String sectionName) {
        if (!line.startsWith(sectionName)) {
            return false;
        }

        // Line STARTS with {sectionName} - promising, but perhaps it's a prefix of
        // a longer section name or something badly garbled...
        if (line.length() > sectionName.length()) {
            char c = line.charAt(sectionName.length()); // char right after sectioName
            if ("\t :#".indexOf(c) < 0) {
                // 1st post char could be part of a longer section name, so let's just keep
                // looking
                return false;
            }
        }

        return true;
    }

    private static String trimComment(String s) {
        int iComment = s.indexOf('#');
        if (iComment >= 0) {
            s = s.substring(0, iComment);
        }
        return s;
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
    public static boolean writeSection(String sectionName, Map<String, String> map, List<String> keys, Writer w) {
        Iterator<String> kIter = keys != null ? keys.iterator() : map.keySet().iterator();
        boolean ret = false;
        
        try (BufferedWriter bw = new BufferedWriter(w)) {
            bw.write(sectionName + ":\n");
            while (kIter.hasNext()) {
                String k = kIter.next();
                String v = map.get(k);
                if (v != null) {
                       String output = "  " + k + COLONSPACE + v + "\n";
                       bw.write(output);
                }
            }
            ret = true;
        } catch (IOException e) {
            // Nothing to do as we close br on exception or not.
        }
        
        return ret;
    }

    // To disallow public instance creation.
    private ConfigurationHelper() {
        assert false;
    }

}

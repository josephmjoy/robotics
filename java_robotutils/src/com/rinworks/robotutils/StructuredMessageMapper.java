package com.rinworks.robotutils;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Maps a string key-value pairs to a HashMap and vice versa.
// Example string: "key1:value1 key2 :value2  key3 : value3", produces mapping
// (("key1", "value1"), ("key2", "value2"), ("key3", "value3")).
// Note that boundry whitespace is trimmed for both keys and values.
//
// Keys are extracted first - they are the first block of non-whitespace characters that precede the
// ':'. The values are extracted next. Values can contain anything other than the ':' character.
// Escaping ':' in values is not supported at this time. The recommendation is to replace ':' by '='
// in value text.
// There are only two possible errors:
// 1: There is no ':' in the entire string. This results in an empty hashmap.
// 2: There may not be a key preceding a ':'. In this case the corresponding value is ignored.
// For example "a::b" has one key "a" with empty value, but it then has an empty key with value "b".
// This "b" is ignored. The resulting mapping is:
// (("a", ""))
// If there is an error, the special key "_errors" is either created or appended with a suitable
// error message.
public class StructuredMessageMapper {

	public static final char COLON = ':'; // Separates keys and values.
	public static final String ERRORS_KEY = "_errors";
	static final Pattern COLON_PAT = Pattern.compile("" + COLON);
	static final Pattern KEY_PAT = Pattern.compile("\\S+\\s*$");

	public static Map<String, String> toMap(String msg) {
		String[] parts = COLON_PAT.split(msg, -1); // -1 to keep all trailing empty strings
		int n = parts.length;
		assert (n >= 1); // Even with an empty message, we should get one part.
		int pairs = n - 1; // Also equal to the number of colons.
		HashMap<String, String> map = new HashMap<>();

		if (pairs <= 0) {
			// No colons, so no key-value pairs.

			if (msg.length() > 0) {
				map.put(ERRORS_KEY, "no keys");
			}
			return map; // ****** EARLY RETURN **********
		}

		String prevKey = "";
		boolean emptyKey = false;
		boolean duplicateKey = false;
		for (int i = 0; i <= pairs; i++) {
			String pre = parts[i];
			String value = null;
			String key = "";
			if (i < pairs) {
				Matcher m = KEY_PAT.matcher(pre);
				if (m.find()) {
					int kStart = m.start();
					value = pre.substring(0, kStart).trim();
					key = pre.substring(kStart).trim();
					assert key.length() > 0;
				} else {
					// Hmm, empty key
					emptyKey = true;
				}
			}
			if (value == null) {
				value = pre.trim();
			}

			if (prevKey.length() > 0) {
				if (map.containsKey(prevKey)) {
					// Hmm, duplicate key
					duplicateKey = true;
				} else {
					map.put(prevKey, value);
				}
			}
			prevKey = key;
		}

		if (emptyKey || duplicateKey) {
			String errors = map.getOrDefault(ERRORS_KEY, "");
			errors += (emptyKey ? "; empty key(s)" : "") + (duplicateKey ? "; duplicate key(s)" : "");
			map.put(ERRORS_KEY, errors);
		}

		return map;
	}

	// Creates a string representation of the key-value map.
	// WARNING - there is no order to how the mapping is serialized.
	// If order is important, use the second overloaded method.
	public static String toString(Map<String, String> map) {
		Set<String> keySet = map.keySet();
		String[] keys = keySet.toArray(new String[0]);
		return toString(map, keys);
	}

	//
	public static String toString(Map<String, String> map, String[] keys) {
		StringBuilder sb = new StringBuilder();
		String pre = "";
		for (String key : keys) {
			if (map.containsKey(key)) {
				String value = map.get(key);
				sb.append(pre + key + COLON + value);
				pre = " ";
			}
		}
		return sb.toString();
	}
	
	// Bogus private constructor to prevent instances from being created.
	private StructuredMessageMapper() {
	    assert false;
	}
}

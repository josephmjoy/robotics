package robotutils;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumingThat;

import java.util.HashMap;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StructuredMessageMapperTest {


	@BeforeAll
	static void setUpBeforeClass() throws Exception {
	}

	@AfterAll
	static void tearDownAfterClass() throws Exception {
	}

	@BeforeEach
	void setUp() throws Exception {
	}

	@AfterEach
	void tearDown() throws Exception {
	}

	@Test
	void testEmptyStringToMap() {
		HashMap<String, String> map = StructuredMessageMapper.toHashMap("");
		Set<String> keys = map.keySet();
		assertEquals(keys.size(), 0);
	}
	
	@Test
	void testEmptyMapToString() {
		HashMap<String, String> map = new HashMap<String, String>();
		String s = StructuredMessageMapper.toString(map);
		assertEquals(s.length(), 0);
	}

	@Test
	void testSingletonStringToMap() {
		String input = "k:v";
		HashMap<String, String> map = StructuredMessageMapper.toHashMap(input);
		Set<String> keys = map.keySet();
		assertEquals(keys.size(), 1);
		assertEquals(map.get("k"), "v");
	}
	
	@Test
	void testSingletonMapToString() {
		HashMap<String, String> map = new HashMap<String, String>();
		map.put("k",  "v");
		String s = StructuredMessageMapper.toString(map);
		assertEquals(s, "k:v");
	}
	
	@Test
	void testSimpleStringToMap() {
		String input = "k1:v1 k2:v2 k3:v3";
		HashMap<String, String> map = StructuredMessageMapper.toHashMap(input);
		Set<String> keys = map.keySet();
		assertEquals(keys.size(), 3);
		assertEquals(map.get("k1"), "v1");
		assertEquals(map.get("k2"), "v2");
		assertEquals(map.get("k3"), "v3");
	}
	
	@Test
	void testSimpleMapToString() {
		HashMap<String, String> map = new HashMap<String, String>();
		map.put("k1",  "v1");
		map.put("k2",  "v2");
		map.put("k3",  "v3");
		String[] keys = {"k1", "k2", "k3"};
		String s = StructuredMessageMapper.toString(map, keys);
		assertEquals(s, "k1:v1 k2:v2 k3:v3");
	}
}

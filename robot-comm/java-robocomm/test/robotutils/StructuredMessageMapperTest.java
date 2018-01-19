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
}

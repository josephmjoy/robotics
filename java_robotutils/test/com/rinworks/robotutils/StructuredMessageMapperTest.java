//
// JUnit tests for roboutils.
// Created by Joseph M. Joy (https://github.com/josephmjoy)
//
package com.rinworks.robotutils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rinworks.robotutils.StructuredMessageMapper;

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
	
	@Test
	void testMoreComplexMappings() {
		String[] keys = {
				"!#!@AB89.[],/-+",
				"2f0j20j0j",
				"13r2ffs,,,,,,",
				"-n339ghhss8898v",
				"d"
		};
		
		String[] values = {
				"13r \n\t \r doo bE B",
				"waka waka waka",
				"What's all the fuss?!",
				"(a=3.5, b=4.5, c=11.2)",
				"\"some quoted string\""
		};
		
		
		assert(keys.length == values.length);
		
		StringBuilder sb = new StringBuilder();
		StringBuilder sbClean = new StringBuilder();
		String pre = "";
		for (int i = 0; i< keys.length; i++) {
			String k = keys[i];
			String v = values[i];
			// Make sure our test data is clean - no edge whitespace.
			assert(k.equals(k.trim()));
			assert(v.equals(v.trim()));
			String space = randomWhitespace();
			sb.append(space);
			
			sbClean.append(pre);
			sbClean.append(k);
			pre = " ";
			sb.append(k);
			space = randomWhitespace();
			sb.append(space);
			
			sb.append(":");
			sbClean.append(":");
			
			space = randomWhitespace();
			sb.append(space);
			
			sb.append(v);
			sbClean.append(v);
			
			space = randomWhitespace();
			sb.append(space);
		}
		String input = sb.toString();
		String cleanInput = sbClean.toString();
		
		HashMap<String, String> map = StructuredMessageMapper.toHashMap(input);
		
		// Verify map contents.
		for (int i=0; i<keys.length; i++) {
			String k = keys[i];
			String v = values[i];
			assertEquals(map.get(k), v);
		}
		
		// Convert back to string
		String outputMessage = StructuredMessageMapper.toString(map, keys);
		assertEquals(outputMessage, cleanInput);
	}
	
	// Raturns a random amount of "random" whitespace.
	String randomWhitespace() {
		String randomWhitespace = "    \t\t    \n\r      ";
		return randomWhitespace.substring((int)(Math.random()*randomWhitespace.length()));
	}
}

/**
 * 
 */
package com.rinworks.robotutils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rinworks.robotutils.StructuredLogger;
import com.rinworks.robotutils.StructuredMessageMapper;

/**
 * @author josephj
 *
 */
class StructuredLoggerTest {

	StructuredLogger bossLogger;
	boolean assertionHandlerCalled;
	MyRawLog[] rawLoggers;
	final String ROOT_LOG_NAME = "ROOT LOG"; // Make sure it doesn't have beginning or ending whitespace, and no colons.
	
	
	class MyRawLog implements StructuredLogger.RawLogger {
		final String logName;
		boolean newSessionCalled;
		boolean logCalled;
		boolean flushCalled;
		boolean closeCalled;

		String sessionId;

		// Saved after each log message call.
		String msgMsg;

		String assertionFailureString;

		MyRawLog(String name) {
			logName = name;
		}

		private void clearLoggedMsg() {
			logCalled=false;
			msgMsg = null;
		}

		@Override
		public void beginSession(String _sessionId) {
			assertFalse(newSessionCalled);
			newSessionCalled = true;
			sessionId = _sessionId;
		}

		@Override
		public void log(String msg) {
			assertTrue(newSessionCalled);
			assertFalse(closeCalled);
			logCalled = true;
			msgMsg = msg;
			if (logName.equals("file")) {
				System.out.println("raw message:" + msg);
			}
		}

		@Override
		public void flush() {
			assertTrue(newSessionCalled);
			flushCalled = true;
		}


		@Override
		public void close() {
			assertTrue(newSessionCalled);
			assertFalse(closeCalled);
			assertTrue(flushCalled);
			closeCalled=true;			
		}

	}

	/**
	 * @throws java.lang.Exception
	 */
	@BeforeAll
	static void setUpBeforeClass() throws Exception {
	}

	/**
	 * @throws java.lang.Exception
	 */
	@AfterAll
	static void tearDownAfterClass() throws Exception {
	}

	/**
	 * @throws java.lang.Exception
	 */
	@BeforeEach
	void setUp() throws Exception {	

	}



	/**
	 * @throws java.lang.Exception
	 */
	@AfterEach
	void tearDown() throws Exception {

	}


	private void setUpBossLogger() {	
		assert(rawLoggers==null);
		rawLoggers = new MyRawLog[]{new MyRawLog("file"), new MyRawLog("network")};
		assert(bossLogger == null);
		bossLogger = new StructuredLogger(rawLoggers, ROOT_LOG_NAME);
		bossLogger.setAsseretionFailureHandler(s -> {
			assertFalse(assertionHandlerCalled);
			assertionHandlerCalled = true;
		});

		bossLogger.beginLogging();
		verifyBeginSessionState();
		clearLoggedMessages(); // messages are logged when a session is started.
	}



	/**
	 * @throws java.lang.Exception
	 */

	private void tearDownBossLogger() {
		clearLoggedMessages();
		bossLogger.endLogging();
		verifyEndSessionState();
		bossLogger = null;
		rawLoggers = null;
	}



	private void clearLoggedMessages() {
		for (MyRawLog rl: rawLoggers) {
			rl.clearLoggedMsg();
		}

	}

	private void verifyBeginSessionState() {
		// Check that the session beginning has been logged
		for (MyRawLog rl: rawLoggers) {
			assertTrue(rl.logCalled);
			verifySessionMessage(rl.msgMsg, true); // true == start
		}

	}


	private void verifyMessageTag(String tagKey, Consumer<String> verifier) {
		for (MyRawLog rl: rawLoggers) {
			assertTrue(rl.logCalled);
			HashMap<String, String> hm = StructuredMessageMapper.toHashMap(rl.msgMsg);
			String tagValue = hm.get(tagKey); // Will be null if the tag doesn't exist	
			verifier.accept(tagValue);
		}
	}

	// Verify the state of bossLogger after the session has been ended.
	private void verifyEndSessionState() {
		StructuredLogger.Log rootLog = bossLogger.defaultLog();
		for (MyRawLog rl: rawLoggers) {
			assertTrue(rl.flushCalled);
			assertTrue(rl.closeCalled);

			// Check that the endSession message has been logged.
			assertTrue(rl.logCalled);
			verifySessionMessage(rl.msgMsg, false); // false == stop

			rl.flushCalled=false;
			rl.closeCalled=false;
			rl.clearLoggedMsg();
		}
		assertionHandlerCalled = false;

		// Now let's verify that doing various things with bossLogger does NOT call down to the raw loggers.
		rootLog.info("TEST");
		rootLog.flush();
		rootLog.info("Hello!");
		rootLog.loggedAssert(false,  "Foo");
		assertTrue(assertionHandlerCalled); // even after session closing, handler should be called.
		for (MyRawLog rl: rawLoggers) {
			assertFalse(rl.flushCalled);
			assertFalse(rl.closeCalled);
			assertFalse(rl.logCalled);
		}
	}


	// Verify that the right message was logged when a session has started or (if {start} is false) has ended.
	private void verifySessionMessage(String msg, boolean start) {
		HashMap<String, String> map = StructuredMessageMapper.toHashMap(msg);
		String mPri = map.getOrDefault(StructuredLogger.Log.PRI, "bad");
		String mCat = map.getOrDefault(StructuredLogger.Log.CAT, "bad");
		String mType = map.getOrDefault(StructuredLogger.Log.TYPE, "bad");
		String rootName = map.getOrDefault("rootName", "bad");
		assertEquals(mPri, ""+StructuredLogger.PRI0);
		assertEquals(mCat, StructuredLogger.INFO);
		String expectedType = start ? StructuredLogger.Log.LOG_SESSION_START : StructuredLogger.Log.LOG_SESSION_END;
		assertEquals(mType, expectedType);
		// We must find the session description on the message part.
		assertTrue(rootName.equals(ROOT_LOG_NAME));
	}

	@Test
	void testBeginEndSession() {
		setUpBossLogger();
		tearDownBossLogger();

	}

	@Test
	void testSimplestLogUsage() {
		setUpBossLogger();
		bossLogger.info("test logging message");
		bossLogger.err("this is an error");
		bossLogger.flush();
		tearDownBossLogger();
	}
	
	@Test
	void testSimpleLogUsage() {
		setUpBossLogger();
		StructuredLogger.Log log1 = bossLogger.defaultLog();
		
		// Basic logging
		log1.info("test logging message");
		log1.err("this is an error");
		log1.warn("this is a warning");
		log1.trace("this is a trace");
		
		// Assertion check - passing and failing
		log1.loggedAssert(true, "unexpectedly, log1 is null.");
		assertFalse(assertionHandlerCalled);
		log1.loggedAssert(false, "unexpectedly, log1 is null.");
		assertTrue(assertionHandlerCalled);
		assertionHandlerCalled = false;
		
		// Creating a new log object and logging to it.
		StructuredLogger.Log log2 = log1.newLog("DRIVE");
		log2.info("Hi!");
		
		tearDownBossLogger();
	}
	
	@Test
	// RTS == relative timestamps
	void testRTS() {
		setUpBossLogger();
		StructuredLogger.Log log1 = bossLogger.defaultLog();
		
		// Log without RTS and verify that the _rts tag is not inserted.
		log1.info("message1");
		this.verifyMessageTag(StructuredLogger.Log.RELATIVE_TIMESTAMP, rtsValue -> {
			assertTrue(rtsValue == null);		    
		});
		
		// Turn on RTS and verify that there is an RTS value
		log1.startRTS();
		log1.info("message2");
		this.verifyMessageTag(StructuredLogger.Log.RELATIVE_TIMESTAMP, rtsValue -> {
			assertTrue(rtsValue != null);
			long time = Long.parseLong(rtsValue);
			assertTrue(time >= 0);
		});
		
		// Turn RTS off and verify that once again _rts tags are not inserted.
		log1.stopRTS();
		log1.info("message1");
		this.verifyMessageTag(StructuredLogger.Log.RELATIVE_TIMESTAMP, rtsValue -> {
			assertTrue(rtsValue == null);		    
		});
		
		tearDownBossLogger();
	}

	@Test
	// Tags - Dynamic tags that are inserted into every logged message.
	void testTags() {
		setUpBossLogger();
		StructuredLogger.Log log = bossLogger.defaultLog();
		String[] tags = {"TAG1", "TAG2", "TAG3"};
		String[] values = {"", "hello", "a=b c=d"};
		assert(tags.length == values.length);
		
		// Add a few tags and very that they show up in the next logged message.
		
		for (int i=0; i< tags.length; i++) {
			log.addTag(tags[i], values[i]);
		}
		log.info("some random message with data a:b c:d");
		
		for (int i=0; i< tags.length; i++) {
			final String vi = values[i];
			this.verifyMessageTag(tags[i], val -> {
				assertEquals(vi, val);		    
			});
		}
		
		// Now remove them all and check we don't find any of them
		
		for (int i=0; i< tags.length; i++) {
			log.removeTag(tags[i]);
		}
		clearLoggedMessages();
		
		log.info("some other random message with data foo:bar");
		
		for (int i=0; i< tags.length; i++) {
			this.verifyMessageTag(tags[i], val -> {
				assertTrue(val == null);		    
			});
		}
		
		// Let's remove a value that doesn't exist.
		log.removeTag("strange");
		
		tearDownBossLogger();
	}
	
	
	@Test
	void testFileRawLogger1() throws IOException {

		// Create a temporary file
		File path = File.createTempFile("testLog", ".txt");
		path.deleteOnExit();	// So tests don't leave stuff lying around.			

		StructuredLogger.RawLogger rawLog = StructuredLogger.createFileLogger(path, false); // false == do not append
		rawLog.beginSession("123");
		rawLog.log("Test raw message");
		rawLog.flush();
		rawLog.close();

		rawLog = StructuredLogger.createFileLogger(path, true); // true == append
		rawLog.beginSession("456");
		rawLog.log("Another test raw message");
		rawLog.flush();
		rawLog.close();
	}

	@Test
	void testFileRawLogger2()  {

		// Create a temporary file
		String tempDir = System.getProperty("java.io.tmpdir");
		File dirPath=  new File(tempDir);
		assert(dirPath.isDirectory());		

		StructuredLogger.RawLogger rawLog = StructuredLogger.createFileLogger(dirPath, "testLog", ".txt", false); // false==don't append
		rawLog.beginSession("123");
		rawLog.log("Test raw message");
		rawLog.flush();
		rawLog.close();

		rawLog = StructuredLogger.createFileLogger(dirPath, "testLog", ".txt", true); // true==append
		rawLog.beginSession("456");
		rawLog.log("Another test raw message");
		rawLog.flush();
		rawLog.close();
	}

	@Test
	void testUDPRawLogger() throws SocketException, InterruptedException {	
		final int PORT = 9876;
		ConcurrentLinkedQueue<String> receivedMessageQueue = new ConcurrentLinkedQueue<String>();
		StructuredLogger.RawLogger rawLog = StructuredLogger.createUDPLogger("localhost", PORT); // false==don't append
		DatagramSocket serverSocket = new DatagramSocket(PORT);
		
		setupToReceiveUDPMessage(serverSocket, receivedMessageQueue);
		//Thread.sleep(500); // Wait 
		String[] sendMsgs = {
				"Test raw message 1",
				"Test raw message 2",
				"Test raw message 3"
		};
		rawLog.beginSession("123");
		for (String msg: sendMsgs) {
			rawLog.log(msg );
		}
		rawLog.flush();
		rawLog.close();

		// Now we wait for our message(s)
		String[] receivedMsgs = receiveTestMessages(sendMsgs.length, receivedMessageQueue);
		validateMessages(sendMsgs, receivedMsgs);

	}
	
	private void validateMessages(String[] sendMsgs, String[] receivedMsgs) {
		assertEquals(sendMsgs.length, receivedMsgs.length);
		int matchedMessages = 0;
		
		// This is o(n^2) and also assumes sent messages are all different, but
		// it will suffice.
		for (String msg:sendMsgs) {
			for (String msg1: receivedMsgs) {
				if (msg.equals(msg1)) {
					matchedMessages++;
				}
			}
		}
		assertEquals(sendMsgs.length, matchedMessages);
	}

	private void setupToReceiveUDPMessage(DatagramSocket serverSocket, ConcurrentLinkedQueue<String> receivedMessageQueue) {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		executor.submit(() -> {

			try {
				while(true) {
					
					byte[] receiveData = new byte[1024];
					DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
					System.out.println("Waiting to receive UDP packet...");
					serverSocket.receive(receivePacket);
					System.out.println("Received packet.");
					String msg = new String(receivePacket.getData(), 0, receivePacket.getLength());
					receivedMessageQueue.add(msg);

				}
			}
			catch (IOException e) {
				System.err.println("IO Exception " + e);
			}
		});

	}

	// Receives count messages from the queue
	private String[] receiveTestMessages(int count, ConcurrentLinkedQueue<String> receivedMessageQueue) throws InterruptedException {
		String[]messages = new String[count];
		int i = count-1;
		while (i>=0) {
			String msg = receivedMessageQueue.poll();
			if (msg == null) {
				System.out.println("Waiting for more messages...");
				Thread.sleep(250);
			} else {
				System.out.println("Received message.");
				messages[i] = msg;
				i--;
			}
		}
		
		return messages;
	}

}

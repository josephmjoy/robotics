/**
 * 
 */
package robotutils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * @author josephj
 *
 */
class StructuredLoggerTest {

	StructuredLogger bossLogger;
	boolean assertionHandlerCalled;
	MyRawLogger[] rawLoggers;
	final String SESSION_DESCRIPTION = "TEST SESSION DESCRIPTION"; // Make sure it doesn't have beginning or ending whitespace, and no colons.
	
	
	class MyRawLogger implements StructuredLogger.RawLogger {
		final String logName;
		boolean newSessionCalled;
		boolean logCalled;
		boolean flushCalled;
		boolean closeCalled;

		String sessionId;

		// Saved after each log message call.
		int msgPri;
		String msgCat;
		String msgMsg;

		String assertionFailureString;

		MyRawLogger(String name) {
			logName = name;
		}

		private void clearLoggedMsg() {
			logCalled=false;
			msgPri = -1;
			msgCat = null;
			msgMsg = null;
		}

		@Override
		public void newSession(String _sessionId) {
			assertFalse(newSessionCalled);
			newSessionCalled = true;
			sessionId = _sessionId;
		}

		@Override
		public void log(int pri, String cat, String msg) {
			assertTrue(newSessionCalled);
			assertFalse(closeCalled);
			logCalled = true;
			msgPri = pri;
			msgCat = cat;
			msgMsg = msg;
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
		rawLoggers = new MyRawLogger[]{new MyRawLogger("file"), new MyRawLogger("network")};
		assert(bossLogger == null);
		bossLogger = new StructuredLogger(rawLoggers, "ROOT", s -> {
			assertFalse(assertionHandlerCalled);
			assertionHandlerCalled = true;
		});

		bossLogger.beginSession(SESSION_DESCRIPTION);
		verifyBeginSessionState();
		clearLoggedMessages(); // messages are logged when a session is started.
	}



	/**
	 * @throws java.lang.Exception
	 */

	private void tearDownBossLogger() {
		clearLoggedMessages();
		bossLogger.endSession();
		verifyEndSessionState();
		bossLogger = null;
		rawLoggers = null;
	}



	private void clearLoggedMessages() {
		for (MyRawLogger rl: rawLoggers) {
			rl.clearLoggedMsg();
		}

	}

	private void verifyBeginSessionState() {
		// Check that the session beginning has been logged
		for (MyRawLogger rl: rawLoggers) {
			assertTrue(rl.logCalled);
			verifySessionMessage(rl.msgPri, rl.msgCat, rl.msgMsg);
		}

	}



	// Verify the state of bossLogger after the session has been ended.
	private void verifyEndSessionState() {
		StructuredLogger.Logger rootLog = bossLogger.getRootLog();
		for (MyRawLogger rl: rawLoggers) {
			assertTrue(rl.flushCalled);
			assertTrue(rl.closeCalled);

			// Check that the endSession message has been logged.
			assertTrue(rl.logCalled);
			verifySessionMessage(rl.msgPri, rl.msgCat, rl.msgMsg);

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
		for (MyRawLogger rl: rawLoggers) {
			assertFalse(rl.flushCalled);
			assertFalse(rl.closeCalled);
			assertFalse(rl.logCalled);
		}
	}


	// Verify that the right message was logged when a session has been ended.
	private void verifySessionMessage(int pri, String cat, String msg) {
		HashMap<String, String> map = StructuredMessageMapper.toHashMap(msg);
		String mPri = map.getOrDefault(StructuredLogger.Logger.PRI, "bad");
		String mCat = map.getOrDefault(StructuredLogger.Logger.CAT, "bad");
		String mType = map.getOrDefault(StructuredLogger.Logger.TYPE, "bad");
		String _msgField = map.getOrDefault(StructuredLogger.Logger.DEF_MSG, "bad");
		assertEquals(mPri, ""+StructuredLogger.PRI0);
		assertEquals(mCat, StructuredLogger.INFO);
		assertEquals(mType, StructuredLogger.Logger.LOGGER);
		// We must find the session description on the message part.
		assertTrue(_msgField.indexOf(SESSION_DESCRIPTION)>= 0);
	}

	@Test
	void testBeginEndSession() {
		setUpBossLogger();
		tearDownBossLogger();

	}

	@Test
	void testSimpleLogUsage() {
		setUpBossLogger();
		StructuredLogger.Logger log1 = bossLogger.getRootLog();
		log1.info("test logging message");
		log1.err("this is an error");
		log1.loggedAssert(log1!=null, "unexpectedly, log1 is null.");
		log1.logDeinitStart("Starting module foo");

		StructuredLogger.Logger log2 = log1.newLogger("DRIVE");
		log2.info("Hi!");
		tearDownBossLogger();
	}

	@Test
	void testFileRawLogger1() throws IOException {

		// Create a temporary file
		File path = File.createTempFile("testLog", ".txt");
		path.deleteOnExit();	// So tests don't leave stuff lying around.			

		StructuredLogger.RawLogger rawLog = StructuredLogger.createFileLogger(path, false); // false == do not append
		rawLog.newSession("123");
		rawLog.log(3,  "INFO",  "Test raw message");
		rawLog.flush();
		rawLog.close();

		rawLog = StructuredLogger.createFileLogger(path, true); // true == append
		rawLog.newSession("456");
		rawLog.log(3,  "INFO",  "Another test raw message");
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
		rawLog.newSession("123");
		rawLog.log(3,  "INFO",  "Test raw message");
		rawLog.flush();
		rawLog.close();

		rawLog = StructuredLogger.createFileLogger(dirPath, "testLog", ".txt", true); // true==append
		rawLog.newSession("456");
		rawLog.log(3,  "INFO",  "Another test raw message");
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
		rawLog.newSession("123");
		for (String msg: sendMsgs) {
			rawLog.log(3,  "INFO",  msg );
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

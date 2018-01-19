/**
 * 
 */
package robotutils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;

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
			// We expect the previous message to be cleared between calls to log - tester's esponsibility.
			// So this verifies that log is called exactly once.
			assertFalse(logCalled);
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
	@AfterEach
	void tearDown() throws Exception {
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
		//fail("Not yet implemented");
	}

}

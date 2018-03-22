/**
 * 
 */
package com.rinworks.robotutils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
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
            logCalled = false;
            msgMsg = null;
        }

        @Override
        public void beginSession(String _sessionId) {
            assertFalse(newSessionCalled);
            newSessionCalled = true;
            sessionId = _sessionId;
        }

        @Override
        public void write(String msg) {
            assertTrue(newSessionCalled);
            assertFalse(closeCalled);
            logCalled = true;
            msgMsg = msg;
            if (logName.equals("file")) {
                String prefix = "[" + Thread.currentThread().getName() + "]";
                System.out.println(prefix + "raw message:" + msg);
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
            closeCalled = true;
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
        assert (rawLoggers == null);
        rawLoggers = new MyRawLog[] { new MyRawLog("file"), new MyRawLog("network") };
        assert (bossLogger == null);
        bossLogger = new StructuredLogger(rawLoggers, ROOT_LOG_NAME);
        bossLogger.setAsseretionFailureHandler(s -> {
            assertFalse(assertionHandlerCalled);
            assertionHandlerCalled = true;
        });
        bossLogger.setAutoFlushParameters(10000, 1);

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
        for (MyRawLog rl : rawLoggers) {
            rl.clearLoggedMsg();
        }

    }

    private void verifyBeginSessionState() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        // Check that the session beginning has been logged
        for (MyRawLog rl : rawLoggers) {
            assertTrue(rl.logCalled);
            verifySessionMessage(rl.msgMsg, true); // true == start
        }

    }

    private void verifyMessageTag(String tagKey, Consumer<String> verifier) {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        for (MyRawLog rl : rawLoggers) {
            assertTrue(rl.logCalled);
            Map<String, String> hm = StructuredMessageMapper.toMap(rl.msgMsg);
            String tagValue = hm.get(tagKey); // Will be null if the tag doesn't exist
            verifier.accept(tagValue);
        }
    }

    // Verify the state of bossLogger after the session has been ended.
    private void verifyEndSessionState() {
        StructuredLogger.Log rootLog = bossLogger.defaultLog();
        for (MyRawLog rl : rawLoggers) {
            assertTrue(rl.flushCalled);
            assertTrue(rl.closeCalled);

            // Check that the endSession message has been logged.
            assertTrue(rl.logCalled);
            verifySessionMessage(rl.msgMsg, false); // false == stop

            rl.flushCalled = false;
            rl.closeCalled = false;
            rl.clearLoggedMsg();
        }
        assertionHandlerCalled = false;

        // Now let's verify that doing various things with bossLogger does NOT call down
        // to the raw loggers.
        rootLog.info("TEST");
        rootLog.flush();
        rootLog.info("Hello!");
        rootLog.loggedAssert(false, "Foo");
        assertTrue(assertionHandlerCalled); // even after session closing, handler should be called.
        for (MyRawLog rl : rawLoggers) {
            assertFalse(rl.flushCalled);
            assertFalse(rl.closeCalled);
            assertFalse(rl.logCalled);
        }
    }

    // Verify that the right message was logged when a session has started or (if
    // {start} is false) has ended.
    private void verifySessionMessage(String msg, boolean start) {
        Map<String, String> map = StructuredMessageMapper.toMap(msg);
        String mPri = map.getOrDefault(StructuredLogger.TAG_PRI, "bad");
        String mCat = map.getOrDefault(StructuredLogger.TAG_CAT, "bad");
        String mType = map.getOrDefault(StructuredLogger.TAG_TYPE, "bad");
        assertEquals(mPri, "" + StructuredLogger.PRI0);
        assertEquals(mCat, StructuredLogger.CAT_INFO);
        String expectedType = start ? StructuredLogger.TYPE_LOG_SESSION_START : StructuredLogger.TYPE_LOG_SESSION_END;
        assertEquals(mType, expectedType);
    }

    // Simple self-contained example that goes into README.md introductory text.
    @Test
    void testIntroductoryExample1() throws IOException {

        // First we create a raw logger that will consume the low-level log messages
        // produced by the
        // structured logger. We create a temporary file
        File logFile = File.createTempFile("testLog", ".txt");

        int maxSize = 1000000;
        StructuredLogger.RawLogger rawLogger = LoggerUtils.createFileRawLogger(logFile, maxSize, null);

        // Then we create a structured logger. Typically there is just one of these per
        // system.
        StructuredLogger baseLogger = new StructuredLogger(rawLogger, "MY_SYSTEM");

        // When we are ready to start login, we begin the logging session. This is when
        // external resources (like files) are opened.
        baseLogger.beginLogging();

        // Then we log!
        // Info messages are logged with priority 1 (a tag "_PRI:1" is inserted to the
        // logged message)
        baseLogger.info("Logging an informational message");

        // Warning message are also logged with priority 1.
        baseLogger.warn("Logging a warning");

        // Error messages are logged with priority 0.
        baseLogger.err("Logging an error");

        // When done, we end logging.
        baseLogger.endLogging();
    }

    // Simple self-contained example that goes into README.md introductory text.
    @Test
    void testIntroductoryExample2() {

        // In this example, we create multiple raw loggers - one logs each logging
        // session to a separate file, and another sends
        // logging messages over a UDP port. We then perform a broader set of operations
        // by operating on a StructuredLogger.Log object.
        // First we create a raw logger that will consume the low-level log messages
        // produced by the
        // structured logger. This logger will create a new file for each logging
        // session.
        String tempDir = System.getProperty("java.io.tmpdir");
        File logDir = new File(tempDir, "testLogs");
        if (!logDir.exists()) {
            logDir.mkdir();
        }
        System.out.println("Log directory: " + logDir.getAbsolutePath());
        final int MAX_SIZE = 1000000; // Maximum size the logfile is allowed to grow.
        StructuredLogger.RawLogger rawFileLogger = LoggerUtils.createFileRawLogger(logDir, "myLog", ".log", MAX_SIZE,
                null);

        // Let's create a second raw logger. This one logs only Priority 0 or 1 messages
        // to a UDP port 31899 on the local host.
        StructuredLogger.RawLogger rawUDPLogger = LoggerUtils.createUDPRawLogger("localhost", 41899, logName -> 1);

        // Then we create the structured logger. Passing in a list of loggers.
        StructuredLogger.RawLogger[] rawLoggers = { rawFileLogger, rawUDPLogger };
        StructuredLogger baseLogger = new StructuredLogger(rawLoggers, "MY_SYSTEM");

        // When we are ready to start login, we begin the logging session. This is when
        // external resources (like files) are opened.
        baseLogger.beginLogging();

        // Then we log! To get the full set of log methods, we need to access a
        // StructuredLogger.Log object. These can be created on the fly, but
        // there is one created by default.
        StructuredLogger.Log log1 = baseLogger.defaultLog();

        // Add a message type (first parameter) to classify log messages for easier
        // analysis.
        log1.info("init", "Component Initialization");

        // Triggers flushing the logs to persistent storage (if applicable) at any time.
        log1.flush();

        // add the key-value pair "mode:auton" to all subsequent messages submitted to
        // log1.
        log1.addTag("mode", "auton");

        StructuredLogger.Log log2 = log1.newLog("LOG2");
        // Start a 'relative timestamp' (RTS) all subsequent log messages from this log
        // instance will have an '_rts' tag inserted with the time
        // in milliseconds relative to this call, for example "_rts:293"
        log2.startRTS();

        // Use the trace calls for high-frequency logging.
        log2.trace("This is a trace message");
        log2.trace("bearing", "x:3 b:2 angle:45");

        // Tracing can be disabled and enabled on the fly. This effects just this log
        // instance.
        log2.pauseTracing();
        log2.trace("This message will never be logged.");
        // trace messages submitted to log1 will continue to be traced, however.
        log1.trace("This message will be logged.");
        log2.resumeTracing();
        log2.trace("This message will be logged.");

        // To minimize the wasted work in building a trace message when
        // it will not in fact be logged to the underlying system,
        // check first if tracing is enabled for this log.
        if (log2.tracing()) {
            // Perform some resource-intensive setup of log messages
            int i1 = 42; // Answer to the meaning of life:-)
            int i2 = (int) Math.sqrt(i1); // The root of the answer to the meaning of life :-).
            log2.trace("This is a 'complex' trace message (i1 = " + i1 + ", i2 = " + i2 + ")");
        }

        // When done, we end logging.
        baseLogger.endLogging();

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
        if (log1.tracing())
            log1.trace("this is a another trace");

        // Assertion check - passing and failing
        log1.loggedAssert(true, "unexpectedly, log1 is null.");
        assertFalse(assertionHandlerCalled);
        log1.loggedAssert(false, "EXPECTED, log1 is null.");
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
        this.verifyMessageTag(StructuredLogger.TAG_RELATIVE_TIMESTAMP, rtsValue -> {
            assertTrue(rtsValue == null);
        });

        // Turn on RTS and verify that there is an RTS value
        log1.startRTS();
        log1.info("message2");
        this.verifyMessageTag(StructuredLogger.TAG_RELATIVE_TIMESTAMP, rtsValue -> {
            assertTrue(rtsValue != null);
            long time = Long.parseLong(rtsValue);
            assertTrue(time >= 0);
        });

        // Turn RTS off and verify that once again _rts tags are not inserted.
        log1.stopRTS();
        log1.info("message1");
        this.verifyMessageTag(StructuredLogger.TAG_RELATIVE_TIMESTAMP, rtsValue -> {
            assertTrue(rtsValue == null);
        });

        tearDownBossLogger();
    }

    @Test
    // Tags - Dynamic tags that are inserted into every logged message.
    void testTags() {
        setUpBossLogger();
        StructuredLogger.Log log = bossLogger.defaultLog();
        String[] tags = { "TAG1", "TAG2", "TAG3" };
        String[] values = { "", "hello", "a=b c=d" };
        assert (tags.length == values.length);

        // Add a few tags and very that they show up in the next logged message.

        for (int i = 0; i < tags.length; i++) {
            log.addTag(tags[i], values[i]);
        }
        log.info("some random message with data a:b c:d");

        for (int i = 0; i < tags.length; i++) {
            final String vi = values[i];
            this.verifyMessageTag(tags[i], val -> {
                assertEquals(vi, val);
            });
        }

        // Now remove them all and check we don't find any of them

        for (int i = 0; i < tags.length; i++) {
            log.removeTag(tags[i]);
        }
        clearLoggedMessages();

        log.info("some other random message with data foo:bar");

        for (int i = 0; i < tags.length; i++) {
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
        path.deleteOnExit(); // So tests don't leave stuff lying around.

        System.out.println("FileLogging: Logging to specific file " + path.getAbsolutePath());
        StructuredLogger.RawLogger rawLog = LoggerUtils.createFileRawLogger(path, 10000, null);
        rawLog.beginSession("123");
        rawLog.write("Test raw message 1");
        rawLog.flush();
        rawLog.close();

        // Let's do it a 2nd time.
        StructuredLogger.RawLogger rawLog2 = LoggerUtils.createFileRawLogger(path, 10000, null);
        rawLog2.beginSession("123");
        rawLog2.write("Test raw message 2");
        rawLog2.flush();
        rawLog2.close();

        // And a 3rd time, specififying a tiny max size.
        StructuredLogger.RawLogger rawLog3 = LoggerUtils.createFileRawLogger(path, 10, null);
        System.out.println("TEST: Ignore subsequent error message - it is expected");
        rawLog3.beginSession("123");
        rawLog3.write("Test raw message 3");
        rawLog3.flush();
        rawLog3.close();

    }

    @Test
    void testFileRawLogger2() {
        final String LOG_PREFIX = "testLog";
        // Create a temporary file
        String tempDir = System.getProperty("java.io.tmpdir");
        File dirPath = new File(tempDir, "testLogs");
        if (!dirPath.exists()) {
            dirPath.mkdir();
        }
        assert (dirPath.isDirectory());
        for (File f : dirPath.listFiles()) {
            String fn = f.getName();
            if (fn.indexOf(LOG_PREFIX) >= 0) {
                System.out.println("Test: clearing out old file " + f.getAbsolutePath());
                f.delete();
            }
        }
        dirPath.listFiles();

        System.out.println("FileLogging: Per-session logs are under " + dirPath.getAbsolutePath());
        StructuredLogger.RawLogger rawLog = LoggerUtils.createFileRawLogger(dirPath, "testLog", ".txt", 1000, null);
        rawLog.beginSession("123");
        rawLog.write("Test raw message 1");
        rawLog.flush();
        rawLog.close();

        // Let's attempt to create the same exact session again - it should fail because
        // the file exists...
        StructuredLogger.RawLogger rawLog2 = LoggerUtils.createFileRawLogger(dirPath, "testLog", ".txt", 1000, null);
        System.out.println("TEST: Ignore subsequent error message - it is expected");
        rawLog2.beginSession("123");
        rawLog2.write("Test raw message 2");
        rawLog2.flush();
        rawLog2.close();

        // Let's attempt to create a session with a really small max capacity - should
        // fail!
        System.out.println("FileLogging: Per-session logs are under " + dirPath.getAbsolutePath());
        StructuredLogger.RawLogger rawLog3 = LoggerUtils.createFileRawLogger(dirPath, "testLog", ".txt", 10, null);
        rawLog3.beginSession("456");
        System.out.println("TEST: Ignore subsequent error message - it is expected");
        rawLog3.write("Test raw message 1");
        rawLog3.flush();
        rawLog3.close();

    }

    @Test
    void testUDPRawLogger() throws SocketException, InterruptedException {
        final int PORT = 9876;
        ConcurrentLinkedQueue<String> receivedMessageQueue = new ConcurrentLinkedQueue<String>();
        StructuredLogger.RawLogger rawLog = LoggerUtils.createUDPRawLogger("localhost", PORT, null); // false==don't
                                                                                                     // append
        DatagramSocket serverSocket = new DatagramSocket(PORT);

        setupToReceiveUDPMessage(serverSocket, receivedMessageQueue);
        // Thread.sleep(500); // Wait
        String[] sendMsgs = { "Test raw message 1", "Test raw message 2", "Test raw message 3" };
        rawLog.beginSession("123");
        for (String msg : sendMsgs) {
            rawLog.write(msg);
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
        for (String msg : sendMsgs) {
            for (String msg1 : receivedMsgs) {
                if (msg.equals(msg1)) {
                    matchedMessages++;
                }
            }
        }
        assertEquals(sendMsgs.length, matchedMessages);
    }

    private void setupToReceiveUDPMessage(DatagramSocket serverSocket,
            ConcurrentLinkedQueue<String> receivedMessageQueue) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {

            try {
                while (true) {

                    byte[] receiveData = new byte[1024];
                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                    System.out.println("Waiting to receive UDP packet...");
                    serverSocket.receive(receivePacket);
                    System.out.println("Received packet.");
                    String msg = new String(receivePacket.getData(), 0, receivePacket.getLength());
                    receivedMessageQueue.add(msg);

                }
            } catch (IOException e) {
                System.err.println("IO Exception " + e);
            }
        });

    }

    // Receives count messages from the queue
    private String[] receiveTestMessages(int count, ConcurrentLinkedQueue<String> receivedMessageQueue)
            throws InterruptedException {
        String[] messages = new String[count];
        int i = count - 1;
        while (i >= 0) {
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

    // A more comprehensive 'stress' test:
    // Sets up N thread. Each thread creates a sub Log object and inserts a unique
    // tag identifying that thread. It also maintains
    // a thread-specific sequence number.
    // Each thread then logs messages like crazy and invokes a variety of log calls
    // for variety. The message encodes the per-thread sequence
    // number.
    //
    // Sets up two raw loggers:
    // - One accepts every message - it's log method verify it receives every
    // message in sequence (thread-specific sequence numbers are
    // strictly in order with nothing message.
    // - The other only accepts p1 messages. It's log method verifies that it only
    // receives p1 messages.

    @Test
    void testStressTest() {
        final String TEST_TYPE = "TEST";
        final String TSN_TAG = "tsn"; // Per-thread sequence number
        final String TID_TAG = "tid"; // Thread ID
        final HashMap<String, Long> tsnMap = new HashMap<String, Long>();
        final String[] tidList = { "T1", "T2", "T3" };
        final long STARTING_TSN = 1;
        final long ENDING_TSN = 30000;
        assert ENDING_TSN >= STARTING_TSN;
        final AtomicLong msgCount = new AtomicLong();

        // Put the initial values of received sequence numbers for each thread.
        for (String tid : tidList) {
            tsnMap.put(tid, STARTING_TSN - 1);
        }

        StructuredLogger.RawLogger logAll = new StructuredLogger.RawLogger() {

            String logThreadName = null; // set on the first log message we get.
            int flushCalls = 0; // We don't need to serialize because we know that all logging calls are
            // made in the context of a single thread.

            // Maps thread IDs to last received sequence number.

            @Override
            public void beginSession(String sessionId) {
                assertEquals(null, logThreadName);
            }

            @Override
            public void write(String msg) {

                // System.out.println("LOGALL " + msg);

                if (logThreadName == null) {
                    logThreadName = Thread.currentThread().getName();
                } else {
                    // Verify that log is always called in the
                    // context of the same thread.
                    String tn = Thread.currentThread().getName();
                    assertEquals(logThreadName, tn);
                }

                // Verify that we get every message, and that these messages are in sequence
                // for each thread that submitted them.
                Map<String, String> map = StructuredMessageMapper.toMap(msg);
                String type = map.get(StructuredLogger.TAG_TYPE);
                assertTrue(type != null);
                if (type.equals(TEST_TYPE)) {

                    // Update the number of test messages we have received.
                    msgCount.incrementAndGet();

                    // Retrieve the client submission thread ID. It had better be there!
                    String strTID = map.get(TID_TAG);
                    assertTrue(strTID != null);

                    // Retrieve per-thread sequence number. It has better be there!
                    String strTsn = map.get(TSN_TAG);
                    assertTrue(strTsn != null);
                    long tsn = Long.parseLong(strTsn);

                    // Retrieve the previous seq number from this TID.
                    Long prevTsn = tsnMap.get(strTID); // We should get something.
                    assertTrue(prevTsn != null);

                    // We expect to receive sequential sn's.
                    // assertEquals(prevTsn + 1, tsn);
                    assertTrue(prevTsn < tsn);
                    tsnMap.put(strTID, tsn);
                }

            }

            @Override
            public void flush() {
                flushCalls++;
            }

            @Override
            public void close() {
                // Nothing to do
            }

        };

        StructuredLogger.RawLogger logMaxP1 = new StructuredLogger.RawLogger() {

            @Override
            public void beginSession(String sessionId) {
                // Nothing to do

            }

            @Override
            public void write(String msg) {
                System.out.println("LOGPRI " + msg);

                // Verify that we get only P1 messages!
                Map<String, String> map = StructuredMessageMapper.toMap(msg);
                assertTrue(Integer.parseInt(map.get(StructuredLogger.TAG_PRI)) <= 1);
            }

            @Override
            public void flush() {
                // Nothing to do.

            }

            @Override
            public void close() {
                // Nothing to do.

            }

            // Accept only p1 messages.
            @Override
            public int maxPriority(String name) {
                return 1;
            }

        };

        StructuredLogger.RawLogger[] rawLoggers = { logAll, logMaxP1 };
        final StructuredLogger mainLogger = new StructuredLogger(rawLoggers, "ROOT");
        // Initialize client threads.
        Thread[] clientThreads = new Thread[tidList.length];
        final CountDownLatch rl = new CountDownLatch(tidList.length);
        for (int i = 0; i < tidList.length; i++) {
            final String tid = tidList[i];
            clientThreads[i] = new Thread(new Runnable() {

                @Override
                public void run() {
                    final long count = ENDING_TSN - STARTING_TSN + 1;
                    final double sleepFrac = 0.1; // sleep every 10th message
                    final double flushFrac = 10.0 / count; // flush on avg. 10 times total
                    // System.out.println("THREAD " + tid + " BEGINS...");
                    StructuredLogger.Log myLog = mainLogger.defaultLog().newLog(tid);
                    myLog.addTag(TID_TAG, tid);

                    // Log messages!
                    for (long seqNo = STARTING_TSN; seqNo <= ENDING_TSN; seqNo++) {
                        // The check below has us calling myLog.tracing() about half of the time.
                        if (Math.random() < 0.5 || myLog.tracing())
                            myLog.trace(TEST_TYPE, TSN_TAG + ":" + seqNo);
                        // Set check below to < 0.01 to see messages being
                        // discarded
                        if (Math.random() < sleepFrac) {
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        if (Math.random() < flushFrac) {
                            myLog.flush();
                        }
                    }

                    // System.out.println("THREAD " + tid + " ...ENDS");

                    // All done, signal latch.
                    rl.countDown();

                }

            }, tid);
        }

        // Start logging.
        mainLogger.beginLogging();

        long startTime = System.nanoTime();

        // Start each thread.
        for (Thread t : clientThreads) {
            t.start();
        }

        // Wait for all client threads to end.
        try {
            rl.await();

            // Close log.
            mainLogger.endLogging();

            long endTime = System.nanoTime();

            // Now verify we got exactly the number of messages we expected.
            // The +2 is for the session beginning and ending messages.
            long expectedCount = (ENDING_TSN - STARTING_TSN + 1) * clientThreads.length;
            long actualCount = msgCount.get();
            // assertEquals(expectedCount, actualCount);

            System.out.println("Sucessfully logged " + actualCount + " messages from " + clientThreads.length
                    + " threads in " + (endTime - startTime) / 1000000000.0 + " seconds");
            if (actualCount != expectedCount) {
                System.out.println("Dropped " + (expectedCount - actualCount) + " messages.");
                System.out.println("Logger says we dropped " + mainLogger.getDiscardedMessageCount() + " messages.");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }
    }

}

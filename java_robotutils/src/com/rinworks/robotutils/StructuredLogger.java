//
// Structured logging classes. For logging and tracing that is intended to be consumed by other
// programs that analyze and visualize the log data.
// Created by Joseph M. Joy (https://github.com/josephmjoy)
//
package com.rinworks.robotutils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Thread-safe logging of 'structured text' - Strings of the form "key1: value1
 * key2:value2". The client provides the low-level log consumers that implement
 * the StructuredLogger.RawLogger interface.
 */
public class StructuredLogger {

    // These control autoflush behavior - logs are flushed if the buffered raw log
    // messages
    // exceed maxBufferedMessageCount or if pariodicFlushMillis has elapsed since
    // the last
    // periodic flush.
    public static final int DEFAULT_MAX_BUFFERED_MESSAGE_COUNT = 1000;
    public static final double ABSOLUTE_BUFFERED_MESSAGE_TRIGGER_FRACTION = 0.25; // at what fraction we trigger
                                                                                  // processing buffers.
    public static final int DEFAULT_PERIODIC_FLUSH_MILLIS = 1000;

    // This limit to per-rawlog buffered messages is never exceeded. Messages
    // are deleted in chunks as this limit is approached. See impnotes.
    public static final int ABSOLUTE_BUFFERED_MESSAGE_LIMIT = 10000;
    public static final int MAX_WAIT_ON_ENDLOGGING = 1000;// Max time (in ms) endLoggin() waits for backgound logging
                                                          // tasks to complete.
    public final static int PRI0 = 0; // Tag indicating pri0
    public final static int PRI1 = 1; // Tag indicating pri1
    public final static int PRI2 = 2; // Tag indicating pri2
    public final static String INFO = "INFO";
    public final static String TRACE = "TRACE";
    public final static String ERR = "ERR";
    public final static String WARN = "WARN";

    private final String rootName;
    private final LogImplementation defaultLog;
    private String sessionId;
    private long sessionStart;
    private boolean sessionStarted = false;
    private boolean sessionEnded = false;
    private AtomicLong seqNo = new AtomicLong(0);
    private AtomicLong totalDiscardedMessageCount = new AtomicLong(0);
    private Consumer<String> assertionFailureHandler = null;
    private int maxBufferedMessageCount = DEFAULT_MAX_BUFFERED_MESSAGE_COUNT;
    private int periodicFlushMillis = DEFAULT_PERIODIC_FLUSH_MILLIS;

    // Background processing of logged messages - one object per raw log
    private final BufferedRawLogger[] bufferedLoggers;

    // These get initialized when logging starts (in startLogging(), and are
    // cancelled in stopLogging()).
    private Timer timer;
    private TimerTask periodicFlushTask;

    private TimerTask oneshotProcessBuffersTask; // Keeps track of a one-shot task if any.
    private final Object oneShotTaskLock = new Object(); // to synchronize setting the above.
    private boolean finalRundown; // Set to true ONCE - when the session is being closed.

    // These are for scrubbing message type and message fields before logging.
    private static final Pattern BAD_NAME_PATTERN = Pattern.compile("[^-.\\w]");
    private static final Pattern BAD_MSG_PATTERN = Pattern.compile("\\n\\r");

    /**
     * Clients provide this to actually write log messages to some system like the
     * file system or network.
     */
    public interface RawLogger {

        /**
         * Prepare to write logs for a new session. For example, a file-based logging
         * system may open a new file. {sessionId} will not contain illegal characters
         * for file names, such as slashes. This method will be called only once - when
         * the owning structured logging object's beginSession method is called.
         */
        void beginSession(String sessionId);

        /**
         * Optionally control which messages are written to this sink. It is more
         * efficient to reject messages by returning false here rather than ignoring it
         * in the call to write because of the overhead of generating and buffering
         * messages.
         * 
         * @param logName
         *            - name of the StructuredLogger.Log object that submitted the
         *            message.
         * @param pri
         *            - priority
         * @param cat
         *            - category
         * @return
         */
        default boolean filter(String logName, int pri, String cat) {
            return true;
        }

        /**
         * Write {msg} to the underlying log sink.
         */
        void log(String msg);

        /**
         * If appropriate, flush unbuffered data to the underlying store.
         */
        void flush();

        /**
         * Close any open resources (no further calls to log or flush will follow the
         * call to close).
         */
        void close();

    }

    /**
     * The core logger interface - multiple log objects can be built - one for each
     * component/sub-component or even for transient logging tasks.
     */
    public interface Log {

        // These are reserved key names - don't use them for generating key-value
        // pairs:
        final String SESSION_ID = "_sid"; // Session ID
        final String SEQ_NO = "_sn"; // Sequence number
        final String TIMESTAMP = "_ts"; // Timestamp - milliseconds since session started.
        final String COMPONENT = "_co";
        final String PRI = "_pri"; // Priority: 0/1/2
        final String CAT = "_cat"; // CATEGORY: ERR/WARN/INFO
        final String TYPE = "_ty"; // Message type
        final String RELATIVE_TIMESTAMP = "_rts"; // Optional relative timestamp - see Log.beginRTS
        final String DEF_MSG = "_msg"; // Default key for message contents

        //
        // Reserved Messages Types - reserved for messages generated by the logging
        // system itself.
        // These all begin with an underscore.
        //
        final String LOG_SESSION_START = "_LOG_SESSION_STARTED"; // Reserved for messages generated by logging system
        // itself
        final String LOG_SESSION_END = "_LOG_SESSION_ENDED";
        final String LOG_TRACING_PAUSED = "_LOG_TRACING_PAUSED";
        final String LOG_TRACING_RESUMED = "_LOG_TRACING_RESUMED";
        final String ASSERTFAIL = "_ASSERTION_FAILURE"; // Message generated by loggedAssert
        final String LOG_MESSAGES_DISCARDED = "_LOG_MESSAGES_DISCARDED"; // Messages were discarded because too many
                                                                         // were buffered
        final String OTHER = "_OTHER"; // Unspecified user message type

        // Recommended message types. These are not used by the logging system itself,
        // but they are
        // RECOMMENDED to be used to log common events.
        // Log the initialization/deinitialization of a component
        final String INIT_START = "INIT_START";
        final String INIT_END = "INIT_END";
        final String DEINIT_START = "DEINIT_START";
        final String DEINIT_END = "DEINIT_END";

        //
        // Actual logging methods - logged with message type "_OTHER"
        //

        /**
         * Log an error. (pri, cat, type) = (0, "ERR", "_OTHER")
         */
        void err(String s);

        /**
         * Log a warning. (pri, cat, type) = (1, "WARN", "_OTHER")
         */
        void warn(String s);

        /**
         * Log an important information message. (pri, cat, type) = (1, "INFO",
         * "_OTHER")
         */
        void info(String s);

        // The same logging methods, with a user-suppled message type.

        /**
         * Log an error. (pri, cat, type) = (0, "ERR", {typpe})
         */
        void err(String msgType, String s);

        /**
         * Log a warning. (pri, cat, type) = (1, "WARN",{type})
         */
        void warn(String msgType, String s);

        /**
         * Log an important information message. (pri, cat, type) = (1, "INFO", {type})
         */
        void info(String msgType, String s);

        /**
         * Log a high-volume trace message. (pri, cat, type) = (1, "TRACE", "_OTHER").
         * Traces can be dynamically enabled or disabled using the pauseTracing or
         * resumeTracing methods.
         */
        void trace(String s);

        /**
         * Log a high-volume trace message. (pri, cat, type) = (1, "TRACE", {type}).
         * Traces can be dynamically enabled or disabled using the pauseTracing or
         * resumeTracing methods.
         */
        void trace(String msgType, String s);

        /**
         * If {cond} is false log an error, appending {s} to the message, and flush the
         * log. If there is an assertion failure handler associated with the structured
         * logger, the handler is called. The handler is set by calling
         * setAssertionFailureHandler.
         */
        void loggedAssert(boolean cond, String s);

        /**
         * Tracing is enabled by default, but may be paused/resumed dynamically - useful
         * for selectively tracing extremely verbose data. Applies ONLY to this
         * StructuredLogger.Log instance.
         */
        void pauseTracing();

        /**
         * Resumes tracing. See pauseTracing() for more context.
         */
        void resumeTracing();

        /**
         * Starts adding a relative time stamp (RTS). Subsequent logging will include a
         * "_RTS" key whose value is the time in milliseconds that has elapsed since
         * this call was invoked. Applies ONLY to this log instance.
         */
        void startRTS();

        /**
         * Stops adding the relative stamps for this log instance. See startRTS() for
         * more context.
         */
        void stopRTS();

        /**
         * Adds a key (the 'tag') with empty value that gets inserted into every log
         * message made from this particular StructuredLogger.Log instance. The tag can
         * represent a boolean condition by it's absence/presence. See addTag(tag,
         * value) for more context.
         */
        void addTag(String tag);

        /**
         * Adds a key-value pair (the 'tag') that gets inserted into every log message
         * made from this particular StructuredLogger.Log instance. Tags must be
         * composed entirely of non-whitespace characters and must not include the ':'
         * (colon) character. To help catch this issue, characters in violation are
         * replaced by the '#' character, and the tag inserted, though this is probably
         * not what is wanted. If the tag already exists it's previous value is
         * overridden. Warning: using tagging will incur an overhead of allocating a
         * data structure to maintain the <key,value> mappings. Once created this map is
         * not deleted (i.e., even if all tags are removed). Warning: Avoid adding tags
         * to the same log from multiple threads. Doing so incurs a small risk of losing
         * previously-added tags or not picking up the most recently added tag.
         * 
         * @param tag
         *            - the tag
         * @param value
         *            - the value (can be an empty string)
         */
        // [FUTURE: Special 'mustache' tags like would get dynamic values, like {TID}
        // would set TID=<thread ID>]
        void addTag(String tag, String value);

        /**
         * Removes a previously added tag. Attempting to remove a null, empty or
         * Nonexistent tag is silently ignored. See addTag(key, value) for more context.
         */
        void removeTag(String tag);

        /**
         * Initiate flushing the ENTIRE log, not just messages logged to this instance
         * of StructuredLoggere.Log. Flushing happens in a background thread, but an
         * attempt is made to initiate flushing 'immediately'. The call returns without
         * waiting for the flush to complete.
         */
        void flush();

        /**
         * Creates a new StructuredLogger.Log object identified by {logName}. This is
         * equivalent to calling the root StructureLoggerObject's newLog method - there
         * is no special relationship between the current instance and the newly created
         * logs.
         * 
         * @param name
         *            - a short (single word) name describing this instance of
         *            StructuredLogger.Log. A hierarchical relationship can be
         *            established by following a suitable naming convention such as
         *            dotted-namespace notation, for example, "ROBOT", "ROBOT.ARM",
         *            "ROBOT.SCHEDULER", "ROBOT.ARM.MOTOR".
         */
        Log newLog(String logName);

        // FUTURE
        // Hidden tags whose existence can be checked (or rather asserted to be present
        // or absent) at a future time.
        // Perhaps these could be added as named parameters in Python, otherwise
        // additional methods to add, assert and remove tags.)

        // FUTURE: Concept of an optional 'location specifier' integer that is set to a
        // random integer that is with
        // very high priority unique across the source code - to be able to quickly
        // identify the source code where the log method
        // was invoked. In Python it could be a named parameter, ls=0 so we don't cause
        // an explosion in the number
        // of logging statements.

    }

    /**
     * Creates the main structured logging object, typically one per system.
     * {_rawLogger} is a low-level consumer of generated log messages. {rootName} is
     * the top-level name. Any StructuredLogger.Log object created have {rootName}
     * prefixed to their own log name when generating the component tag for each log
     * message.
     */
    public StructuredLogger(RawLogger _rawLogger, String _rootName) {
        this(new RawLogger[] { _rawLogger }, _rootName);
    }

    /**
     * Creates the main structured logging object, typically one per system.
     * {_rawLoggers} is an array of low-level consumers of generated log messages.
     * {rootName} is the top-level name. Any StructuredLogger.Log object created
     * have {rootName} prefixed to their own log name when generating the component
     * tag for each log message.
     */
    public StructuredLogger(RawLogger[] _rawLoggers, String _rootName) {
        this.bufferedLoggers = new BufferedRawLogger[_rawLoggers.length];
        for (int i = 0; i < _rawLoggers.length; i++) {
            this.bufferedLoggers[i] = new BufferedRawLogger(_rawLoggers[i]);
        }

        this.rootName = _rootName;
        this.defaultLog = this.commonNewLog(_rootName);
    }

    /**
     * Sets the assertion failure handler. The default handler is null, which means
     * that assertion failures are logged but otherwise no action is taken. Note it
     * is recommended to call this before calling beginLogging to ensure that all
     * assertion failures are caught.
     */
    public void setAsseretionFailureHandler(Consumer<String> _assertionFailureHandler) {
        this.assertionFailureHandler = _assertionFailureHandler;
    }

    /**
     * Sets parameters that control when messages are flushed. Automatic flushing is
     * triggered if the number of buffered messages exceeds
     * {maxBufferedMessageCount} or if {periodicFlushMillis} has elapsed since the
     * last periodic flush. These times are honored to some degree of approximation
     * because actual I/O is performed by background threads subject to scheduling
     * delays. This call must be called before logging has begin, else the call has
     * no effect.
     */
    public void setAutoFlushParameters(int maxBfferedMessageCount, int periodicFlushMillis) {
        synchronized (this) {
            if (!this.sessionStarted) {
                this.maxBufferedMessageCount = Math.max(maxBufferedMessageCount, 0);
                this.periodicFlushMillis = Math.max(periodicFlushMillis, 100); // We clamp very short period requests.
            } else {
                System.err.println(
                        "StructuredLogger: ignoring auto-flush parameter update because session has already started");
            }
        }
    }

    /**
     * Begins the logging session. The Session timestamp is set. Caller must ensure
     * no other thread attempts to log concurrently with this call - actual logging
     * calls are not synchronized for performance reasons.
     */
    public synchronized void beginLogging() {

        if (this.sessionStarted || this.sessionEnded) {
            System.err.println("Ignoring attempt to begin structured logger " + rootName + ":invalid state");
        } else {
            long startTime = System.currentTimeMillis();
            String sessionID = "" + startTime; // WAS String.format("%020d", startTime);
            this.timer = new Timer("Structured Logger (" + rootName + ")", true);// true == daemon task.
            this.periodicFlushTask = newBackgroundProcessor(false, null); // false, null== don't flush immediately, no
            // latch
            this.oneshotProcessBuffersTask = null; // These are created on demand when we have to clear a backlog of
            // buffered messages.
            this.sessionId = sessionID;
            this.sessionStart = startTime;
            seqNo.set(0); // First logged sequence number in the session is 1.
            for (BufferedRawLogger brl : bufferedLoggers) {
                brl.rawLogger.beginSession(sessionId);
            }
            this.sessionStarted = true;

            this.timer.schedule(this.periodicFlushTask, this.periodicFlushMillis, this.periodicFlushMillis);

            // Log very first message...
            String msg = String.format("rootName:%s maxBuffered:%s autoFlushPeriod:%s", this.rootName,
                    this.maxBufferedMessageCount, this.periodicFlushMillis);
            defaultLog.pri0(Log.LOG_SESSION_START, msg);
        }

    }

    /**
     * Ends the logging session. Once the session has been ended a new session can
     * not be started with this instance. Caller must ensure no other thread
     * attempts to log concurrently with this thread - actual logging calls are not
     * synchronized for performance reasons.
     */
    public void endLogging() {

        boolean deinit = true;

        logDiscardedMessageCount();
        defaultLog.pri0(Log.LOG_SESSION_END, "rootName:" + rootName);

        synchronized (this) {
            if (!this.sessionStarted) {
                System.err.println("Ignoring attempt to end structured logger " + rootName + ":invalid state");
                deinit = false;
            }
            this.sessionStarted = false; // no more messages will be logged.
        }
        if (deinit) {
            // Wait some bounded time for the buffers to be written out. Not that no new log
            // messages can be submitted.
            emptyBuffersOnShutdown_BLOCKING();
            timer.cancel(); // No background flushing of tasks will be scheduled, though there could be one
            // running
            this.sessionEnded = true;
            for (BufferedRawLogger brl : bufferedLoggers) {
                brl.rawLogger.flush();
                brl.rawLogger.close();
            }
        }
    }

    /**
     * Log an error. (pri, cat, type) = (0, "ERR", "_OTHER")
     */
    public void err(String s) {
        this.defaultLog.err(s);
    }

    /**
     * Log a warning. (pri, cat, type) = (1, "WARN", "_OTHER")
     */
    public void warn(String s) {
        this.defaultLog.warn(s);
    }

    /**
     * Log an important information message. (pri, cat, type) = (1, "INFO", {type}).
     * To log high volumes of messages, retrieve the default StructuredLogger.Log
     * object by calling defaultLog() or create a new Log object by calling
     * newLog(), and call one of the Log object's trace methods.
     */
    public void info(String s) {
        this.defaultLog.info(s);
    }

    /**
     * Initiate flushing the ENTIRE log, not just messages logged to this instance
     * of StructuredLoggere.Log. Flushing happens in a background thread, but an
     * attempt is made to initiate flushing 'immediately'. The call returns without
     * waiting for the flush to complete.
     */
    public void flush() {
        this.defaultLog.flush();
    }

    /**
     * Get the root ("top level") log object, which provides a much richer set of
     * logging methods
     */
    public Log defaultLog() {
        return this.defaultLog;
    }

    /**
     * Gets the total number of deleted messages
     * 
     */
    public long getDiscardedMessageCount() {
        return this.totalDiscardedMessageCount.get();
    }

    /**
     * Utility raw log constructors takes this filter object to provide the caller
     * control of filtering messages.
     */
    public interface Filter {
        /**
         * Return true to accept messages from the StructuredLogger.Log instance with
         * name {logName}, and with priority {pri} and category {cat}.
         */
        boolean filter(String logName, int pri, String cat);
    }

    /**
     * Creates a logger that generates per-session log files of the form
     * {prefix}{session id}{suffix}. If a file already exists with the same name
     * when StructuredLogger.openSession is called, that file WILL NOT be touched,
     * and no logging will be done. No IOExceptions are thrown. Instead various
     * error conditions produce one-time error messages that messages are written to
     * System.err. A valid RawLogger is always returned, even on error.
     * 
     * @param logDirectory
     *            - directory where log files will reside.
     * @param prefix
     *            - filename prefix
     * @param suffix
     *            - filename suffix
     * @param long
     *            - The maximum size the log file is allowed to grow.
     * @param filter
     *            - if null: accept all messages, else this method is call to
     *            determine whether or not to accept messages with the specified
     *            attributes.
     * @return A StructuredLogger.RawLogger object that may be passed into a
     *         StructuredLogger constructor
     */
    public static RawLogger createFileLogger(File logDirectory, String prefix, String suffix, long maxSize,
            Filter filter) {
        FileRawLogger fileLogger = new FileRawLogger(logDirectory, prefix, suffix, maxSize, filter);
        return fileLogger;

    }

    /**
     * Creates a logger that logs multiple sessions to a single file. If a file
     * already exists with the same name when StructuredLogger.openSession is
     * called, that file WILL be appended to. The path name MUST contain the string
     * "log" (a case-insensitive check is made). No IOExceptions are thrown. Instead
     * various error conditions produce one-time error messages that are written to
     * System.err. A valid RawLogger is always returned, even on error.
     * 
     * @param logFile
     *            - File object representing log file path.
     * @param long
     *            - The maximum size the log file is allowed to reach.
     * @param filter
     *            - if null: accept all messages, else this method is call to
     *            determine whether or not to accept messages with the specified
     *            attributes.
     * @return A StructuredLogger.Logger object that may be passed into a
     *         StructuredLogger constructor
     */
    public static RawLogger createFileLogger(File logFile, long maxSize, Filter filter) {
        FileRawLogger fileLogger = new FileRawLogger(logFile, maxSize, filter);
        return fileLogger;

    }

    /**
     * Creates a logger that transmits log messages as UDP packets to the specified
     * destination.
     * 
     * @param address
     *            - Destination host name or IP Address
     * @param port
     *            - Destination port.
     * @return A StructuredLogger.Logger object that may be passed into a
     *         StructuredLogger constructor
     */
    public static RawLogger createUDPLogger(String address, int port, Filter filter) {
        UDPRawLogger fileLogger = new UDPRawLogger(address, port, filter);
        return fileLogger;
    }

    // **********************************************************************************
    // End of public methods
    // ********************************************************************************

    // Consolidates calls to create a new log objects, in case we want to do
    // something more
    // like keep a list of logs. At present we don't keep a global list of allocated
    // log objects.
    private LogImplementation commonNewLog(String name) {
        return new LogImplementation(name);
    }

    // Launch a special one-time timer task to write out all buffered messages
    // to the raw logs, but NOT attempt to flush the logs. Wait until this task has
    // completed execution or a timeout.
    private void emptyBuffersOnShutdown_BLOCKING() {
        this.finalRundown = true; // this encourages a running timer task to NOT flush - just write buffers.
        final CountDownLatch latch = new CountDownLatch(1);
        TimerTask finalTask = newBackgroundProcessor(false, latch); // false=don't flush now
        timer.schedule(finalTask, 0); // 0 == 'immediately'
        try {
            // This call will BLOCK until the above task is done (or rather calls
            // latch.countDown()).
            boolean done = latch.await(Math.min(this.periodicFlushMillis, StructuredLogger.MAX_WAIT_ON_ENDLOGGING),
                    TimeUnit.MILLISECONDS);
            if (!done) {
                System.err.println(
                        "StructuredLogger: timed out waiting for final task to finish. Abandanoning any buffered messages and proceeding to flush all raw logs.");
            }
        } catch (InterruptedException e) {
            System.err.println(
                    "StructuredLogger: interrupt exception waiting for final task to finish. Abandanoning any buffered messages and proceeding to flush all raw logs.");
            Thread.currentThread().interrupt(); // Notes that the interrupt happened. Blocking methods that happen later
            // will probably throw another InterruptedException.;
        }
    }

    private void logDiscardedMessageCount() {
        for (BufferedRawLogger brl : bufferedLoggers) {
            int discarded = brl.discardedMessages.getAndSet(0);
            if (discarded > 0) {
                defaultLog().warn(Log.LOG_MESSAGES_DISCARDED, "log:" + brl.rawLogger + " discardedCount: " + discarded);
            }
        }
    }

    // Create the timer task that will process all
    // queued messages and potentially flushing
    // the raw logs. If {flushNow} is true, the logs will
    // be flushed in the context of the task (except if the
    // logging is being shutdown). If optional {latch} is
    // non null, it will be counted-down.
    private TimerTask newBackgroundProcessor(boolean flushNow, CountDownLatch latch) {
        return new TimerTask() {

            @Override
            public void run() {

                // System.out.println("In BGP task");

                logDiscardedMessageCount();

                for (BufferedRawLogger brl : bufferedLoggers) {
                    brl.processAllBufferedMessages();
                }

                // Now, we flush each log if necessary
                for (BufferedRawLogger brl : bufferedLoggers) {
                    // Logs are never flushed in the background if {this}
                    // is being shut down (endLogging() has been called).
                    if (finalRundown) {
                        break;
                    }
                    if (flushNow || brl.msgsSinceLastFlush.get() > maxBufferedMessageCount) {
                        brl.rawLogger.flush(); // can potentially take some time.
                        brl.msgsSinceLastFlush.set(0);
                    }
                }

                if (latch != null) {
                    latch.countDown();
                }

                // If we're a one-shot task clear the oneshot task.
                //
                synchronized (oneShotTaskLock) {
                    if (oneshotProcessBuffersTask == this) {
                        oneshotProcessBuffersTask = null;
                    }
                }
            }
        };
    }

    // This private class implements a Log object
    private class LogImplementation implements Log {
        final String logName;
        boolean tracingEnabled = true;
        boolean rtsEnabled = false; // rts = relatie timestamp
        long rtsStartTime = 0; // if rtsEnabled, gettimemillis value when startRTS was called.
        ConcurrentHashMap<String, String> tagMap = null; // Created on demand - see addTag
        String tagsString = ""; // Linearized tag map - ready to be inserted into a raw log message.
        // {component} should be a short - 3-5 char - representation of the component.
        // The component hierarchy is represented using dotted notation, e.g.:
        // root.a.b.c

        LogImplementation(String logName) {
            this.logName = scrubName(logName); // Replace ':' etc (these shouldn't be there) by '#'
        }

        // See the Logger interface definition for documentation on
        // these overridden methods.

        @Override
        public LogImplementation newLog(String logName) {
            // Note: commonNewLog is actually a method of the *containing*
            // class - an instance of StructuredLogger.
            return commonNewLog(rootName + "." + logName);
        }

        @Override
        public void err(String s) {
            err(OTHER, s);

        }

        @Override
        public void warn(String s) {
            warn(OTHER, s);

        }

        @Override
        public void info(String s) {
            info(OTHER, s);

        }

        @Override
        public void trace(String s) {
            trace(OTHER, s);

        }

        @Override
        public void err(String msgType, String s) {
            if (tracingEnabled) {
                rawLog(PRI0, ERR, scrubName(msgType), s);
            }
        }

        // @Override
        public void warn(String msgType, String s) {
            if (tracingEnabled) {
                rawLog(PRI1, WARN, scrubName(msgType), s);
            }
        }

        @Override
        public void info(String msgType, String s) {
            if (tracingEnabled) {
                rawLog(PRI1, INFO, scrubName(msgType), s);
            }
        }

        @Override
        public void trace(String msgType, String s) {
            if (tracingEnabled) {
                rawLog(PRI2, TRACE, scrubName(msgType), s);
            }
        }

        @Override
        public void pauseTracing() {
            pri0(LOG_TRACING_PAUSED, "");
            tracingEnabled = false;
        }

        @Override
        public void resumeTracing() {
            pri0(LOG_TRACING_RESUMED, "");
            tracingEnabled = true;

        }

        @Override
        public void loggedAssert(boolean cond, String s) {
            // Note that we will call the assertionFailureHandler even if there is the
            // logging session is not active.
            // However if there is no session, there will be no logging and flushing (those
            // methods below will have no effect).
            if (!cond) {
                rawLog(PRI0, ERR, ASSERTFAIL, s);
                this.flush();
                if (assertionFailureHandler != null) {
                    assertionFailureHandler.accept(s);
                }
            }

        }

        @Override
        public void flush() {
            if (sessionStarted) {
                // Launch an immediate timer task
                triggerBackgroundTaskIfNotRunning(true); // true == force flush
            }
        }

        // Not for use outside the containing class.
        void pri0(String msgType, String s) {
            rawLog(PRI0, INFO, msgType, s);
        }

        private void rawLog(int pri, String cat, String msgType, String msg) {
            // Example:
            // _sid:989, _sn:1, _ts: 120, _co: .b, _pri:1, _sev:INFO, _ty:OTHER, Hello
            // world!

            // Note that sessionStarted is defined in the containing class -
            // StructuredLogger!
            if (!sessionStarted) {
                return; // ******************** EARLY RETURN ******************
            }

            // Push it into each logger's buffer if they want it.
            // Note that if no logger wants it the raw message is not
            // even generated.
            boolean triggerTask = false;
            String rawMsg = null;
            for (BufferedRawLogger brl : bufferedLoggers) {
                if (brl.rawLogger.filter(logName, pri, cat)) {
                    int queueLength = brl.approxQueueLength.get();
                    if (queueLength < ABSOLUTE_BUFFERED_MESSAGE_LIMIT) {
                        if (rawMsg == null) {
                            rawMsg = rawMessage(pri, cat, msgType, msg);
                        }
                        brl.approxQueueLength.incrementAndGet();
                        brl.buffer.add(rawMsg);
                    } else {
                        // Not a good situation - we have exceeded the limit.
                        brl.discardedMessages.incrementAndGet();
                        totalDiscardedMessageCount.incrementAndGet();
                    }

                    final int TRIGGER_LIMIT = (int) (ABSOLUTE_BUFFERED_MESSAGE_LIMIT
                            * ABSOLUTE_BUFFERED_MESSAGE_TRIGGER_FRACTION);
                    int nonFlushedMsgs = queueLength + brl.msgsSinceLastFlush.get();
                    triggerTask = triggerTask || nonFlushedMsgs > TRIGGER_LIMIT;
                }
            }

            // If the max number of messages in any one queue is too large, it triggers
            // a oneshot task to clear all message buffers (provided one is not already
            // active!).
            if (triggerTask) {
                triggerBackgroundTaskIfNotRunning(false);
            }
        }

        // Generates and returns the message that is actually logged to the raw logs.
        // This includes the atomically-incremented sequence number, timestamp and
        // any extra tags.
        private String rawMessage(int pri, String cat, String msgType, String msg) {

            msgType = scrubName(msgType);
            msg = scrubMessage(msg);

            // Keeping some old code because of the subtle issue it had.
            // OLD: As a special case, if msg contains no colons, we prefix a special _msg
            // key.
            // NEW: We AWAYS prefix the special _msg key. This is to make sure that the
            // previous
            // key's value cannot be corrupted by a message. Besides, it's common for the
            // user messagae to be
            // something like "got info a:b c:d". In this case, the OLD way would tack on
            // "got info" to the
            // previous key, whatever that is, while in the NEW way, the _msg key will have
            // value "got info", which
            // is not bad - the "a:b c:d" part will make its way into the dictionary.
            // if (msg.indexOf(StructuredMessageMapper.COLON)==-1) {
            // msg = DEF_MSG + StructuredMessageMapper.COLON + msg;
            // }

            long curSeq = seqNo.incrementAndGet();
            long millis = System.currentTimeMillis();
            long timestamp = millis - sessionStart;
            String rtsKeyValue = (rtsEnabled) ? RELATIVE_TIMESTAMP + ":" + (millis - rtsStartTime) + " " : "";
            String output = String.format("%s:%s %s:%s %s:%s %s%s:%s %s:%s %s:%s %s:%s %s%s: %s", Log.SESSION_ID,
                    sessionId, Log.SEQ_NO, curSeq, Log.TIMESTAMP, timestamp, rtsKeyValue, Log.COMPONENT, logName,
                    Log.PRI, pri, Log.CAT, cat, Log.TYPE, msgType, tagsString, Log.DEF_MSG, msg);
            return output;
        }

        // RTS implementation:
        // We keep a boolean flag whether RTS is enabled,
        // and keep the currentTimeMillis value when RTS was
        // started. At logging time, we optionally insert the RTS (key,value)

        @Override
        public void startRTS() {
            rtsStartTime = System.currentTimeMillis();
            rtsEnabled = true;
        }

        @Override
        public void stopRTS() {
            rtsStartTime = 0;
            rtsEnabled = false;
        }

        @Override
        public void addTag(String tag) {
            addTag(tag, "");

        }

        // addTag implementation creates an on-demand ConcurrentHashMap.
        // Once created this map is not deleted (i.e., even if all tags are removed).
        // Any previously mapped value is discarded.
        @Override
        public void addTag(String tag, String value) {
            if (tagMap == null) {
                ConcurrentHashMap<String, String> hm = new ConcurrentHashMap<String, String>();
                // We synchronize on this just for setting up the tagMap. Once set up,
                // this tag map is NEVER changed. This is a key invariant that allows
                // add/removeTag to not have to acquire this lock before using the map.
                synchronized (this) {
                    if (tagMap == null) {
                        tagMap = hm;
                    }
                }
            }

            // Now that we have a tag map, we can synchronize on it for properly isolating
            // tag updates: if multiple threads are concurrently adding tags, the
            // regenerated
            // tag string will eventually include all the tags. Of course if multiple
            // threads are
            // attempting to add and remove the same tag, the end result is unpredictable,
            // but that is
            // expected.
            synchronized (tagMap) {
                tagMap.put(scrubName(tag), scrubMessage(value));
                regenerateTagsString(); // We re-compute the string representation each time a tag is added.
            }

        }

        // Regenerate the tags message (if there are no tags associated with this log,
        // this
        // string is empty. NOT synchronized - caller must take care of synchronization.
        private void regenerateTagsString() {
            if (tagMap == null || tagMap.size() == 0) {
                tagsString = "";
            } else {
                StringBuilder sb = new StringBuilder();
                for (Entry<String, String> e : tagMap.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    sb.append(" " + k + ":" + v);
                }
                if (tagMap.size() > 0) {
                    sb.append(" ");
                }
                tagsString = sb.toString();
            }
        }

        // Attempting to remove a null tag or a tag that does not exist has no effect.
        @Override
        public void removeTag(String tag) {
            if (tag != null && tagMap != null) {

                // Now that we have a tag map, we can synchronize on it for properly isolating
                // tag updates: if multiple threads are concurrently adding tags, the
                // regenerated
                // tag string will eventually include all the tags. Of course if multiple
                // threads are
                // attempting to add and remove the same tag, the end result is unpredictable,
                // but that is
                // expected.
                synchronized (tagMap) {
                    System.out.println("Removing tag " + tag);
                    tagMap.remove(tag);
                    assert (tagMap.get(tag) == null);
                    regenerateTagsString(); // We re-compute the string representation each time a tag is added.
                }
            }
        }
    }

    // Maintains state associated with a single raw log, include
    // message buffer for that log.
    private class BufferedRawLogger {
        final RawLogger rawLogger;
        final ConcurrentLinkedQueue<String> buffer;
        final AtomicInteger discardedMessages; // used to generate a log message.
        final AtomicInteger approxQueueLength; // used to trigger oneshot buffer processing.
        final AtomicInteger msgsSinceLastFlush; // used to trigger oneshot buffer processing.

        BufferedRawLogger(RawLogger rl) {
            rawLogger = rl;
            buffer = new ConcurrentLinkedQueue<String>();
            discardedMessages = new AtomicInteger();
            approxQueueLength = new AtomicInteger();
            msgsSinceLastFlush = new AtomicInteger();
        }

        public void processAllBufferedMessages() {

            // We set this to 0 NOW before we will briefly clear the buffer below. It is
            // possible that this count could go up even if the responsible messages are
            // cleared here - that's fine. It's just an estimate to trigger a BG task.
            this.approxQueueLength.set(0);

            String rm = this.buffer.poll();
            int loggedCount = 0;
            while (rm != null) {
                this.rawLogger.log(rm);
                loggedCount++;
                rm = this.buffer.poll();
            }
            this.msgsSinceLastFlush.addAndGet(loggedCount);
        }
    }

    // Replace invalid chars by a '#'
    private static String scrubName(String msgType) {
        // Presumably this is faster than using a Regex? Not sure.
        return BAD_NAME_PATTERN.matcher(msgType).replaceAll("#");
    }

    // Trigger a one-shot background task to process buffers, if
    // there isn't one already. The task will force-flush
    // if {flushNow} is true.
    private void triggerBackgroundTaskIfNotRunning(boolean flushNow) {
        if (oneshotProcessBuffersTask == null) {
            TimerTask task = newBackgroundProcessor(flushNow, null); // null == no latch
            // latch
            boolean scheduleTask = false;
            // We create the task optimistically expecting to
            // actually schedule it, but we may not. We do this
            // outside the lock to keep the lock holding time to
            // a minimum.
            synchronized (oneShotTaskLock) {
                if (oneshotProcessBuffersTask == null) {
                    oneshotProcessBuffersTask = task;
                    scheduleTask = true;
                }
            }

            if (scheduleTask) {
                // System.out.println("Triggering BGP");
                timer.schedule(task, 0);
            }
        }
    }

    // Replace invalid chars by a '#'
    private static String scrubMessage(String msgType) {
        return BAD_MSG_PATTERN.matcher(msgType).replaceAll("#");
    }

    // This is a static class beause it is constructed from within static methods of
    // StructuredLogger.
    // It is intended to be constructed BEFORE a StructuredLogger exists and
    // therefore does not have access
    // (and in fact anyways has no business accessing!) instance fields of
    // StructuredLogger.
    private static class FileRawLogger implements RawLogger {
        final int MAX_EXCEPTION_COUNT = 100; // after which we will disable logging.
        final boolean perSessionLog;
        final long maxSize;
        final File logDirectory;
        final Filter filter;
        File logFile;
        final String prefix;
        final String suffix;

        boolean loggingDisabled; // Typically because of an unrecoverable error
        long remainingCapacity; // Bytes we can still log before reaching maxSize.
        BufferedWriter out;
        boolean logErrorNotified; // we generate on err msg on write error.
        int exceptionCount = 0;

        // Logger that creates per-session log files
        public FileRawLogger(File logDirectory, String prefix, String suffix, long maxSize, Filter filter) {
            this.perSessionLog = true;
            this.logDirectory = logDirectory;
            this.prefix = prefix;
            this.suffix = suffix;
            this.maxSize = maxSize;
            this.filter = filter;

        }

        // Logger that logs to a single log file
        public FileRawLogger(File logFile, long maxSize, Filter filter) {
            this.perSessionLog = false;
            this.logDirectory = null;
            this.logFile = logFile;
            this.prefix = null;
            this.suffix = null;
            this.maxSize = maxSize;
            this.filter = filter;
        }

        @Override
        public void beginSession(String sessionId) {

            // We don't throw any exceptions on error, just write the error to the err
            // console.

            if (!loggingDisabled) {
                if (perSessionLog) {

                    if (!logDirectory.canWrite()) {
                        System.err.println(String.format("FileRawLogger: log directory {%s} cannot be written to.",
                                logDirectory.getAbsolutePath()));
                    }
                    String name = prefix + sessionId + suffix;
                    logFile = new File(logDirectory, name);
                    if (logFile.exists()) {
                        // OOPS- we WILL not touch a log file that exists in per-session mode. This
                        // should 'never'
                        // happen because the file includes the supposedly unique session ID (which is
                        // typically
                        // System.currentTimeMillis()).
                        System.err.println(
                                String.format("FileRawLogger: log name {%s} already exists and will NOT be overridden.",
                                        logFile.getAbsolutePath()));
                        loggingDisabled = true;
                    }

                } else {

                    // Check that the path name contains 'log'.
                    String fullName = logFile.getAbsolutePath();
                    if (fullName.toLowerCase().indexOf("log") < 0) {
                        System.err.println(
                                String.format("FileRawLogger: disabling logging because the specified log path {%s}"
                                        + "does not contain the string \"log\"",
                                        fullName));
                        loggingDisabled = true;
                    }
                }
            }

            if (!loggingDisabled) {
                try {
                    // per-session (filename generated based on sessionID) ==> do NOT append
                    // otherwise (filename specified explicitly) ==> DO append
                    boolean append = !perSessionLog;
                    FileWriter fr = new FileWriter(logFile, append);
                    out = new BufferedWriter(fr);
                    long len = logFile.length();
                    this.remainingCapacity = maxSize - len; // could potentially be negative.
                } catch (IOException e) {
                    System.err.println(String.format(
                            "FileRawLogger: Cannot log. Could not create/open log file {%s}. Exception: %s",
                            logDirectory.getAbsolutePath(), e));
                    out = null;
                    loggingDisabled = true;
                }
            }

            if (loggingDisabled) {
                System.err.println("FileRawLogger: NOT starting session with ID " + sessionId
                        + " because of earlier unrecoverable error.");
                return;
            } else {
                assert out != null;
            }

        }

        @Override
        public boolean filter(String logName, int pri, String cat) {
            return loggingDisabled == false && (filter == null || this.filter.filter(logName, pri, cat));
        }

        @Override
        public void log(String msg) {

            if (loggingDisabled)
                return; // *** EARLY RETURN ****

            try {
                int len = msg.length();
                if (remainingCapacity > len) {
                    out.write(msg, 0, msg.length());
                    out.newLine();
                    remainingCapacity -= len;
                } else {
                    System.err.println(String.format("FileRawLogger: log file {%s} has reached max capacity %d."
                            + "Stopping further logging.",
                            logFile.getAbsolutePath(),
                            this.maxSize));
                    logErrorNotified = true;
                    loggingDisabled = true;
                    out.flush();
                    out.close();
                    out = null;
                }
            } catch (IOException e) {
                exceptionCount++;
                if (!logErrorNotified) {
                    System.err.println(String.format("FileRawLogger: could not write to log file {%s}. Exception: %s",
                            logFile.getAbsolutePath(), e));
                    logErrorNotified = true;
                    if (exceptionCount > MAX_EXCEPTION_COUNT) {
                        loggingDisabled = true;
                    }
                }
            }

        }

        @Override
        public void flush() {

            if (loggingDisabled)
                return; // *** EARLY RETURN ****

            try {
                if (out != null) {
                    out.flush();
                }
            } catch (IOException e) {
                exceptionCount++;
                System.err.println(String.format("FileRawLogger: could not flush log file {%s}. Exception: %s",
                        logFile.getAbsolutePath(), e));
                if (exceptionCount > MAX_EXCEPTION_COUNT) {
                    loggingDisabled = true;
                }
            }
        }

        @Override
        public void close() {
            try {
                if (out != null) {
                    out.close();
                    out = null;
                }
            } catch (IOException e) {
                exceptionCount++;
                System.err.println(String.format("FileRawLogger: could not close log file {%s}. Exception: %s",
                        logFile.getAbsolutePath(), e));
            }
        }

    }

    private static class UDPRawLogger implements RawLogger {
        final String destAddress;
        final int destPort;
        final Filter filter;
        boolean logErrorNotified; // we generate one err msg if there is an error message on write..
        DatagramSocket clientSocket;
        InetAddress destIPAddress;
        boolean canLog = false;

        // Logger that logs by sending UDP traffic to the specified address and port.
        public UDPRawLogger(String _address, int _port, Filter _filter) {
            destAddress = _address;
            destPort = _port;
            filter = _filter;
        }

        @Override
        public void beginSession(String sessionId) {

            try {
                clientSocket = new DatagramSocket();
                destIPAddress = InetAddress.getByName(destAddress);
            } catch (SocketException e) {
                System.err.println("UDPRawLogger: Cannot log. Could not create DatagramSocket. Exception: " + e);
            } catch (UnknownHostException e) {
                System.err.println(
                        "UDPRawLogger: Cannot log. Could not resolve address " + destAddress + ". Exception: " + e);
            }
            System.out.println(String.format("UDPRawLogger: logging session %s to IP Address %s, port %d", sessionId,
                    destIPAddress, destPort));
            canLog = true;
        }

        @Override
        public boolean filter(String logName, int pri, String cat) {
            return filter == null || this.filter.filter(logName, pri, cat);
        }

        @Override
        public void log(String msg) {
            try {
                if (canLog) {
                    byte[] sendData = msg.getBytes();
                    DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, destIPAddress, destPort);
                    clientSocket.send(sendPacket);
                }
            } catch (IOException e) {
                if (!logErrorNotified) {
                    System.err.println(
                            String.format("UDPRawLogger: could not send msg to IP Address %s, port %d. Exception: %s",
                                    destIPAddress.toString(), destPort, e));
                    logErrorNotified = true;
                }
            }
        }

        @Override
        public void flush() {
            // Nothing to do as we don't buffer messages.
        }

        @Override
        public void close() {
            if (clientSocket != null) {
                clientSocket.close();
                clientSocket = null;
            }
        }
    }
}
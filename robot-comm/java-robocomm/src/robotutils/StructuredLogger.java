//
// Structured logging classes. For logging and tracing that is intended to be consumed by other
// programs that analyze and visualize the log data.
// Created by Joseph M. Joy (https://github.com/josephmjoy)
package robotutils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class StructuredLogger  {
	
	final int PRI0 = 0; // Tag indicating pri0
    final int PRI1 = 2; // Tag indicating pri1
    final int PRI2 = 2; // Tag indicating pri2
    final String INFO = "INFO";
    final String TRACE = "TRACE";
    final String ERR = "ERR";
    final String WARN = "WARN";

    private final RawLogger[] rawLoggers;
    private final String rootName;
    private InternalLogger rootLog;
    private String sessionId;
    private long sessionStart;
    private boolean sessionStarted = false;
    private AtomicInteger seqNo = new AtomicInteger(0);
    
    // These are for scrubbing message type and message fields before logging.
    private static final Pattern BAD_NAME_PATTERN = Pattern.compile("[^-.\\w]");
    private static final Pattern BAD_MSG_PATTERN = Pattern.compile("\\n\\r");

    // Clients provide this to actually write log messages to some system like the file system
    // or network.
	public interface RawLogger {
		
		// Prepare to log a new session. For example, a file based logging system may
		// open a new file. SessionId will not contain illegal characters for file names, such as
		// slashes.
		void newSession(String sessionId);
		
		// Log a string message
		void log(int pri, String cat, String msg);
		
		// Flush the log to persistant storage if appropriate.
		void flush();
		
		// Handle a failed assertion (this failure has already been logged and flush() called).
		// One implementation is to simply call assert(false). Another is to throw some other kind
		// of exception.
		void assertionFailure(String s);
		
		// Close any open resources (no further calls to log or flush will follow the call to close)
		void close();

	}

	// The core logger interface - a hierarchy of logging objects can be built - for
	// each component/sub-component.
	public interface Logger {

		// These are reserved key names - don't use them for generating key-value
		// pairs:
		final String SESSION_ID = "_sid";
		final String SEQ_NO = "_sn";
		final String TIMESTAMP = "_ts";
		final String COMPONENT = "_co";
		final String PRI = "_pri"; // Priority: 0/1/2
		final String CAT = "_cat"; // CATEGORY: ERR/WARN/INFO
		final String TYPE = "_ty"; // Message type
		final String DEF_MSG = "_msg"; // Default message contents

		//
		// Standard message types
		//
		final String LOGGER = "LOGGER"; // Reserved for messages generated by logging system itself
		final String ASSERTFAIL = "ASSERT_FAIL"; // Message generated by loggedAssert
		final String OTHER = "OTHER"; // Unspecified message type
		// These next for are for the start/stop of initialization/deinitialization of the component
		final String INIT_START = "INIT_START";
		final String INIT_END = "INIT_END";
		final String DEINIT_START = "DEINIT_START";
		final String DEINIT_END = "DEINIT_END";

		// Creates a child logger with the specified component name.
		Logger newLogger(String component);

		void err(String s); //  Log an error - Pri 0
		void warn(String s); // Log a warning - Pri 0
		void info(String s); // Log some information - Pri 1
		void trace(String s); // Log potentially high-volume trace data - Pri 2
		void trace(String msgType, String s); // As above, with a custom message data type


		// These special-case logging methods are for recording each component's initialization
		// and deinitialization. They are all logged at pri 0.
		void logInitStart(String s);
		void logInitEnd(String s);
		void logDeinitStart(String s);
		void logDeinitEnd(String s);

		// If {cond} is false log an error, flush and call the assertion
		// failure handler. Nothing is logged if {cond} is true.
		void loggedAssert(boolean cond, String s);

		// Tracing is enabled by default, but may be paused/resumed
		// dynamically - useful for selectively tracing extremely verbose
		// data. This applies only to this log instance, NOT
		// to any children.
		void pauseTracing(); // stop logging
		void resumeTracing();// (re)start logging

		// Flush the ENTIRE log, not just this sub-component.
		void flush();

	}

	// Creates the containing logger object. This object can be used to 
	// create the hierarchy of Logger objects. (Start by calling beginSession and 
	// then getRootLog).
	public StructuredLogger(RawLogger _rawLogger, String _rootName) {
		this(new RawLogger[] {_rawLogger}, _rootName);
    }
	
	// Creates the containing logger object. This object can be used to 
	// create the hierarchy of Logger objects. (Start by calling beginSession and 
	// then getRootLog).
	public StructuredLogger(RawLogger[] _rawLoggers, String _rootName) {
		this.rawLoggers = _rawLoggers;
        this.rootName = _rootName;
    }
	
	// Get the root ("top level") logging object.
    public Logger getRootLog() {
        return getInternalRootLog();
    }
    
	// Get the root ("top level") logging object.
    private synchronized InternalLogger getInternalRootLog() {
        if (rootLog == null) {
            rootLog = new InternalLogger(rootName);
        }
        return rootLog;
    }

    // Begins the logging session. The Session timestamp is set.
    // Caller must ensure no other thread attempts to log concurrently with
    // this call - actual logging calls are not synchronized for performance
    // reasons.
    public synchronized void  beginSession(String sessionDescription) {
        assert(!this.sessionStarted);
        long startTime = System.currentTimeMillis();
        String sessionID = "" + startTime; // WAS String.format("%020d", startTime);
        for (RawLogger rl: rawLoggers) {
            rl.newSession(sessionId);
        }
        this.sessionId = sessionID;
        this.sessionStart  = startTime;
        this.sessionStarted = true;
        seqNo.set(0); // First logged sequence number in the session is 1.
        InternalLogger rootLog = getInternalRootLog();
        rootLog.pri0(Logger.LOGGER, sessionDescription + " session started.");
    }

    // Caller must encure no other thread attempts to log concurrently
    // with this thread - actual logging calls are not synchronized for
    // performance reasons.
    public synchronized void endSession() {
        assert(this.sessionStarted);
        InternalLogger rootLog = getInternalRootLog();
        rootLog.pri0(Logger.LOGGER, "Session ended.");
        this.sessionStarted = false;
        for (RawLogger rl: rawLoggers) {
	        rl.flush();
	        rl.close();
        }

    }

    // This private class implements each node of the Logger object hierarchy.
	private class InternalLogger implements Logger {

        final String component;
        boolean tracingEnabled = true;

        // {component} should be a short - 3-5 char - representation of the component.
        // The component hierarchy is represented using dotted notation, e.g.: root.a.b.c
        InternalLogger(String component) {
            this.component = scrubName(component); // Replace  ':' etc (these shouldn't be there) by '#'
        }

        // See the Logger interface definition for documentation on
        // these overridden methods.
        
        @Override
		public InternalLogger newLogger(String component) {
			return new InternalLogger(rootName + "." + component);
		}


		@Override
		public void trace(String msgType, String s) {
			if (tracingEnabled) {
			    rawLog(PRI2, TRACE, scrubName(msgType), s);
			}
		}

		@Override
		public void err(String s) {
			rawLog(PRI0, ERR, OTHER, s);

		}

		@Override
		public void warn(String s) {
			rawLog(PRI0, WARN, OTHER, s);

		}

		@Override
		public void info(String s) {
			rawLog(PRI1, INFO, OTHER, s);

		}

		@Override
		public void trace(String s) {
			trace(OTHER, s);

		}

		@Override
		public void logInitStart(String s) {
			pri0(INIT_START, s);

		}

		@Override
		public void logInitEnd(String s) {
			pri0(INIT_END, s);

		}

		@Override
		public void logDeinitStart(String s) {
			pri0(DEINIT_END, s);
		}

		@Override
		public void logDeinitEnd(String s) {
			pri0(DEINIT_END, s);

		}

		@Override
		public void pauseTracing() {
            pri0(LOGGER, "Tracing paused.");
            tracingEnabled = false;
		}

		@Override
		public void resumeTracing() {
			// TODO Auto-generated method stub
			pri0(LOGGER, "Tracing resumed.");
			tracingEnabled = true;

		}

		@Override
		public void loggedAssert(boolean cond, String s) {
			if (!cond) {
                    rawLog(PRI0, ERR, ASSERTFAIL, s);
                    this.flush();
                    for (RawLogger rl: rawLoggers) {
                        rl.assertionFailure(s); // TODO: we will call this handler from each raw logger - probably the first one will break.
                    }
            }

		}

		@Override
		public void flush() {
            if (sessionStarted) {
            	for (RawLogger rl: rawLoggers) {
                    rl.flush();
            	}
            }     
		}
		
		// Not for use outside the containing class.
        void pri0(String msgType, String s) {
            rawLog(PRI0, INFO, msgType, s);
        }

        private void rawLog(int pri, String cat, String msgType, String msg) {
            // Example:
            //  _sid:989, _sn:1, _ts: 120, _co: .b, _pri:1, _sev:INFO, _ty:OTHER, Hello world!
            msgType = scrubName(msgType);
            msg = scrubMessage(msg);
            // As a special case, if msg contains no colons, we prefix a special _msg key.
            if (msg.indexOf(StructuredMessageMapper.COLON)==-1) {
            	msg = DEF_MSG + StructuredMessageMapper.COLON + msg;
            }
            
            int curSeq = seqNo.incrementAndGet();
            long timestamp = System.currentTimeMillis() - sessionStart;
            String output = String.format("%s:%s %s:%s %s:%s %s:%s %s:%s %s:%s %s:%s %s",
                    Logger.SESSION_ID, sessionId,
                    Logger.SEQ_NO, curSeq,
                    Logger.TIMESTAMP, timestamp,
                    Logger.COMPONENT, component,
                    Logger.PRI, pri,
                    Logger.CAT, cat,
                    Logger.TYPE, msgType,
                    msg
                    );
            if (sessionStarted) {
            	for (RawLogger rl: rawLoggers) {
                    rl.log(pri, cat, output);
            	}
            }
        }

	}
        
		
    // Replace invalid chars by a '#'
    private static String scrubName(String msgType) {
    	// Presumably this is faster than using a Regex? Not sure.
    	return BAD_NAME_PATTERN.matcher(msgType).replaceAll("#");
    }
    
    // Replace invalid chars by a '#'
    private static String scrubMessage(String msgType) {
        return BAD_MSG_PATTERN.matcher(msgType).replaceAll("#");
    }


}

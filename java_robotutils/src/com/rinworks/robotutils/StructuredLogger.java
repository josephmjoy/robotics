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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class StructuredLogger  {
	
	final static int PRI0 = 0; // Tag indicating pri0
    final static int PRI1 = 2; // Tag indicating pri1
    final static int PRI2 = 2; // Tag indicating pri2
    final static String INFO = "INFO";
    final static String TRACE = "TRACE";
    final static String ERR = "ERR";
    final static String WARN = "WARN";

    private final RawLogger[] rawLoggers;
    private final String rootName;
    private final LogImplementation defaultLog;
    private String sessionId;
    private long sessionStart;
    private boolean sessionStarted = false;
    private boolean sessionEnded = false;
    private AtomicInteger seqNo = new AtomicInteger(0);
    private Consumer<String> assertionFailureHandler = null;
    
    // These are for scrubbing message type and message fields before logging.
    private static final Pattern BAD_NAME_PATTERN = Pattern.compile("[^-.\\w]");
    private static final Pattern BAD_MSG_PATTERN = Pattern.compile("\\n\\r");

    // Clients provide this to actually write log messages to some system like the file system
    // or network.
	public interface RawLogger {
		
		// Prepare to log a new session. For example, a file based logging system may
		// open a new file. SessionId will not contain illegal characters for file names, such as
		// slashes. This method will be called only once - when the owning structured logging
		// object's beginSession method is called.
		void beginSession(String sessionId);
		
		// Log a string message
		void log(int pri, String cat, String msg);
		
		// Flush the log to persistent storage if appropriate.
		void flush();
		
		// Close any open resources (no further calls to log or flush will follow the call to close)
		void close();

	}

	// The core logger interface - multiple log objects can be built - for
	// each component/sub-component or even transient logging tasks.
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
		// Reserved Messages Types - reserved for messages generated by the logging system itself.
		// These all begin with an underscore.
		//
		final String LOG_SESSION_START = "_LOG_SESSION_STARTED"; // Reserved for messages generated by logging system itself
		final String LOG_SESSION_END = "_LOG_SESSION_ENDED";
		final String LOG_TRACING_PAUSED = "_LOG_TRACING_PAUSED";
		final String LOG_TRACING_RESUMED = "_LOG_TRACING_RESUMED";
		final String ASSERTFAIL = "_ASSERTION_FAILURE"; // Message generated by loggedAssert
		final String OTHER = "_OTHER"; // Unspecified user message type
		
		// Recommended message types. These are not used by the logging system itself, but they are
		// RECOMMENDED to be used to log common events.
		// Log the initialization/deinitialization of a component
		final String INIT_START = "INIT_START";
		final String INIT_END = "INIT_END";
		final String DEINIT_START = "DEINIT_START";
		final String DEINIT_END = "DEINIT_END";


		//
		// Actual logging methods - logged with message type "_OTHER"
		//
		void err(String s); //  Log an error - Pri 0
		void warn(String s); // Log a warning - Pri 1
		void info(String s); // Log some information - Pri 1
		
		
		// The same logging methods, with a user-suppled message type.
		void err(String msgType, String s);
		void warn(String msgType, String s);
		void info(String msgType, String s);

		// Traces are like the earlier logging methods, except they can be dynamically
		// enabled or disabled using the pauseTracing/resumeTracing methods.
		void trace(String s); // Log potentially high-volume trace data - Pri 2
		void trace(String msgType, String s); // As above, with a user-defined message data type

		// FUTURE: Concept of an optional 'location specifier' integer that is set to a random integer that is with
		// very high priority unique across the source code - to be able to quickly identify the source code where the log method
		// was invoked. In Python it could be a named parameter, ls=0 so we don't cause an explosion in the number
		// of logging statements.
		
		// If {cond} is false log an error, flush the log. If there is an assertion
		// failure handler associated with the structured logger, the handler is called.
		// The handler may be set by calling setAssertionFailureHandler.
		// 
		void loggedAssert(boolean cond, String s);

		// Tracing is enabled by default, but may be paused/resumed
		// dynamically - useful for selectively tracing extremely verbose
		// data. This applies only to this log instance.
		void pauseTracing(); // stop logging
		void resumeTracing();// (re)start logging
		
		
		// Starts adding a relative time stamp. Subsequent logging will include a _RTS key whose value is the 
		// time in milliseconds that has elapsed since this call was invoked. This applies only to this
		// log instance.
		void startRTS();
		
		// Stops adding the relative stamps for this log instance.
		void stopRTS();
		
		// The following methods adds key (the 'tag') that gets inserted into
		// every log message made from this particular log instance.
		// Tags must be composed entirely of non-whitespace characters and can do not
        // include the ':' (colon) character.
		// [FUTURE: Special 'moustache' tags like would get dynamic values, like {TID} would set TID=<thread ID>]
		void addTag(String tag); // A tag with an empty value. Can represent boolean conditions.
		void addTag(String tag, String value); // A tag with the specified value (which could be an empty string).
		
		// Removes a previously added tag. Attempting to remove a null, empty or nonexistant tag is silently ignored.
		void removeTag(String tag);

		// Flush the ENTIRE log, not just this sub-component.
		void flush();
		
		// Creates a new log object. This is equivalent to calling the root
		// StructureLoggerObject's newLog method - there is no special relationship between
		// the current instance and the newly created logs. A hierarchical relationship can be established
		// by following a suitable naming convention such as dotted-namespace notation.
		Log newLog(String name);
		
		// FUTURE
		// Hidden tags whose existence can be checked (or rather asserted to be present or absent) at a future time.
		// Perhaps these could be added as named parameters in Python, otherwise additional methods to add, assert and remove tags.)

	}

	// Creates the containing logger object. This object can be used to 
	// create the hierarchy of Logger objects. (Start by calling beginSession and 
	// then getRootLog). 
	//
	// {_assertionFailureHandler} is an optional handler of assertion failures - it is
	// called if the call to loggedAssert fails the assertion tes (failure has already been logged and flush() called)).
	// One implementation is to simply call assert(false) after an error message to debug putput. Another is to throw an
	// exception. WARNING: Will be invoked even if there is no active session.
	public StructuredLogger(RawLogger _rawLogger, String _rootName) {
		this(new RawLogger[] {_rawLogger}, _rootName);
    }
	
	// This version takes an array of rawLoggers so that logging output may be piped to multiple logger
	// sinks.
	public StructuredLogger(RawLogger[] _rawLoggers, String _rootName) {
		this.rawLoggers = _rawLoggers;
        this.rootName = _rootName;
        this.defaultLog = this.commonNewLog(_rootName);
    }

	// Consolidates calls to create a new log objects, incase we want to do something more
	// like keep a list of logs. At present we don't keep a global list of allocated log objects.
	private LogImplementation commonNewLog(String name) {
		return new LogImplementation(name);
	}

	// Updates the assertion failure handler.
	// The default handler is null, which means that assertion failures are logged but
	// otherwise no action is taken.
	// Note that there is no thread synchronization in this update - so it's best
	// to set this up before calling beginLogging.
	public void setAsseretionFailureHandler(Consumer<String> _assertionFailureHandler) {
		this.assertionFailureHandler = _assertionFailureHandler;
	}

    

    // Begins the logging session. The Session timestamp is set.
    // Caller must ensure no other thread attempts to log concurrently with
    // this call - actual logging calls are not synchronized for performance
    // reasons.
    public synchronized void  beginLogging() {
        assert(!this.sessionStarted && !this.sessionEnded);
        long startTime = System.currentTimeMillis();
        String sessionID = "" + startTime; // WAS String.format("%020d", startTime);

        this.sessionId = sessionID;
        this.sessionStart  = startTime;
        this.sessionStarted = true;
        seqNo.set(0); // First logged sequence number in the session is 1.
        for (RawLogger rl: rawLoggers) {
            rl.beginSession(sessionId);
        }
        defaultLog.pri0(Log.LOG_SESSION_START, this.rootName);
    }

    // Caller must ensure no other thread attempts to log concurrently
    // with this thread - actual logging calls are not synchronized for
    // performance reasons.
    // WARNING - the StructuredLogger can only do a single session in its lifetime.
    // Once the session has been ended a new session can not be started.
    public synchronized void endLogging() {
        assert(this.sessionStarted);
        defaultLog.pri0(Log.LOG_SESSION_END, rootName);
        this.sessionStarted = false;
        this.sessionEnded = true;
        for (RawLogger rl: rawLoggers) {
	        rl.flush();
	        rl.close();
        }

    }

	
	// The base logger support some simple logging functions for
	// convenience. Look at the Log interface methods for full documentation
	void err(String s) {
		this.defaultLog.err(s);
	}
	
	void warn(String s) {
		this.defaultLog.warn(s);
	}
	
	void info(String s) {
		this.defaultLog.info(s);
	}
	
	void flush() {
		this.defaultLog.flush();
	}
	
	
	// Get the root ("top level") log object, which provides a much richer
	// set of logging methods
    public Log defaultLog() {
        return this.defaultLog;
    }
    
    

    
    
    // This private class implements a Log object
	private class LogImplementation implements Log {
        final String component;
        boolean tracingEnabled = true;

        // {component} should be a short - 3-5 char - representation of the component.
        // The component hierarchy is represented using dotted notation, e.g.: root.a.b.c
        LogImplementation(String component) {
            this.component = scrubName(component); // Replace  ':' etc (these shouldn't be there) by '#'
        }

        // See the Logger interface definition for documentation on
        // these overridden methods.
        
        @Override
		public LogImplementation newLog(String component) {
        	// Note: commonNewLog is actually a method of the *containing*
        	// class - an instance of StructuredLogger.
			return commonNewLog(rootName + "." + component);
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

		//@Override
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
			// Note that we will call the assertionFailureHandler even if there is the logging session is not active.
			// However if there is no session, there will be no logging and flushing (those methods below will have no effect).
			if (!cond) {
                    rawLog(PRI0, ERR, ASSERTFAIL, s);
                    this.flush();
                    if (assertionFailureHandler!=null) {
                    	assertionFailureHandler.accept(s);
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
        	
        	// Note that sessionStarted is defined in the containing class - StructuredLogger!
        	if (!sessionStarted) {
        		return; //               ******************** EARLY RETURN ******************
        	}
        	
            msgType = scrubName(msgType);
            msg = scrubMessage(msg);
            // As a special case, if msg contains no colons, we prefix a special _msg key.
            if (msg.indexOf(StructuredMessageMapper.COLON)==-1) {
            	msg = DEF_MSG + StructuredMessageMapper.COLON + msg;
            }
            
            int curSeq = seqNo.incrementAndGet();
            long timestamp = System.currentTimeMillis() - sessionStart;
            String output = String.format("%s:%s %s:%s %s:%s %s:%s %s:%s %s:%s %s:%s %s",
                    Log.SESSION_ID, sessionId,
                    Log.SEQ_NO, curSeq,
                    Log.TIMESTAMP, timestamp,
                    Log.COMPONENT, component,
                    Log.PRI, pri,
                    Log.CAT, cat,
                    Log.TYPE, msgType,
                    msg
                    );
            if (sessionStarted) {
            	for (RawLogger rl: rawLoggers) {
                    rl.log(pri, cat, output);
            	}
            }
        }

		
		@Override
		public void startRTS() {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void stopRTS() {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void addTag(String tag) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void addTag(String tag, String value) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void removeTag(String tag) {
			// TODO Auto-generated method stub
			
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

    private static class FileRawLogger implements RawLogger {
    	final boolean perSessionLog;
    	final boolean append;
    	final File logDirectory;
    	File logFile;
    	final String prefix;
    	final String suffix;
    	BufferedWriter out;
    	boolean logErrorNotified; // we generate on err msg on write error.
    

    	// Logger that creates per-session log files
		public FileRawLogger(File _logDirectory, String _prefix, String _suffix, boolean _append) {
			perSessionLog = true;
			logDirectory = _logDirectory;
			prefix = _prefix;
			suffix = _suffix;
			append = _append;
			// We don't throw any exceptions on error, just write the error to the err console.
			if (!logDirectory.canWrite()) {
				System.err.println(String.format(
						"FileRawLogger: log directory {%s} cannot be written to.", 
						logDirectory.getAbsolutePath()
						));
			}
		}

		// Logger that logs to a single log file
		public FileRawLogger(File _logFile, boolean _append) {
			perSessionLog = false;
			logDirectory = null;
			logFile = _logFile;
			prefix = null;
			suffix = null;
			append = _append;
		}

		
		@Override
		public void beginSession(String sessionId) {
			
			if (perSessionLog) {
				String name = prefix + sessionId + suffix;
				logFile = new File(logDirectory, name);
			}
			
			try {

			    out = new BufferedWriter(new FileWriter(logFile, append));
			}
			catch (IOException e) {
				System.err.println(String.format(
						"FileRawLogger: Cannot log. Could not create/open log file {%s}. Exception: %s", 
						logDirectory.getAbsolutePath(),
						e));
				out = null;
			}
		}

		@Override
		public void log(int pri, String cat, String msg) {
			try {
				if (out !=null ) {
					out.write(msg, 0, msg.length());
					out.newLine();
				}
			}
			catch (IOException e) {
				if (!logErrorNotified) {
				    System.err.println(String.format("FileRawLogger: could not write to log file {%s}. Exception: %s",
				    		logFile.getAbsolutePath(), 
				    		e));
				    logErrorNotified = true;
				}
			}
		}

		@Override
		public void flush() {
			try {
				if (out != null) {
					out.flush();
				}
			}
			catch (IOException e) {
			    System.err.println(String.format("FileRawLogger: could not flush log file {%s}. Exception: %s",
			    		logFile.getAbsolutePath(), 
			    		e));		
			}
			
		}

		@Override
		public void close() {
			try {
				if (out != null) {
					out.close();
				}
			}
			catch (IOException e) {
			    System.err.println(String.format("FileRawLogger: could not close log file {%s}. Exception: %s",
			    		logFile.getAbsolutePath(), 
			    		e));			
			}
		}
    	
    }
    
    private static class UDPRawLogger implements RawLogger {
    	final String destAddress;
    	final int destPort;
    	boolean logErrorNotified; // we generate one err msg if there is an error message on write..
    	DatagramSocket clientSocket;
        InetAddress destIPAddress;
        boolean canLog = false;
    


		// Logger that logs by sending UDP traffic to the specified address and port.
		public UDPRawLogger(String _address, int _port) {
			destAddress = _address;
			destPort = _port;
		}

		
		@Override
		public void beginSession(String sessionId) {			
			
			try {
		    	clientSocket = new DatagramSocket();
		        destIPAddress = InetAddress.getByName(destAddress);
			}
			catch (SocketException e) {
				System.err.println("UDPRawLogger: Cannot log. Could not create DatagramSocket. Exception: " + e);
			}
			catch (UnknownHostException e) {
				System.err.println("UDPRawLogger: Cannot log. Could not resolve address " + destAddress + ". Exception: " + e);
			}
			System.out.println(String.format("UDPRawLogger: logging session %s to IP Address %s, port %d", sessionId, destIPAddress, destPort));
			canLog = true;
		}

		@Override
		public void log(int pri, String cat, String msg) {
			try {
				if (canLog) {
					byte[] sendData = msg.getBytes();
				    DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, destIPAddress, destPort);
				    clientSocket.send(sendPacket);				
				}
			}
			catch (IOException e) {
				if (!logErrorNotified) {
				    System.err.println(String.format("UDPRawLogger: could not send msg to IP Address %s, port %d. Exception: %s",
				    		destIPAddress.toString(), 
				    		destPort,
				    		e));
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

    /**
     * Creates a logger that generates per-session log files of the form {perfix}{session id}{suffix}.
     * No IOExceptions are thrown. Instead error messages are written to System.err.
     * @param logDirectory - directory where log files will reside.
     * @param prefix - filename prefix
     * @param suffix - filename suffix
     * @param append - true: append to the file if it exist; false: overwrite the file if it exists.
     * @return A StructuredLogger.Logger object that may be passed into a StructuredLogger constructor
     */
   public static RawLogger createFileLogger(File logDirectory, String prefix, String suffix, boolean append) {
	   FileRawLogger fileLogger = new FileRawLogger(logDirectory, prefix, suffix, append);
	   return fileLogger;
	
   }
   
   /**
    * Creates a logger that logs multiple sessions to a single file.
    * No IOExceptions are thrown. Instead error messages are written to System.err.
    * @param logFile - File object representing log file path.
    * @param append - true: append to the file if it exist; false: overwrite the file if it exists.
    * @return A StructuredLogger.Logger object that may be passed into a StructuredLogger constructor
    */
   public static RawLogger createFileLogger(File logFile, boolean append) {
	   FileRawLogger fileLogger = new FileRawLogger(logFile, append);
	   return fileLogger;
	
   }
   
   /**
    * Creates a logger that transmits log messages as UDP packets to the specified destination.
    * @param address - Destination host name or IP Address
    * @param port - Destination port.
    * @return A StructuredLogger.Logger object that may be passed into a StructuredLogger constructor
    */
   public static RawLogger createUDPLogger(String address, int port) {
	   UDPRawLogger fileLogger = new UDPRawLogger(address, port);
	   return fileLogger;
   }

}

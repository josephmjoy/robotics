# StructuredLogger
This class provides thread-safe logging of 'structured text' - Strings of the form "key1: value1
key2:value2". The client provides the low-level log consumers that implement
the StructuredLogger.RawLogger interface.
## StructuredLogger - Simple Usage
First we create a raw logger that will consume the low-level log messages
produced by the structured logger. We create a temporary file and pass that to
utility method `StructuredLogger.createFileRawLogger`.
```Java
File logFile = File.createTempFile("testLog", ".txt");
int maxSize = 1000000; // max size in bytes the file is allowed to grow.
StructuredLogger.RawLogger rawLogger = StructuredLogger.createFileRawLogger(logFile, maxSize, null);
```
Then we create a structured logger. Typically there is just one of these per
system.
```Java
StructuredLogger baseLogger = new StructuredLogger(rawLogger, "MY_SYSTEM");
```
When we are ready to start logging, we begin the logging session. This is when
external resources (like files) are opened.
```Java
baseLogger.beginLogging();
```
Then we log! Info messages are logged with priority 1 (a tag "_PRI:1" is inserted to the
logged message).
```Java
baseLogger.info("Logging an informational message");
```
Warning message are also logged with priority 1.
```Java  
baseLogger.warn("Logging an error");
```
Error messages are logged with priority 0.
```Java
baseLogger.err("Logging an error");
```
When done, we end logging.
```Java
baseLogger.endLogging();
```

# Structure of Logged Messages
 The primary purpose of structured logging is to generate log files that can be easily parsed by analysis programs. If we open the temporary file created by the session above, we will see that it contains lines that look like:
```
_sid:1517976498636 _sn:1 _ts:0 _co:MY_SYSTEM _pri:0 _cat:INFO _ty:_LOG_SESSION_STARTED _msg: rootName:MY_SYSTEM maxBuffered:1000 autoFlushPeriod:1000
_sid:1517976498636 _sn:2 _ts:0 _co:MY_SYSTEM _pri:1 _cat:INFO _ty:_OTHER _msg: Logging an informational message
_sid:1517976498636 _sn:3 _ts:0 _co:MY_SYSTEM _pri:1 _cat:WARN _ty:_OTHER _msg: Logging a warning
_sid:1517976498636 _sn:4 _ts:0 _co:MY_SYSTEM _pri:0 _cat:ERR _ty:_OTHER _msg: Logging an error
_sid:1517976498636 _sn:5 _ts:0 _co:MY_SYSTEM _pri:0 _cat:INFO _ty:_LOG_SESSION_ENDED _msg: rootName:MY_SYSTEM
```
Each line corresponds to one logged message. The message has broadly speaking the form "key1: val1 key2:val2 key3:val3...", i.e., a sequence of key-value pairs.

Tag | Example Value | Description
--- | --- | ---
_sid | 1517976498636 | Session ID. This is the time stamp at the start of the session -- when `beginLogging()` was called. The value is the number of milliseconds since the Unix epoch.
_sn | 1 | Log sequence number. These increment by exactly one for each message logged to a particular instance of `StructuredLogger`. If messages are discarded because the rate of logging is too high, gaps will show up in these sequence numbers. If multiple threads are logging concurrently, messages may not show up in order of sequence number, though this should rarely happen.
_ts | 0 | Milliseconds since the start of the logging session (since `beginLogging()` was called).
_co I MY_SYSTEM | Logging component name. The default Log has the component name set to the `rootName` passed to the `StructuredLogger` constructor. If a `StructuredLogger.Logger` instance is created by calling `StructuredLogger.newLog` or the equivalent `StructuredLogger.Logger.newLog` specifying name _name_, messages submitted to it will have have _co set to _rootName_ + '.' name.

Individual messages cannot have newlines, although one can embed <br> or other tags that log viewers may honor, though that is out of the scope of `StructuredLogger`. An attempt to log messages with embedded newlines results in all newlines replaced by the '#' character. Also, the colon character ':' must  not be present in any value portion of the message. Attempting to do so will result in incorrect parsing of that specific log message, though other messages will be unaffected.


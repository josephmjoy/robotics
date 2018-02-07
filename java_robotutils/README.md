# StructuredLogger
This class provides thread-safe logging of 'structured text' - Strings of the form "key1: value1
key2:value2". The client provides the low-level log consumers that implement
the StructuredLogger.RawLogger interface.
## StructuredLogger - Simple Usage
First we create a raw logger that will consume the low-level log messages
produced by the structured logger. We create a temporary file and pass that to
utility method StructuredLogger.createFileRawLogger.
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
When we are ready to start login, we begin the logging session. This is when
external resources (like files) are opened.
```Java
baseLogger.beginLogging();
```
Then we log! Info messages are logged with priority 1 (a tag "_PRI:1" is inserted to the
logged message)
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
If we open the temporary file created by the session above, we will see that it contains lines
that look like:
```
_sid:1517976498636 _sn:1 _ts:0 _co:MY_SYSTEM _pri:0 _cat:INFO _ty:_LOG_SESSION_STARTED _msg: rootName:MY_SYSTEM maxBuffered:1000 autoFlushPeriod:1000
_sid:1517976498636 _sn:2 _ts:0 _co:MY_SYSTEM _pri:1 _cat:INFO _ty:_OTHER _msg: Logging an informational message
_sid:1517976498636 _sn:3 _ts:0 _co:MY_SYSTEM _pri:1 _cat:WARN _ty:_OTHER _msg: Logging a warning
_sid:1517976498636 _sn:4 _ts:0 _co:MY_SYSTEM _pri:0 _cat:ERR _ty:_OTHER _msg: Logging an error
_sid:1517976498636 _sn:5 _ts:0 _co:MY_SYSTEM _pri:0 _cat:INFO _ty:_LOG_SESSION_ENDED _msg: rootName:MY_SYSTEM
```
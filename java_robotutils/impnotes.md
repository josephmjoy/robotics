#Implementation Notes for java_robotutils
These are informal notes and TODO lists for the project.

#TODO
1. StructuredLogger: threadsafe logging. Currently it's not threadsafe, in that
   logging from multiple threads can result in corrupted output messages. The
   implementation plan is to use a non-blocking queue to buffer the raw 
   text messages. A background worker (either in the context of a periodic timer
   or an executor task) picks these upp and sends to the raw logs and flushes
   them.
1. StructuredLogger: Make sure that it can never throw an exception or assertion
   failure
1. [POSTPONED-unclear if this is a benefit as bigger packets may have higher
   chance of failed transmission] LOG_SESSION_STARTStructuredLogger: UDP rawlogger should bundle multiple log messages into
   a single UDP packet - as much as can fit. Given the overhead of sending and
   receiving a UDP packet, we should pack as much in there as we can.


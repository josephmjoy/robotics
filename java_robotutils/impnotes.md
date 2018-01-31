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


# Jan 30, 2018 Design Note on Threadsafe and background Logging
Goals:
1. Thread safe - integrity of individual log messages is preserved. Log messages submitted in the context of any one thread is logged sequentially.
2. Max buffered limit is honored on a per-raw log basis. If any individual raw log's max buffered  message is reached, subsequent messages WILL be discarded. A log message is generated periodically if any messages were discarded in the previous period. This message identifies the raw log(s) and the number of deleted messages.
3. No attempt is made to generate and buffer a log message if no raw logs will accept it. IMP: before the message is generated, each raw log is queried to see if it will accept it based on (pri, cat, type).
4. Raw log's log() message is never called in the context of a client's logging method (info, trace, etc.). This is so that there is no unknown (and potentially) high in-line cost while logging.
2. StructuredLogger.flush will trigger immediate flushing of buffered messages inn the background. It can 
 potentially (TBD) take a wait parameter - where it will wait for the message queues to be empty (or have
 flushed all the messages buffered at the start of the call to flush()).
5. There is a max overhead of one background thread per StructuredLoggine object. IMP: THis is for a timer object.

Design:
1. IMP: All writing to raw loggers MUST be done by a single thread, else messages can be corrupted when writing to
   the raw logs. Therefore, flush must get this logging thread to write in the background and if needed wait until
   all message buffers are empty (though the problem with that is that in the mean time more messages could be
   logged). So perhaps check the sequence number and make sure that the 'last flushed sequence number' is at
   least as great.
1. A RawLogBuffer object is maintained, one for each raw log. This object contains:
    -- Pointer to the raw log.
    -- A ConcurrentLinkedQueue of strings that contains the buffer messages for that log.
    -- An atomic counter of deleted messages since the last logged 'messages were deleted' message was logged.
2. StructuredLogger.rawLog does the following for each raw log:
  - Check if it will accept the message by calling filter(pri, cat, type). If no it ignores the message.
  - If accepted, checks if that raw log's max buffered message limit has been reached. If so, it increments the 'deleted messages' atomic counter and quits - the message will be discarded. Else, if needed the message to be
  logged is created (this is done lazily, so subsequent raw logs will use this message). And then the message is
  logged to that log's message buffer.
  -- If any raw log's message buffer is approaching capacity, a one-shot timeer is scheduled fire
   "immediately" to clear the backlog. The fact that this was done is saved so that each subsequent message does
    not trigger the same behavior. IMP: A reference to the oneshot timer task is preserved (the timer task itself
    will remove this reference when done - if it's the same object).
3. A timer is setup to trigger periodic submission of buffered messages to the raw logs. It will flush each raw log
   after sending off all outstanding buffered messages from that log (in the mean time more messages could get
   added to the buffer by another thread).
4. On close  
    

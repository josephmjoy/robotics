#Implementation Notes for java_robotutils
These are informal notes and TODO lists for the project.

#TODO
1. For roboRIO config file, under "logging": add "trace: [logname1, logname2, loname3]", and 
  set these up during init - basically disable tracing on all logs except the list above. This is
  a pragmatic thing to do. For example RobotComm has tracing, but it should obviously not be enabled
  by default. But adding "trace: [robotcomm]" would enable it in the config file as required.
1. StructuredLogger AND StructuredMessageMapper: YAML REQUIRES a spacec after ':' and ','. We should
   strongly consider adopting this same guideline for parsing!
1. SUGGESTION: MessageMapper: strip commas at end of tags and values,
   to allow for optional commas to be added.
1. StructuredLogger: Keep track of % of timer period taken up by processing of periodic and one-shot tasks
1. StructuredLogger: Make sure that it can never throw an exception or assertion
   failure
1. [POSTPONED-unclear if this is a benefit as bigger packets may have higher
   chance of failed transmission] LOG_SESSION_STARTStructuredLogger: UDP rawlogger should bundle multiple log messages into
   a single UDP packet - as much as can fit. Given the overhead of sending and
   receiving a UDP packet, we should pack as much in there as we can.

# Feb 14, 2018 RobotComm Protocol - Laying out Command-Response sequence
*Client*:
1. Prepares SentCommand, sends CMD, keeps SentCommand in pendingSentCommands mam. If [NEW] addToCompletionQueue is TRUE, it will
   note this fact internally.
2. On receiving RESP, if still in map it updates it's status, and if not still pending, it removes itself from from map, and [new] if addToCompletionQueue, 
   it adds it to the completion queue.    Whether in map or not, and if the RESP indicates the command is not pending, it always immediately responds with CMDRESPACK. 
   So it will never respond with CMDRESPACK in response to CMDRESP whose status is still pending.
3. Every CLIENT_TIMEOUT seconds (randomly between 500ms and 1500ms) it resends CMD for all SendCommands in map. Note that the server will respond with a CMDRESP with pending status if it 
   is already processing this command.
4. Client can call pollCommandCompletionQueue (non blocking) to get the next completed SentCommand.
4b. Client is expected to service the completion queue if it has sent commands with addToCompletionQueue set. Otherwise the completion queue will expand
   without bound. IMPNOTE: at some point we can log an error and start dropping new sendCommand requests on the floor.
5. If client calls SendCommand.cancel() for a command that has addToCompletionQueue set, it WILL get immediately added to the completion queue. If it is still pending, it's status
   is changed to SATUS_CLIENT_CANCELED (so pending state - STATUS_SUBMITTED/STATUS_REMOTE_RECIVED/STATUS_REMOTE_COMPUTING - is lost).
   
*Server*:
1. On receiving a CMD, it checks it's recvCommandsMap. If if already exists, it immediately responds with a CMDRESP with the updated status (which may be pending/completed.)
   If it does not exist, it atomically creates a ReceivedCommand and adds it to the pendingRecvCommands queue.
2. If the user (server) calls ReceiveCommand.respond(), it check the state - if already completed or cancelled(?) it does nothing. Else it  updates completion status
   and response info (type, resp message body), and immediately sends a CMDRESP with latest status. [NEW] It DOES keep completed commands in a queue - see below.
3. On receiving a CMDRESPACK, if the command exists in it's recvCommandsMap AND it is completed, [NEW] it is NOT removed immediately - instead  it is marked for early deletion. It is not
     deleted immediately to guard against duplicate and delayed CMD messages arriving for the same command instance (same CmdId).
    If ReceivedCommand is it is NOT in a completed state, this suggests a bogus CMDRESPACK, and  the CMDRESP will be ignored.
4. Managing the completetdRecvCommands queue. After considering several strategies, the one that is most forgiving is to base it on queue lengths. Every Nth (say 1000) completed items added to
completedRecvCommaands queue, we perform a cleanup action. This cleanup action iterates through all the items in the queue, oldest (head)to last. We first run through and count the number of 'early deletion' nodes and 'remaining' nodes. If early deletion count > MAX_EARLY_DELETION, we delete MAX_EARLY_DELETION/2 of these. Once that is done, if total length is greater than MAX_TOTAL_COMPLETED, we go and simply delete MAX_TOTAL_COMPLETED/2 of these oldest first - a simple call to poll() to delete up to MAX_TOTAL_COMPLETED/2 will suffice here. This approach uses no worker tasks and no arbitrary timeout values. Nominal values: MAX_EARLY_DELETION = 1000, MAX_TOTAL_COMPLETED = 10,000. This does require an atomic counter for completed commands so we can know when to trigger the check (determining the length of the queue is an O(N) operation, so we obviously can't do that on each queued packet!).

# Feb 13, 2018 Design Note - on Testing RobotComm transport
- Unit tests should have a version of MessageHeader that can also create messed up versions of the header.
- Test loopback transport should be enhanced to lose a fraction of packets, to delay random packets a random time (will also cause packet reordering).
- Potentially virtualize getCurrentMillis so we can mess with time.

# Feb 12A, 2018 Design Note - RobotComm Message Format 
- receive msg header: 
    - signature: a magic aphanumeric string such as 1309JHI
    - channel: Alphanumeric string identifying channel.
    -type: string identifying message type: One of MSG, CMD, CMDRESP
    -user type: string identifying _client_ type for the message/command/response
      (se Design Note Feb 12C, 2018 below]. MUST NOT contain a ',' - we check
      for this will thow an argument exception if it contains a comma. More strict
      version: keep it to no whitespaces and commas.
    - commandInstance: Long integer identifying command instance. If the same command
       is sent multiple times awaiting a response, it will have the same 
       commandInstance. IMP: (session start time curMillis) + atomic seqNo.
    - Command status: String, one of OK, PENDING, REJECTED
        OK - command has completed
        PENDING - remote node says that it is waiting for result of command.
        REJECTED - remote node says that for whatever reason (typically there is
        no one listening on this channel, this command is rejected).
   Examples
    - "1309JHI,MY_CHANNEL,MSG,MY_MSG_TYPE"
    - "1309JHI,MY_CHANNEL,CMD,MY_COMMAND_TYPE,0x2888AB89"
    - "1309JHI,MY_CHANNEL,CMDRESP,MY_RESPONSE_TYPE,0x2888AB89,OK"

    Implementation notes:
     1. Check message starts with <magic>+','. Reject (with trace msg) otherwise.
     2. Reject with trace if it contains whitespace.
     3. Split on ','. No need to trim as there are is no white space.
     4. Use hard-coded offsets based on the spec.
     

# Feb 12B, 2018 Design Note- RobotComm Reseive/Receive Command/Send/Channel Semantics
- Decided to consolidate all into a Channel, so got rid of "Server" as a distinct type. This was going
  back to an earlier design. Main reason is the realization that a channel need not be bound to
  a remote node - it can be optionally bound to a remote node. This gives the flexibility of putting
  the client in charge of what it wants and also not having to have to juggle different kinds of objects
  when communicating with a single remote destination - which is the common case (e.g. main processor
  communicating with a secondary processor.)
- As part of the above consolidation, got rid of the concept of Mode (READONLY/WRITEONLY/READWRITE)
  specified when constructing a Channel. Now receiving commands and messages can be enabled on the
  fly.
- As part of the above consolidation, only one channel may be created with a given name. This means that
if a sender wishes to send to multiple endpoints on the same channel, only one Channel should be 
created and the channel should not be bound to any endpoint.
 I (JMJ) considered multiple receivers - each will get a copy of
  any received message, and command instances have unique sequence numbers so command responses can be   
  delivered to the receiver that submitted the command. HOWEVER, managing this would add complexity and  
   there is no anticipated need for multiple listeners on the same channel.
- If a channel is bound to a remote endpoint, no communication is accepted (commands and messages
  received) from any other node, even when addressed to this channel. Sending to another address is
  still allowed, however (send has two overridden methods, one specifying an address).

# Feb 12C, 2018 Design Note- RobotComm Adding MessgeType to messages
-  Decided to add a string message type to client-supplied messages. This type will be available in
   received messages, commands and responses. This is a convenience to allow the clients to
   hand-off parsing of messages and responses without looking into the message, and without needing
   to come up with their own way to add the type to the message.

# Feb 12D, 2018 Design Note - RobotComm protocol for clearing old message state
- Command receiver needs to keep old command responses around because it never knows when the
  comamnd responses have been received by the original sender. So there needs to be a protocol to
  clean these out. Simplest is time-based but that can cause unreasonable growing of these stale
  messages in high-throughput situations, and is not the most responsive.
- If sender gets a CMDRESP message it does not identify, it immediately responds with an CMDRESP_ACK
  messagae.
  
## OBSOLETE - all of these considerations vanish sender immediately sends a RECV_MSG_ACK message.
 Decided that sender will keep track of all command-IDs for which it has received completion
  notification (these will include ones for which it has no record off - because it has moved on).
 - Periodically the sender will send a special bulk-message with these command-IDs. The server will
   receive these and clean out it's old messages. 
 - Periodically the server will send out responses to old pending completed commands. A client that
   receive these will bank the commandIDs as previously mentioned. So eventually these old entries will
   be deleted. 
 - Dealing with a client closing a channel and/or closing a RobotComm instance.  When exiting,
  the client can send a special exit message that lists all the clients it is exiting so that the server
   can wipe out old state to do with these channels. Care must be taken to know when to ignore these
    - a new session message sent out of order
   with an older exiting-channel should cause mayhem!

# Feb 8, 2018 Design Note - ConfigurationHelper and StringmapHelper
Design goals:
- As with rest of robotutils, do not pull in an external dependency, use standard Java apis.
- If possible base the file format on an existing standard that is not too cumbersome. 
- Two-level of structure - a set of sections, a top-level for each sub-component or any other logical entity
  (like 'logging' or 'auton'), and the next level is simply key-values, one per line.
- Caller has control over where the config file is located - provides a Stream object to read
- Does have write support - but really just append to an existing stream. Mainly to support keeping the ORDER of appearance
  of keys, read should return the keys in order that they appear and write should take such a key order.
- There is the option to re-read. This would typically be done infrequently - say if a sub-component is
  being re-inited dynamically. This allows configurations to be updated 'dynamically' externally (e.g., 
  someone logs into the machine and updates the file..)
- Support reading types with defaults. Do not throw exceptions. Typically the show must go on (program
  execution continues) even if the config file is missing, unreadable or otherwise messed up. So there
- should be basic support for doing things like reading an integer value with a default supplied if there
  was some issue reading that value.
- The above support for reading should be encapsulated in a separate class so
 that it can be used in other situations to read/write non-string values into
 the string map.  
- Do not read the whole config file into memory, but ok to read a sub-section into memory.
- Potentially allow reading a specified list of sections in one chunk - into a map of maps. This is to avoid needlessly scanning the file multiple times to
pick up each section.
- Subset of YAML parser should gracefully ignore any parts of YAML it does not
 support, while being able to read the things it does understand. In particular, it should skip past hierarchies greater than two-level, and it
should ignore (strip out) ! directives that explicitly specify a type.

## Implementation Decisions
Java has a standard type called Properties. It can fill in a Properties object from a specified file stream.
There are then methods to read and write string properties. The documentation outlines the file format.
I[JMJ] decided to write a simple YAML-subset parser and supporting classes/methods for the following reasons:
- Properties does not support a two-level hierarchy.
- Properties imposes the ability to read and write.
- Properties does not support reading objects other than strings.
- YAML is minimalist yet supports a two-level hierarchy.
- Since YAML support is not built-into Java and we don't want to bring in
  3rd party library dependencies, we write our own parser for a subset of
  YAML, which is easy to do.
- Add two classes: ConfigurationHelper and StringmapHelper. These classes
  are independent of each other. Their common currency is a
  Map<String, String>, which is also what the StructuredMessageMapper deals
  with.
- Use Yaml 1.2 spec, which tightened up certain things, like boolean values
  are strictly true or false (not True, False, Yes, No, etc, etc.).
  
## Sample Configuration File
```
# Robot Configuration
---
logging:
    root_name: BB_RIO
    
auton:
    tracingOn: true
    pid1Params: kP:0.2 kI:0.002 kD:-0.2 tol:0.005
    # Above, our simple parser will simply map "pid1Params" to the
    # string "kP:0.2 kI:0.002 kD:-0.2 tol:0.005". Since this happens to be
    # a valid structured message, you can use StructuredMessageMapper.toMap
    # to get a Map, and then StringmapHelper to get individual double
    # values out of these.
    
    position: [0.5, 0.2, 0.2]
    # Above, once again, the simpler parser will parse the value into string 
    # "[0.5 0.2, 0.2]". StringmapHelper may in the future support 1D arrays of 
    # doubles and other primitives.
```

#Feb 6, 2018 Design Note A - suggestions for log file naming
To be able to collect log files from multiple machines (e.g. multiple robots and potentially multiple processors from each robot - rio, pi) the prefix for each log
should read the machine name from a file, such as "~/machinename" that can contain a name that effectively identifies the machine, such as "TCpractice=RIO" and "TCpractice-pi".
This same name CAN be also used as the root log name, though that may be overkill. When pulling logs into a database, we can keep eacah machine's log in a separate collection.

#Feb 6, 2018 Design Note B - EXPLORATION (abandoned) -changing the filter functions from a Filter interface to a Functional interface
Explored trying to use one of the predefined functional interfaces, in particular BiFunction<String, int>. Abandoned this because BiFunction<String, int> doesn't work, it has to
be BiFunction<String, Integer> - that means the overhead of wrapping the priority in an Integer object for each call to filter! Also there was no TriFunction....
Aside: Logname provides the ultimate way to discriminate as one can create a StructureddLogger.Log instance with a specialized log name and then a filter that
looks for just that name. One quirk: the logName will have the root prefix appended to it.

#Feb 4, 2018 Design Note - revision of StructuredLogger.CreateFileRawLogger methods
    It used to be that we support an 'append' flag. That has gone. The behavior is now that if a specific file is specified and it exists, new
    logs are APPENDED to this. Also, this path MUST contain the string "log" somewhere (a case-insensitive comparison is made). If a session-specific
    file is automatically generated, then it is expected that this file does not exist. If it DOES exist, it is treated as an error condition,
    the error is written to stderr, and no logging is done.
    
    A new parameter has been added: maxSize, which is the max size in bytes
    of the log file. Logging will stop if the size of this file approaches this max size (approximately). It is felt that this is the most
    straightforward way to prevent logging from taking up too many resources.
    
    The built-in File (and UDP) file Loggers now take an optional filter parameter - if non-null, this parameter is an object that implements
    the StructuredLogger.Filter interface - basically it has a method called filter that has the same semantics as Log.filter. This makes the
    built-in raw loggers much more flexible.
    
    As before, the client can always make completely custom RawLoggers.
    
#Jan 30, 2018 Design Note on Threadsafe and background Logging
[Last updated Feb2,2018]
Goals:
1. [Done]Thread safe - integrity of individual log messages is preserved. Log messages submitted in the context of any one thread is logged sequentially.
2. [Done] Max buffered limit is honored on a per-raw log basis. If any individual raw log's max buffered  message is reached, flushing of logs WILL be triggered.
3. [Done] Message deletion policy: If too many messages are being buffered - messages are being logged too fast for the logger to keep up,
   subsequent messages WILL BE discarded as long as the buffer is maxed out. For now "too many" is based on an internal constant (nominally 10,000).
   If some constant fraction (nominally 0.25) of this limit is reached, immediate processing of buffered messages is triggered to attempt to bring down the queue sizes. 
   [OLD LOGIC (NOT USED ANYMORE): If the limit is reached, a constant fraction (nominally 0.1) of the buffered messages are DELETED.]
   This is done on a per-raw-log basis, so logs that have not reached this limit are not penalized. A log message is generated periodically 
   if any messages were discarded in the previous period. This message identifies the raw log(s) and the number of deleted messages.
3. [Done]No attempt is made to generate and buffer a log message if no raw logs will accept it. IMP: before the message is generated, each raw log is queried to see if it will accept it based on (pri, cat, type).
4. [Done] Raw log's log() message is never called in the context of a client's logging method (info, trace, etc.). This is so that there is no unknown (and potentially) high in-line cost while logging, also this is to ensure thread-safe behavior.
2. [Done] StructuredLogger.flush will trigger immediate flushing of buffered messages in the background.
5. [Done] There is a max overhead of one background thread per StructuredLoggine object. IMP: THis is for a timer object.

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
    -- Additional atomic counters,  e.g., count of messages written since last flush.
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
4. On ending logging launch an 'immediate' task to write out all buffered messages, wait (block) for that task to complete, and then flush all logs.
    

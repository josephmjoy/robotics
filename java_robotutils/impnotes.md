#Implementation Notes for java_robotutils
These are informal notes and TODO lists for the project.

#TODO
1. UDP Transport: Implement caching of RemoteNodes so we don't create a new one for each incoming message! In our expected use, we have very few of these.
1. For roboRIO config file, under "logging": add "trace: [logname1, logname2, loname3]", and 
  set these up during init - basically disable tracing on all logs except the list above. This is
  a pragmatic thing to do. For example RobotComm has tracing, but it should obviously not be enabled
  by default. But adding "trace: [robotcomm]" would enable it in the config file as required.
1. StructuredLogger AND StructuredMessageMapper: YAML REQUIRES a spaces after ':' and ','. We should
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

#March 2B, 2018 General Design Note - Refactoring into more classes
- Implementations are getting unwieldy
- Helper methods are folded into core classes
- To fix these we create more classes.
RobotComm: add classes CommClientImplementation and CommServerImplementation - these are for client and server implementations of command processing.
RobotComm: add class CommUtils, placeholder for future utility methods.
StructuredLogger: add class LoggerUtils and move the various utility methods there.

#March 2A, 2018 RobotCom Design Note - Implementing CMDRESPACK

See Feb 14B, 2018 Design Note for aggregate format of CMDRESPACK, and earlier discussion (now obsolete) on strategy for pruning
old ReceivedCommand records.
Client:
- Keep a simple array of longs, equal in size to how many we want to aggregate. This array is null to start with,
and created on demand.
- Keep a lock object for access to this array.
- Keep a counter indicating how much is filled.
- For each received response, simply add to this array. Don't bother checking for duplicates or whether we
  have it in our map (local user could have cancelled it, among other things) - just tack any received cmdresps (to this channel)
  to the array.
- When the array is full, (with the lock held of course), remove it (set reference to null), and exit lock.
- If removed array, generate and send CMDRESPACK - in same context as submitCommand, not periodic work.
Server:
- on receiving a CMDRESPACK, look up map. If present, we expect the cmd to be completed. If it is NOT completed, this is an unusual situation - probably the client has been reset while the server is still pending - a very unlikely case. For now we treat it as though the cmd has been completed EXCEPT that we do not nuke the cmdType and cmdBody (as user server code will reference it). 
- Server maintains a separate map and queue of *just* cmdId's of acked commands. So when processing CMDRESPACK id's, check if it's in the ack'd map, and if not, add it to the map and queue, and *remove* it from the main cmd map. Also (if command has been completed, as most likely it is, as mentioned earlier in this paragraph) set cmdType, cmdBody, respType and respBody to the empty string, as these resources will no longer be needed. The ReceivedCommand object will also typically be in the command completed queue - it can't be removed from there now because removal from the queue is O(n). It will be cleaned up later as part of periodic cleanup.
- Also: ReceivedCommand.submit method should replace cmdType and cmdBody by the empty string - update the docs for the interface
to reflect the fact that user code attempting to access these values will get the empty string.
- The periodic pruning logic will do a first pass clearing out *all* ReceivedCommands that have gotRespAck set (these will already have been queued in the separate ack'd map.). It also keeps a running count of remaining size to avoid calling size() again.
- If after cleaning out ack'd ReceivedCommands there is still too many left, it runs through and deletes some significant fraction of them (as explained in the Feb 14B discussion) - those would typically be all old/stale records because of lost CMDRESPACKs or dead clients.
FUTURE: Add a timeout parameter to CMD - this is the client's expectation of when it expects to see the command completed. It will NOT re-send a CMD after this timeout passes. So the server can safely cleanup commands that are still lying around after their timeout has expired - in practice it can delete after min(MAX_TIMEOUT, max(MIN_TIMEOUT, FACTOR*timeout)), because a retransmitted CMD could arrived delayed by the transport.
Downside is that this places the burden on the client to specify a timeout (though -1 can represent no timeout.). Also adds clutter to the CMD header. 

#Feb 28C, 2018 RobotComm Design Note - Thoughts on real-time processing
- The command server MUST support 'instant' processing of incoming commands, not poll-based notification. This is to ensure responsiveness. Ideally, if the transport has less than 1ms delays,
and the operation can be completed in (say) 1ms, then there should be no reason to add additional delays in the process. The server user code *could* poll at sub-millisecond intervals, but that seems very wasteful.
- Likewise, the client may need to do something immediately on receiving a completion, so it too may want a completion handler called in the context of the received CMDRESP message.
- SO: Decided to add RTCMD nd RTCMDRESP as new datagraam types (instead of adding a CMDTYPE field with |RT| as suggested in the Feb 26A design note). These are have fundamentally different
  handling paths, so appropriate to make them different datagram types.
- Client has a sendRTMessage that takes a completion handler callback and a timeout. There is no option for queuing completion to be polled for later. The completion  handler will be called
one way or another - if timed out or other reason to cancel it will have an appropriate status indication. 
- Client never sends CMDRESPACKs for RT commands.
- Client periodically checks the  msgMap for timed-out commands. It could potentially keep a separate  map for RT vs non-RT sent cmds, but for starters just use the existing map.
- Server user code has to have registered a handler for receiving RT commands. So we need a new method for that (startReceivingRtCommands).
- RTCMDs never go into any map or queue. The user code is notified in the context of the transport receive packet notification - a ReceivedCommand object is created and passed up to the user code. The user code completes it like normal commands. The response is sent
in the context off the user code's call to ReceivedCommand.respond() (after a quick check that we have not already responded).
The handler is expected to not do anything time consuming. Instead it should offload time consuming tasks to a worker thread.


Unrelated:
- Client: Add userContext to sentCommand to simplify routing of completed commands.

#Feb 28B, 2018 RobotComm Milestone - sent 20 million commands over a noisy transport!
Successfully ran the command stress test "stressSubmitAndProcessCommands" with 20 million commands at the rate of 50,000 per second.
However, we are (temporarily) not tracking duplicate command executions until we finish implementing CMDRESPACK. 

```
        final int nThreads = 1;
        final int nCommands = 20000000;
        final int commandRate = 50000;
        final double dropCommandRate = 0.01;
        final double dropResponseRate = 0.01;
        final int maxComputeTime = 0;
        final double transportFailureRate = 0.1;
        final int maxTransportDelay = 100; // ms
        
_sid: 1519821865936  _sn: 1136  _ts: 408828  _pri: 2  _cat: TRACE  _co: test  _ty: _OTHER  _msg: Waiting to receive up to 20000000 commands
_sid: 1519821865936  _sn: 1137  _ts: 414039  _pri: 1  _cat: INFO   _co: test  _ty: _OTHER  _msg: Final COMMAND verification. TSForceDrops: 1932446  TSRandomDrops: 4802395   PendingCmds: 0
_sid: 1519821865936  _sn: 1138  _ts: 414108  _pri: 1  _cat: INFO   _co: test  _ty: _OTHER  _msg: ch: testChannel sc: 20000000 rc: 19800454 sC: 26641458 rC: 23227166 sCR: 23221122 rCR: 20009576 srvCMap: 1300472 srvCCQ: 1300453
_sid: 1519821865936  _sn: 1139  _ts: 414116  _pri: 0  _cat: INFO   _co: test  _ty: _LOG_SESSION_ENDED  _msg:  rootName: test
```

   
#Feb 28A, 2018 Performance Monitoring Note
Visual VM is amazing. I (JMJ) can simply run it (it's a standalone application) and it reports all kinds of information about all running VMs, including the ability to drill down into individual 
objects (including individual strings!), all reported in tables sorted by number of instances so you can immediately find out the biggest culprits. Also, it is VERY responsive. The bogged-down stress test had 10s of millions of objects, and it was happy to drill down with almost no delays. It also reports on the execution state of all threads.
https://docs.oracle.com/javase/7/docs/technotes/guides/visualvm/

Using it on RobotComm command stress test which was attempting to send 10 million commands and getting bogged down in the process, revealed, as expected that the server-side tracking of received and completed commands was the culprit - 5 million instances of ReceivedCommandImplementation (I guess were halfway through the test). The bulk of memory was taken up by 20 million instances of char[]  - 2.2GB of memory! Followed by 500MB for 20M instances of String, 500MB for 5M instances of ReceivedCommandImplementation, 200MB for 5M instances of ConcurrentHashMap.Node.
500/5 = 100 bytes per instance of ReceivedCommandImplementation and 200/5 = 40 bytes per instance of ConcurrentHashMap.Node. That's not too bad.

The 4:1 ratio of Strings to ReceivedCommandImplementation is because the latter keeps of cmdType, cmdBody, respType and respBody. We don't need to keep cmdType and cmdBody once we have notified
the server user code - so we should consider nulling these once the server user code has called ReceivedCommand.respond(). However this is tricky as the server user code can always query the command message type and body given a ReceivedCommand.  We could still nuke them. However, this is not a priority as we do not expect large sizes for cmdType and cmdBody![March 2nd update: we will replace cmdType and cmdBody by the empty string when user code calls respond(); We will replace respType and respBody by the empty string when we receive CMDRESPACK]

We also have 5M instances of RemoteNode, taking up 150MB. This seems wasteful, given that we would have relatively few remote node instances. Currently the transport passes one of these for each incoming request. So it's the transport implementation's responsibility to reuse RemoteNode objects. That's fine, something to keep in mind for the UDP implementation.

So purging old receive-command entries (see Feb 14B, 2018 Design Note on the RobotComm protocol) should fix the bogging-down of stress tests issue. Satisfying that this issue was expected to
be hit by the stress tests! Impressive that at least on my XPS-16 laptop with 32GB memory, the JVM is happy to use gigabytes of memory and manage 100s of millions of objects.


#Feb 26A, 2018 Design Note RobotComm Restransmit Strategy
- After considering various strategies for retransmit including allowing client to set timeout and #retransmits on a per command or per channel level, decided on the following.
- Two kinds of sendCommands: a regular send command that NEVER times out and a real-time send command that NEVER retransmits. The second kind, called sendRtCommand, takes an additional parameter which   is the timeout. If the response has not been received within that timeout the command is cancelled with TIMEOUT status and if necessary added to the completion queue
    and all state is is forgotten about this packet.
- The server also special-cases RT handling - it does not keep those responses around once the response has been sent because we expect that the  command will not be retransmitted. Or it could
pretty aggressively prune RT packets - to guard against duplicate packet injection by the transport.
- Client doesn't bother including RT packets in CMDRESPACK packets for the same reason.
- So the RT nature of this command needs to be in the message header somehow. Probably add a CMDTYPE field that is encoded as sequence of strings separated by hyphens (should more status bits come along = like |RT|PNG| (perhaps PNG is a Ping response handled by the server itself, with server-side client code being involved? The bars are on both side so we can easily check for the presence of a particular option without parsing everything there, like search for "|RT|".).
[FEB 28UPDATE: Decided to NOT use this for RT, instead have new message types RTCMD and RTCMDRESP.]
- SentCommand has an isFresh(int timeout) method that returns true if the time from when the command was submitted until when the isStale method was called is within specified timeout. This would place a upper bound on how stale the response is, including time sitting in the completed command queue. It would return false if the completion was not completed - canceled, rejected, etc.
- Regular send commands (which never timeout) - the client will eventually implement congestion detection with random exponential backoff. However for now, the plan is to start with a small random value (say between 100 and 200ms) and send subsequent re-transmits with random, exponential delay upto some maximum retransmit time (say 10 seconds). These constants can potentially be settable at a channel level (or sendNode level?). Note that the server-side is not affected - as it only sends a response on getting a CMD message.

Implementation sequence:
1. Implement regular command retransmit strategy - note that these commands never timeout. This is the more complicated case.
2. Get long running stress to work, with RobotComm internal resources under control, on both client and server side. Verify the never-times-out behavior, and cancel behavior.
Implement and test isFresh().
3. Add CMDTYPE into message header (see above - e.g., |RT|PNG|). For now, it should just be |RT|.
4. Implement and test client-side logic (server will still hang on to the completed responses). 
5. Implement and test combined client and server-side logic with purely RT messages. There should be no retransmits! 
6. Test combinations of RT and non-RT commands.
[Feb 27, 2018 update: After implementing random exponential backoff, sent 1 million commands over a channel with 25% loss, at the rate of 10,000 commands per second - retransmits are under control - about 2M CMDs were sent for 1M messages. There are a small fraction (about 200 out of 1 million) of missing commaands - commands that are expected to complete but were never completed. This is most likely due to the test quitting too soon, but needs to be investigated.]

#Feb 26A, 2018 RobotComm Milestone - sent 100,000 commands over a noisy transport!
Successfully ran the command stress test "stressSubmitAndProcessCommands" with 100,000 commands at a rate of
5000 commands per second over a transport with errors and delays. With greater rates (and esp with more threads) things get bogged down, and probably that is due to an exessive number of re-transmits. The client is rather simplistic about re-sending
CMD messages, so several million messages are exchange for sending 100K messages with 0.25 failure rate.

Didn't have to fix any bugs in RobotComm to get this working. However now the focus should shift to minimizing resource consumption including purging abandoned commands (both client and server).


#Feb 25B, 2018 General Adding random IDs that mark a location in source code
- Add a random 5-character alphanumeric string to log messages - unique (with high probability) one for each log call in source code. The following Processing code does the trick. Just
generate this and paste to notepad. Delete each from notepad as you use them. Code below generates 

```
#Wgyr
#JZEs
#Qnl7
Permutations: 14776336
Sqrt(permutations): 3844.0
```
The permutations is the number of possible 5-alnum sequences, and sqrt is (roughly) the number of occurrences at which there is a good chance of collisions. With 4 chars the number of places
in code is a fraction of 3844, we should have low chance of collisions. The cost of a collision is not high - just adds occasional ambiguity that can be quickly resolved by examining the code.
WARNING - There is a chance that inappropriate words are generated - so be careful when cutting-n-pasting to source code.
SUGGESTION: Add a '#' character prefix, so it's clear in the log (by convention) that this is a location specifier.
Example: log.err("NULL_POINTER", "cr #Wgyr");


```
void setup() {
  final String lcChars = "abcdefghijklmnopqrstuvwxyz";
  final String digits  = "0123456789";
  String charSet = lcChars + lcChars.toUpperCase() + digits;
  int len = 4;
  for (int i = 0; i < 20; i++) {
    println("#" + randChars(len, charSet));
  }
  
  long perm = calcPermutations(len, charSet); 
  println("Permutations: " + perm);
  println("Sqrt(permutations): " + Math.sqrt(perm));
}


String randChars(int n, String charSet) {
  String ret = "";
  for (int i = 0; i < n; i++) {
    ret += charSet.charAt((int) (Math.random()*charSet.length()));
  }
  return ret;
}

long calcPermutations(int len, String charSet) {
 return (long) Math.pow(charSet.length(), len); 
}
```

#Feb 25A, 2018 RobotComm Stress Test Design Note - Managing dropped commands and responses
- Keep two queues: droppedCommands - CommandRecords (CRs) are added to this just before a force-dropped command is sent over RobotComm; and droppedResponses - CRs are added to this queue just before the server sends a force-dropped response. These queues are periodically pruned (the same prune method can prune both). 
- Note that as commands are completed they are removed from cmdMap. 
- At the end of the (potentially long running) test, both queues are purged in a loop - this is similar logic to send/receieve Messages - except now the termination condition is that the command map goes down to 0. At this point we *know* that all commands that were created were either dropped on send, dropped on receive or completed.
- There is one wrinkle - that when RobotComm itself decides to purge internal resources - this will result in commands being completed twice or being cancelled "underneath", i.e., by RobotComm itself. With some internal knowledge of RobotComm we can know when this happens, or we can add stats like number of abandoned sentCommands and number of purged srvCompletedCommands to know how stringent to make the validation.

# Feb 23B, 2018 RobotComm Stress Test Debugging Note - Send/Receive Messages Stress Test
Spent a lot of time debugging an issue of a few unaccounted-for messages that show up when more than about 2K messages were sent. After lots of investigation and trying various 
combinations, it emerged that the so-called missing messages were forced-drop messages that should have been deleted from the msgMap. There were roughly one of these per batch.
It further emerged that the drop queue was empty, so it was pretty clear where the culprit was at that point - it is in this piece of code in prunedropedMessages:

```
                while ((mr = this.droppedMsgs.poll()) != null && dropCount > 0) {
                    this.msgMap.remove(mr.id, mr); // Should be O(log(n)), so it's ok all in all.
                    dropCount--;
                }
```
The problem is the && in the while condition - if dropCount was 0, we would sometimes remove the message record from the queue but not from the map!
The corrected code is below:

```
                while (dropCount > 0) {
                    MessageRecord mr = this.droppedMsgs.poll();
                    if (mr == null) {
                        break;
                    }
                    this.msgMap.remove(mr.id, mr); // Should be O(log(n)), so it's ok all in all.
                    dropCount--;
                }
```
Now we can send 100 million messages with force drops, a small fraction of random drops, and with transport delays - at the rate of 200K messages per second! All messages accounted for!

Here are the parameters for the above long-running test (transport failure rate of 0.00001, transport max delay of 2000ms, 100M messages at the rate of 200K per second, and with a 50% drop rate (so about 50M messages were actually received).

```
        transport.setTransportCharacteristics(0.00001, 2000);
        stresser.submitMessages(100000000, 200000, 0.5);
```

And the last few log entries (the test ran for 517 seconds, so total throughput of 100M/517 sent message per second, so pretty close to 200K per second! 

```       
_sid: 1519464050061  _sn: 1500  _ts: 514227  _pri: 2  _cat: TRACE  _co: test  _ty: _OTHER  _msg: Purging about 99913 force-dropped messages.
_sid: 1519464050061  _sn: 1501  _ts: 514243  _pri: 2  _cat: TRACE  _co: test  _ty: _OTHER  _msg: Beginning to submit 200000 messages.
_sid: 1519464050061  _sn: 1502  _ts: 514383  _pri: 1  _cat: INFO   _co: test  _ty: _OTHER  _msg: maxReceiveDelay: 3000
_sid: 1519464050061  _sn: 1503  _ts: 515386  _pri: 2  _cat: TRACE  _co: test  _ty: _OTHER  _msg: Waiting to receive up to 100000000 messages
_sid: 1519464050061  _sn: 1504  _ts: 517685  _pri: 1  _cat: INFO   _co: test  _ty: _OTHER  _msg: Final verification. ForceDrops: 49994269  RandomDrops: 482   Missing: 0
_sid: 1519464050061  _sn: 1505  _ts: 517704  _pri: 0  _cat: INFO   _co: test  _ty: _LOG_SESSION_ENDED  _msg:  rootName: test
```


# Feb 23, 2018 RobotComm Stress Test Design Note
- Before we move on to implementation of stress testing of commands, I (JMJ) would like to be able to
 have a very longrunning stress test that sends 100s of millions of messages. The current design creates
 timers for all the messages in one shot - that is going to chew up all available memory. So we need to
 do this in batches.
- New design: Take a *rate*  - messages per second - instead of the current timespan. Given a count of messages and a rate, the stress test will batch submit timers
 at that steady rate per second. Note that the transport delays will often extend beyond each one-second window.
- [IMP] fairly subtle - one complication is that dropped messages will stick around, either those that are force-dropped or randomly dropped by the transport, and these
 will accumulate. This is addressed by (a) requiring that random drops are NOT enabled at the transport level if there a very large number of messages to be sent, and
 (b) Periodically purging the older force-dropped messages sitting around. Keeping track of these for accurate accounting to the last message at the end of the test is quite tricky.

# Feb 21C, 2018 RobotComm Implementation Milestone
Sent and received a million messages using the test transport, with delays and dropped packets and with sends and receives over 10 threads. Most of the work was flushing out bugs in the test code :-), such as
not waiting long enough for the scheduled processing of received to actually process the receives before declaring failure.
Logging was essential to debugging these issues - so this is the first real use of StructuredLogger!
The following code that initializes the logger is handy as it allows for easily changing the filters based on which components to log.

```
File logfile = new File("C:\\Users\\jmj\\Documents\\robotics\\temp\\log.txt");
        StructuredLogger.Filter f1 = (ln, p, cat) -> {
            return ln.equals("test.TRANS") || ln.equals("test.HFLOG") ? false : true;
        };
        StructuredLogger.Filter f2 = (ln, p, cat) -> {
            return ln.equals("test.TRANS")  ? false : true;
        };
        StructuredLogger.Filter f = f1;
        
        StructuredLogger.RawLogger rl = StructuredLogger.createFileRawLogger(logfile, 1000000, f);
        StructuredLogger.RawLogger rl2 = StructuredLogger.createConsoleRawLogger(f);
        StructuredLogger.RawLogger[] rls = { rl, rl2 };
        StructuredLogger sl = new StructuredLogger(rls, "test");
```

# Feb 21B, 2018 General Design Note
One issue with tasks submitted using the ExecutorService is that those tasks are responsible for catching and handling all exceptions, else they are silently discarded. Therefore, one SHOULD always a catch-all for Exception in all submitted tasks that may possibly throw a runtime exception. I (JMJ) ran into this when debugging a problem with the RobotComm stress tests where exceptions were silently swallowed while I was assuming that they were not being thrown.

# Feb 21A, 2018 StructureLogger Design Note - Tags: adding space after colon; adding dateTime to _LOG_SESSION_STARTED
- Decided to add a single space character after the colon character for tags in the log. The result is not as "tight", but it
has the big advantage that parsing can now ignore ':' that occur within message text without space, such as in URLs and time stamps
for example "2018-02-21T10:50:02.401". Also this is what YAML specifies (and where the original inspiration for adding a space came from).
- Also added dateTime tag to the start session message. [IMP] This is printed by LocalDateTime.now().toString()
New log format:

```
_sid: 1519239002401  _sn: 1  _ts: 0  _co: ROOT#LOG  _pri: 0  _cat: INFO  _ty: _LOG_SESSION_STARTED  _msg: dateTime: 2018-02-21T10:50:02.401  rootName: ROOT LOG  maxBuffered: 10000  autoFlushPeriod: 100
```


#Feb 17, 2018 RobotComm Design Note - Stress Testing Specification
*Test Transport*:
- supports loopback only for now.
- constructor arguments int maxDelay(ms) and double failureRate (0.0 to 1.0). A value close
 to 0.0 is taken as zero-failures - so specifying 0 or 0.0 guarantees absolutely no failures.
- if failureRate > 0 it uniformly-randomly drop that fraction of packets.
- if is not dropping a packet and maxDelay is > 0, it will *always* use a timer to send 
   the packet, specifying a delay of between [0, maxDelay]. So the average delay will be
   maxDelay/2.
- It will also ALWAYS drop a packet that contains the special pattern "TRANSPORT-MUST-DROP".
 For simplicity, packets dropped this way are not part of the failure rate calculation.
- Transport maintains an accurate count of packets dropped (due to both drop-packet
indication in the packet or because of random failures.

*Stress Tester*
- Submits bulk sendMessage and bulk sendCommand. For each, it takes n messages/commands and
a time interval, and generates timer tasks randomly across [0, time interval]. The timer task
simply runs an execution task in a fixed-sized threadpool (ExecutorService). The latter task
sends the message/command. This two-step process is so that the timing is more or less accurate
while at the same time multiple threads are used.
- It also sprays some number of poll-recvd-message and poll-recvd-command timer tasks, which
  also do their work in the same executor threadpool. These tasks keep processing incoming 
  messages/commands until the queue becomes empty, then they exit. Note that each task
  will empty the corresponding queue, so the number of these tasks do not have to match
  the number of received messages/commands.
- There needs to be a final set of received after 2x or more the sum of the delay before
submission and the transport delay. Commands neeed to take into account re-transmits of the 
protocol extending the time further.

*Verfication of Send/Recv messages"
- Total messages sent == totalMessagesReceived + transport drop count.
- No drop commands should be received.
- Message body and type should be intact.

*Verification of Send/Recv command"
- Total commands processed and responses received == total commands sent plus force-dropped
messages. Transport failures should have no effect.
- No dropped commands should be received/processed -- well there is drop CMD and drop CMDRESP,
which have different effects. These chould be accounted for exactly.
- Correct cmd type and cmd body should be received by server. Correct response type and
response should be received by client.
- Just keep waiting until the expected number of commands are completed or received- should 
just wait indefinately for this to happen as an accurate count of dropped packets and dropped
responses are known.

IMP: First send/receive of messages stress test working. Then revise above spec and 
get cmd/response stress test to work.

#Feb 15D, 2018 RoboComm Design Note - Statistics Reporting
 RobotComm can support reporting of performance statistics. For each channel:
  - size of queues and maps - current values for now, not averages.
  - number of sends, receives, command send sequests, sent CMDs,  command receives, RESP
  - The above are rough estimates as we don't keep atomic counters for the above - they are just volatile variables.
 Not appropriate to be a method in SentCommand etc as they are implementation dependent.
 The status are reported in RobotComm.ChannelStatistics:

```
     public static class ChannelStatistics {
        public final String channelName;
        public final long sentMessages;
        public final long rcvdMessages;
        public final long sentCommands;
        public final long rcvdCommands;
        public final long sentCMDs;
        public final long rcvdCMDs;
        public final long sentCMDRESPs;
        public final long rcvdCMDRESPs;
        public final long sentCMDRESPACKs;
        public final long rcvdCMDRESPACKs;
        public final int curCliSentCmdMapSize;
        public final int curCliSentCmdCompletionQueueSize;
        public final int curSvrRecvdCmdMapSize;
        public final int curSvrRcvdCmdIncomingQueueSize;
        public final int curSvrRcvdCmdCompletedQueueSize;
        ...
}
```

#Feb 15C, 2018 RobotComm Design Note - Execution Context for Timers
RobotComm needs timers just for the send-commend side - to periodically re-send CMD messages for which
RESP with completed status have not been received. It seems heavy handed to create a Timer object (which has
the overhead of one thread) to do this. Some ideas:
- Add methods to DatagramTransport for timers (and also millis and nanos). Isn't clean to add this 
  to a datagram transport interface.
- Add a new System interface passed in to RobotComm constructor that contains timers, millis and nanos.
  Seems cumbersome. But potentially eventually the way to go - RobotUtils.SystemHelper interface, and a
  RobotUtils.Utils class that collects together a bunch of helper methods including some currently
  in StructuredLogger and RobotComm - raw loggers, udp-transport - it would be more clean to have these
  live outside the core RobotComm and StructuredLogger class.
- So while all this thinking is breweing, RobotComm will simply expose a periodicWork method that the
  client is expected to call periodically - no strong requirements as to how often. Retransmits will happen
  in this context.

# Feb 15B, 2018 RobotComm - Command ID (CmdId) uniqueness considerations.
- I (JMJ) realized that on the server side, the reach check for uniqueness should combine client CmdID with client address. I am not doing this currently - the 
  key is just client CmdID. 
- The client CmdId is CURRENTLY generated by starting with the currentTimeMillis when RobotCom instance was created and doing an atomic increment each time any command on any
   channel is submitted. Problem is different clients will have similar values for currentTimeMillis so will generally generate similar sequence numbers.
- I considered the CmdId initialized with millis<<32 + nanos % (0xffffffff) - this is pretty unique as we are using bits from both millis and nanos.
- However in the end, decided on just Random.nexLong to setup the start cmdId for each channel, and then increment it on each new command.

# Feb 15A, 2018 RobotComm - mapping remote (server) CMDRESP status to local (client) status
- Need to only update if there is progress, as messages from the server can be received out of order.
- It is very messy to do this conditional updpate on a case-by-case basis, so the idea is to compute an ordering or priority.
- Create an ordering of status:
   if (local status is SUBMITTED) order is 0 - this is the very start of a sent command.
   if (local status is !pending) order is 100 - once we decide this command is complete, we NEVER update it's status any further.
   local status REMOTE_QUEUED - 1, REMOTE_COMPUTING -2
   remote status completed: order is 50 - various things like REJECTED, CANCELED, COMPLETED
   remote status is QUEUED - 1, COMPUTING - 2
   check if remote status order > current order, if so update status.
   
   
   if (
# Feb 14A, 2018 RobotComm - removed Channel.periodicSend
Rationale:
 1. It is the only method that calls back to the client with in an arbitrary (timer) context.
 2. It will incur the overhead of a timer task. Well not really because we need a timer task anyways to retransmit CMD messages.
 3. It is something the client can easily do - just create a periodic timer task and call Channel.sendMessage() from that task.
 The old PeriodicSender class:

```
     /*
     * Sends a dynamically generated message periodically. The period was set when
     * the underlying object was created. Pause and resume may be called in any
     * order and from multiple threads, though results of doing so will be
     * unpredictable. The messages themselves will be sent as whole units.
     *
     */
    interface PeriodicSender extends Closeable {
        void pause();
        void resume();
        void close(); // will cancel all further attempts to start/stop.
    }
    
    PeriodicSender periodicSend(int period, String msgType, Supplier<String> messageSource);
```

# Feb 14B, 2018 Design Note RobotComm Protocol - Laying out Command-Response sequence
Consider making CMDRESPACK an aggregate - bulk responding to acks. It does seem very wasteful to generate one CMDRESPACK for every transaction.
In the absence of errors (the common case) 33.3% of the packets are CMDRESPACK! So...
1. Make CMDRESPACK have a 0 value for cmdId, and in the message body it has a list of cmdIds, separated by newlines (no other whitespace).
Sample message (new line chars as new lines):

```
3wIC,CMDRESPACK,mychannel,0,IDLIST,,
309039AB09CFA90
2099939AB09CFA9
234059AB09CFA90
23234309039AB09
```

Note IDLIST is an internal message type. Client can never generate CMDRESPACK messages.

Some significant observations/guidlines on protocol implementnation.
*Client*:
1. Prepares SentCommand, sends CMD, keeps SentCommand in pendingSentCommands mam. If [NEW] addToCompletionQueue is TRUE, it will
   note this fact internally.
2. On rec eiving RESP, if still in map it updates it's status, and if not still pending, it removes itself from from map, and [new] if addToCompletionQueue, 
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
[March 3rd UPDATE: See Mar 2A, 2018 Design Note - we keep a separate map and queue of ack'd commands - just the cmdIds are kept; so the strategy has been enhanced.]

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

- It used to be that we support an 'append' flag. That has gone. The behavior is now that if a specific file is specified and it exists, new
    logs are APPENDED to this. Also, this path MUST contain the string "log" somewhere (a case-insensitive comparison is made). If a session-specific
    file is automatically generated, then it is expected that this file does not exist. If it DOES exist, it is treated as an error condition,
    the error is written to stderr, and no logging is done.
    
-    A new parameter has been added: maxSize, which is the max size in bytes
    of the log file. Logging will stop if the size of this file approaches this max size (approximately). It is felt that this is the most
    straightforward way to prevent logging from taking up too many resources.
    
-    The built-in File (and UDP) file Loggers now take an optional filter parameter - if non-null, this parameter is an object that implements
    the StructuredLogger.Filter interface - basically it has a method called filter that has the same semantics as Log.filter. This makes the
    built-in raw loggers much more flexible.
    
-    As before, the client can always make completely custom RawLoggers.
    
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
    

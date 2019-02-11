# Design and Development Notes for Python port of `Robotutils`



## February 11, 2018A JMJ: Moved Robotcomm tests to the tests directory
`test_robotcomm.py` and `test_comm_helper.py` were sitting side-by-side with the code they were testing. They have been
moved to the `tests` directory to join the other unit tests. The imports have to be changed. In doing so, I realized that
the `context` import in test is imported by other tests during `unittest` test discovery, and this is confusing, because
I could remove the `import context` and still have the tests import `robotutils` even though the latter is not
in the python path. For now, using `unittest discover` as below works well from any directory, and should
be set as an alias...
```
alias rutest='py -m unittest discover -s ~/Documents/GitHub/robotics/python_robotutils -k'
```
Without specifying the `discover` option, as below, the test fails because it cannot find `robotutils`, because
the `context.py` module was not loaded by that test. In `test_comm_helper.py`, importing `.context` _before_
referencing `robotutil` works, but is frowned on by Pylint. Placing it _after_ makes Pylint happy, but
then then `unittest` (without `discover` option) fails.
```
py -m unittest python_robotutils/tests/test_comm_helper.py
```
Bottom line: until I get to the bottom of this, just run with the `-discover` option, run `rutest`, which works from
any directory (assuming of course that the location of the `python_robotutils` directory in the alias is
correctly specified).

## February 10, 2018D JMJ: Adding timing to the echo client
Things to measure:
- total time
- total messages sent
- total messages received
- messages per second
- failure rate
- response time for an individual packet. This requires matching up a message with its response - but we could
  do this matching just every Nth message if sending at a high rate or a very large number of messages. I think it's
  better to do that response time with commands and rt commands instead of trying to correlate outgoing messages with
  responses from the echo server.

Implemented this in `rcping`. Implemented new method `EchoClient.get_stats` that returns the `robotcomm` statistics.
The output shows the results of sending 1 million messages. Interestingly, the rate running the client and server
on the same machine is about half the rate of running them on different machines. I suppose this is expected.
It's about 2K per second on my Elitebook and 4K per second across machines.
Sample output below.
```
$  rcping -msg -n 1000000 -rate 4000 -q 192.168.1.16
Message statistics for 192.168.1.16
    Sent = 1000000, Rate = 3991.94, Received = 998196, Lost = 1804 (0.18% loss)
Received = 998196
```
Loss rate (over Wifi) is hard to characterize. It seems to depend on how many messages are being sent in total.
At 4K messages per second:
- 1M:   ~0.2%
- 100K: 0 to 0.03% usually
- 10K: often 0 loss

## February 10, 2018C JMJ: Milestone - successfully ran echo client and server on different machines
Print output was held until the command terminated when python was invoked directly from the bash shell. It's 
an artifact of the console - so have to run `winpty python`, not `python`.
 
Sent 1 million messages from `JMJ-ELITEBOOK` to `JMJ-XPS15` which was running the echo server and the latter dutifully
sent them back. There was some loss - hard to characterize, but it's between 1 in 1K and 1 in 50K messages. It is dependent
on rate. It's about 1 in 1K, sending 1M messages at a rate of 5K per second. At a slower rate the loss diminishes.

### `rcping` - implemented -q option, sent/received 100K messages
Implemented the `-q` quite option in `rcping`, and successfully sent and received 100K messages using `rcping` and `rcecho` -
still on the same machine, but running in separate console windows.

There's some shared code with `rcping.py` that should be moved to a common place.
Pylint points out this common code - impressive.

Some quirks:
- `rcecho` is very silent - needs to print something when it is starting and when it is shutdown with CRTL-C
- Reduce duplicate code between the two utilities (mentioned earlier)
- `rcping` - needs to stop reporting output after a certain number have received so we don't clutter the console window. Especially
  needed for long-running stress testing done at a high rate. Or maybe a -quiet option - that may be better actually, rather
  than suppressing of output based on some number of sends/receives.

### Changed `rcping` option `-payload` to '-body`
```
  -body PAYLOAD         send PAYLOAD, which has the form bodytype[::body] or
                        ::body
```
### Adding send notification handler to `EchoClient`
`rcping` is rather quite sending messages as it does not report the sending of individual messages.
So adding an handler to the echo client which will be called just before sending each message.
Now the output is:
```
$ rcping -msg localhost
Sending: 'hello::sn: 0 ts: 82360'
Sending: 'hello::sn: 1 ts: 1083484'
Sending: 'hello::sn: 2 ts: 2083293'
Sending: 'hello::sn: 3 ts: 3083060'
Received = 0
```
Note that '::' is used to separate message type from body.

### Adding logging level to `rcping` command line
Spec: `-loglevel TRACE|DEBUG|INFO|ERROR|CRITICAL`
Default is ERROR
Logging to a file is not yet supported.

Implementation note: the standard logging levels are parsed using the following code:
```
choices = "DEBUG INFO WARNING ERROR CRITICAL".split()
index = choices.index(strloglevel)
level = getattr(logging, choices[index])
```

### Moving `rcping` outside the `robotutils` package
It should be inside `robotutils` anyways, because it is a _client_ of `robotutils.`
For now, until we figure out how to install `robotutils` as a package available to any
python script, the only place it can be is one level above the `robotutils` package directory,
because the directory of the script is always added to the python module search path.

With this change - `rcping` now works - sending messages. The logging output is verbose - need to make it
settable via a command line argument.

### Ping statistics to emulate:
```
$ ping localhost

Pinging JMJ-EliteBook [::1] with 32 bytes of data:
Reply from ::1: time<1ms
Reply from ::1: time<1ms
Reply from ::1: time<1ms
Reply from ::1: time<1ms

Ping statistics for ::1:
    Packets: Sent = 4, Received = 4, Lost = 0 (0% loss),
Approximate round trip times in milli-seconds:
    Minimum = 0ms, Maximum = 0ms, Average = 0ms
```

## February 10, 2018B JMJ: PrettyPrinter objects are very powerful, along with vars
`pprint.PrettyPrinter` objects, including the default object `pprint.pprint` are much more powerful
than I first thought. Some of the things you can do:
- get the formatted output as a string using `pprint.pformat`
- check if the object is readable (can be used to reconstruct the object entirely) or is recursive (has 
  cycles, which it detects)
- can print compactly (small attributes packed into a single single)
- can vary width of line and indentation amount

Pretty printer objects will not directly print an arbitrary object. To do so, pass the 
object through `vars`:
```
mc = MyClass()
pprint.pprint(vars(mc)) # takes additional parameters, including a stream to write to

# Or, if you want to use pformat...
s = pprint.pformat(vars(mc)) # s is the pretty-printed string.
print(s)
```
According to "The Python 3 Standard Library by Example" book, if an object has a `__repr__` method, 
pretty printing will display that object. See Section 2.10.3.

## February 10, 2018A JMJ: Started work on rcping utility

It is based on design note `February 6, 2018B JMJ`.  It lives, for now, in
`robotutils/scripts`. It uses Python's powerful `argparse` module for parsing command line
arguments. See method `generate_argparser` in `scripts/rcping.py`. Some features of the
parser I use:
- mutually exclusive groups, for example `-msg -cmd -rtcmd`
- customizing destination attributes and help using `dest`, `metavar`, `default` and `help` when calling
  `add_argument`
- using `parser.error` to exit after parsing on extra validation I do - for extracting host name, port, etc.
- type conversion from string to integers and floats.

I considered various options for specifying message type and body, here are some:
```
    -payload 'pos/x: 1 y: 2'
    -payload 'pos::x: 1 y: 2'
    -payload 'pos|x: 1 y: 2'
    -payload 'pos,x: 1 y: 2'
    -payload 'pos[x: 1 y: 2]'
```
Picked the second one (`msgtype::msgbody` and variants `msgype` `::msgbody`) as it is distinctive and easy to type and to
parse. It picks up the first '::' it finds, ignoring any others to the right - so '::' can be used in the message body,
but not in the message type.

Help text auto-generated by `argparse`:
```
$ py rcping.py -h
usage: rcping.py [-h] [-n count | -t] [-l size | -payload PAYLOAD]
                 (-msg | -cmd | -rtcmd) [-c CHANNEL]
                 address

Robotcomm ping utility

positional arguments:
  address           hostname[:port] of destination

optional arguments:
  -h, --help        show this help message and exit
  -n count          count of things to send
  -t                send indifinitely
  -l size           size of auto payload
  -payload PAYLOAD  send PAYLOAD, which has the form mytype[::mybody] or
                    ::mybody
  -msg              send messages
  -cmd              send commands
  -rtcmd            send rt-commands
  -c CHANNEL        name of channel
```
Currently, `rcping` just parses and prints the parse object. Note that it 
has extracted host name and port, and payload message type and body
```
$ py rcping.py -msg -payload 'pos::x: 42 y: 100' rpi0:41901 -rate 10 -t
{'address': 'rpi0:41901',
 'channel': None,
 'cmd': False,
 'count': None,
 'hostname': 'rpi0',
 'msg': True,
 'msgbody': 'x: 42 y: 100',
 'msgtype': 'pos',
 'payload': 'pos::x: 42 y: 100',
 'port': 41901,
 'rate': 10.0,
 'rtcmd': False,
 'size': None}
```
The above output was generated by using `vars` and `pprint` - for pretty-printing the attributes
of an object:
```
def ppobject(obj):
    """Pretty print a single object"""
    pprint.pprint(vars(obj))
```

## February 8, 2018C JMJ: Milestone - got echo client and server to work over local UDP transport
100,000 messages were sent at the rate of about 5000/sec (which is abut the max on my EliteBook). All of them
were received (as expected, as this is the loop-back transport).

Sending 0 messages also works.

The errors were due to uncaught exceptions thrown that `unittest` would silently handle and not report, while there are background
listen threads waiting to be shut down - in particular the transport listen threads. By catching exceptions and 
closing the transport the hangs went away.

To debug I noticed that `EchoClient.sendMessage` was not returning normally - because the trace before it was displayed but
not after - so I put a breakpoint in the function and stepped through until it hit the error.
The key uncaught exception was an attribute naming error - see the `pdbg` listing below.
```
AttributeError: 'ReceivedMessage' object has no attribute 'msgbody'
> c:\users\jmj\documents\github\robotics\python_robotutils\robotutils\comm_helper.py(400)send_messages(
)
-> recvmsg.msgtype, recvmsg.msgbody)
(Pdb) l
395                     recvmsg = channel.poll_received_message()
396                     _TRACE("ECHO_POLL_RECEIVED returns")
397                     breakpoint()
398                     if recvmsg:
399                         _TRACE("ECHO_RECV_MSG msgtype: %s  msgbody: %s",
400  ->                            recvmsg.msgtype, recvmsg.msgbody)
401                         if response_handler:
402                             response_handler(recvmsg.msgtype, recvmsg.message)
403
404                 _LOGGER.info("Done sending %d messages", num_sends)
405             except KeyboardInterrupt:
(Pdb) pp recvmsg
ReceivedMessage(msgtype='hello', message='sn: 0 ts: 233134', remote_node=('127.0.0.1', 41890), received
_timestamp=1549631684822, channel=<robotutils.comm.channel.Channel object at 0x000001F07CB63F28>)
(Pdb)
```
The `EchoClient.send_message` code was also just polling for one received message at a time, instead of processing
all that have arrived. This code is now factored into the method `_get_received_messages` and uses the handy-dandy
`_utils.getsome`  - see "January 29, 2018G" note.

## February 8, 2018B JMJ: More progress in debugging and fixing echo client and server
Note: discovered `math.isclose`: `math.isclose(self.rate, 0.0, abs_tol=1e-3)`.

When the echo client server test attempts to send 4 packets and have them reflected back, the first two of them
make it to the server and back, but the response is not indicated back to the client - the udp transport receives them
on the client side, but the messages do not show up when the client polls for responses.

Also the tests hang. So to debug these issues, I created simple client-only and server-only tests:
`test_echo_only_client_simple` and `test_echo_only_server_simple`. These seem to run fine (with some small
fixes in the echo client and server when sending 0 messages.

When the client AND server is run, sending 0 messages, the tests hang:
```
INFO:robotutils.commutils:Starting server hostname: localhost port: 41890
INFO:robotutils.commutils:Closing Echo Client
INFO:robotutils.comm:START_LISTENING instance: rc_server
INFO:robotutils.commutils:ECHO CLIENT CHANNEL_STATS ChannelStatistics(channelName='echo', sentMessages=
0, rcvdMessages=0, clientStats=None, clientRtStats=None, serverStats=None)
INFO:robotutils.comm:STOP_LISTENING instance: rc_client)
TRACE:robotutils.comm.channel:REMOVING_CHANNEL name: echo
TRACE:robotutils.commutils:Starting background listen thread <Thread(DGRAM-LISTEN, initial daemon)>
TRACE:robotutils.commutils:START_LISTEN Binding socket to address ('localhost', 41890)
TRACE:robotutils.commutils:RECV_WAIT Waiting to receive UDP packet...
INFO:robotutils.commutils:Echo client echoclient Starting to send 0 messages
INFO:robotutils.comm:START_LISTENING instance: rc_client
TRACE:robotutils.commutils:Deferring starting to listen until first send
```
The logging is confusing because the two instances of the transport, the echo client and the echo server
all use the same log `robotutils.commutils`. It's probably time to move to class-specific loggers from module-wide
loggers and also inject the friendly name of the instances per "February 6, 2018D" note.

Need to look into why the test is hanging sending 0 messages...

## February 8, 2018A JMJ: Need to bind or send from a socket before you can receive from it
On windows, when attempting to receive a datagram using `recvfrom`, one of two conditions must hold:
- The socket has previously been bound to a local address and port
- The socket was previously used to send a packet.

I did not realize this. There is a nice explanation here:
<https://stackoverflow.com/questions/35805664/socket-recvfrom1024-throws-socket-error-invalid-argument-supplied>

The echo client was not calling bind, but also started a background thread which attempted to receive, and that was
throwing an exception:
`OSError: [WinError 10022] An invalid argument was supplied`

There are various ways to fix this:
- Require `UdpTransport` users to send at least packet before calling `start_listening`.
- Have the background thread wait for the first send to be called - in a loop with a short sleep.
- Have the background thread wait to be signalled when the first send is submitted.
- Defer starting the background thread until the first send (if ever).

I picked the last option as it seems the cleanest and wastes the fewest resources in the case the send is
never called or called much later than the client is initialized.

Implemented these changes to `comm_helper.UdpTransport`.


## February 7, 2018A JMJ: A tight loop in executor task causes a hard hang...
The following unit test reproduces the problem. The background task is simply a tight
loop that never exits. The main thread fails an assertion. The program hangs - have to kill
the bash shell. No errors are reported.  If, on the other hand, I sleep for a bit in the
loop (uncomment the commented-out line below), it works fine - assertion failure is reported and 
the program exits.
```
    def test_sample_hanging_executor_submission(self):
        """Shows getting stuck because of a never-exiting background submission"""
        with concurrent.futures.ThreadPoolExecutor(1) as executor:
            def runserver():
                while True:
                    pass
                    #time.sleep(0.1)
            executor.submit(runserver)
        assert False
```
I hit this debugging an issue with the echo client / server unit tests. The program hanged so hard
that the main line code log and print statements did not run. To debug, I
stepped through code with pdb and realized that an assertion was failing.

## February 6, 2018D JMJ: Added optional parameter `name` to  RobotComm constructor
This is for logging purposes - so in the logs we can make out which instance of Robotcomm is
generating what logs. It is a keyword-only parameter with a reasonable default:
`def __init__(self, transport, *, name="robotcomm"):`
This is a pattern that can be adopted more generally - each object of
significance has an attribute called `name` that is injected into log messages.

## February 6, 2018D JMJ: Implemented EchoClient  - just for messaging
Implemented the Udp echo client, `comm_helper.EchoClient`. Currently just handles sending and receiving messages.
It's design is different from the Java version. Key differences:
- Various send parameters are specified by a different function, called `set_parameters`.
- Method `send_messages` just takes a single parameter, a handler that is called each time any message is received.
 The Java version would log any received messages, but did not otherwise provide a way for the client to access any
 received messages.
- The rate of sends is dynamically controlled to match the requested rate - see the code in `send_messages` - it will
  sleep a certain amount if it finds it is sending at a rate faster than what was requested. The Java version took a 
  period as input and would sleep for that period between any sends.
- The default format of a send message is as per the specification in the "February 6, 2018B" design note. It includes
  a sequence number and time stamp and optional padding characters. However, the message can also be supplied by
  the client.

## February 6, 2018C JMJ: Thoughts on default 'system' channels
Have Robotcomm publish some reserved channels, such as 'ping' - any service can be pinged for health, say.
A standard set of commands and responses can be suggested, such as uptime and epoch (updated each time service
is restarted), CPU utilization, #processes, #threads, %memory used, etc. Maybe this special channel is called
`_sysinfo` and is a hidden channel - is responded to by Robotcomm itself as a convenience. Also can query
Robotcomm's own internal stats.


## February 6, 2018B JMJ: Robotcomm cmdline 'ping' utility design
`rcping` is the name of a proposed utility to encompass the capability of `EchoClient` and `EchoServer`.
General guidelines:
1. Client and server roles are decoupled - they don't depend on each other. In particular, the client can be used
to send user-supplied messages or commands to a destination, which will be useful for testing. Similarly, the server
can supply canned responses to a client.
1. Can use a config file for supplying more detailed information - not a hardcoded file location - a user-supplied
file and section for extra configuration information.

Client-side features:
1. Send a standard message to a standard channel a default (4) times and wait for the echoed response before
   sending the next one. This is a straightforward emulation of `ping`.
1. As above, but standard command.
1. As above, but standard rt command command.
1. Support `-n` and `-t` options from ping for each.
1. Support '-l' (length) size option from ping for each. It specifies the size of the _body_
1. Support '-body',  for message body type and body (default to nanosecond timestamp)
1. Support '-c', for channel (default to 'echo')
1. Set a standard port number for Robotcomm servers so that we don't have to keep typing (and mistyping) port numbers,
   and can specifically open that port in firewalls.  Default echo server port: 41890 (see  "February 6, 2018A" note)

```
rcping -msg localhost
rcping localhost # same as above
rcping -cmd localhost
rcping -rtcmd localhost

# Below, for '-body', the type and body (if present) are separated by ','
# [-body ''] specfies empty body type and body, which is perfectly valid
rcping -c 1 -msg -body 'loc, x: 32 y: 45' pi0:41891
rcping -c 1 -cmd -body 'calibrate' pi0:41891
rcping -c 1 -rtcmd -body 'getpos, x' pi0:41891

rcping [-t] [-n count] [-l size] [-msg] [-cmd] [-rtcmd] [-body _type[,body_]] target_name[:target_port]

Default values:
-Option -n: 4, unless -t is specified
-Option -l: About 30 - to hold a sequence number and time stamp (see below)
-Option -body 'hello,sn: _sequence-number_  ts: _microsecond-timestap_ -pad: [_extra-pad-chars_]
-Option -body default example: 'hello,sn: 2  ts: 552953421366 -pad: []'
-If none of -msg, -cmd and -rtcmd is specified, -msg is assumed
-Server port number: 41890

sn: 1 ts: 55124112412412 -pad:[----]

Illegal values and combinations:
- Option -t and -n cannot be specified together
- Option -l and -body cannot be specified together
- Option -l (size) value less than a minimum amount is ignored to make room for the default body which includes the sequence
	number and timestamp.
- At most one of -msg, -cmd and -rtcmd must be specified (if none specified -msg is assumed)
- Server port MUST be in the 41xxx range. Other options are 36xxx, 42xxx, 45xxx and 48xx per the note below, but
  we'll keep things simple and restrict it to 41xxx so its easy to remember.

```

For future - potential specification of body types and content in a configuration file:
```
config:
    msg_1: type, message content
    cmd_1: type, command content
    resp_1: type, response to cmd1
    rtcmd_1: type, rt command content
    rtresp_1: type, response to rtcmd1
```


## February 6, 2018A JMJ: Multicast IP addresses and server Port Number Assignment Guidelines

Found the discussion on possible port numbers to use on OneNote - 'January 22' in `JMJ Personal->FRC->FRC Notes`

### Multicast

From <https://tools.ietf.org/html/rfc2365>:
```
	6.2. The IPv4 Organization Local Scope -- 239.192.0.0/14
	239.192.0.0/14 is defined to be the IPv4 Organization Local Scope, and
	is the space from which an organization should allocate sub- ranges
	when defining scopes for private use. 
```

### Port numbers to use for in-house applications
From <https://support.mcommstv.com/hc/en-us/articles/202306226-Choosing-multicast-addresses-and-ports>
Do NOT use:
- Ports 0-1023 are the Well Known Ports and are assigned by IANA. These
  should only be used for the assigned protocols on public networks.
  Ports 1024-65535 used to be called Registered Port Numbers (see
  rfc1700) but are now split into two areas (see rfc6335).
- Ports 1024-49151 are the User Ports and are the ones to use for your own protocols.
- Ports 49152-65535 are the Dynamic ports and should not be prescribed to a protocol.

From <https://stackoverflow.com/questions/218839/assigning-tcp-ip-ports-for-in-house-application-use> 
<https://en.wikipedia.org/wiki/List_of_TCP_and_UDP_port_numbers> - very comprehensive!

I also picked up these notes from OneNote, however I can't find any online confirmation of this,
so don't know how this came about, and I'm ignoring this guidance:
- Nix 40000-42999 - "Rainbow Six" - plus 44000 and 45000
- 32768-65535 "Tiger Woods"
- 42292 Unreal Tournament

So we will live in the 36xxx range. Or we can be in 41xxx - because of 41899! 

A very useful utility: <https://www.adminsub.net/tcp-udp-port-finder> It returns what is assigned if anything
to a particular port.

My conclusion as of January 21st, 2019:
- 36xxx and 41xxx-42xxx, 45xxx, 48xxx range is unassigned currently
- We'll pick 4189x for our use because we can remember 41899
- Default echo server port: 41890


## February 5, 2018D JMJ: First cut of port of EchoServer
Lives in `robotutils/comm_helper`. No tests for it, and anyways, haven't ported `EchoClient` yet.
The Java version blocked; the Python version is non-blocking - the caller must repeatedly call `periodic_work`.
There are pros and cons to both approaches, but I feel that this non-blocking version is more 
straightforward because it doesn't require the client to create a new thread and interrupt it to end. Instead
the client can run a loop (with potentially multiple echo clients and servers) and simply quit when it wants to as
none of the methods are blocking.

## February 5, 2018C JMJ: Start of unit test for the UDP transport
It's `test_comm_helper.py`. 
It creates a client and a server. The client sends a bunch of messages and the server verifies
it receives messages. Not much validation (yet) - just that there are no send errors.

The basic test is working. There was a logging issue I hadn't realized. `logging.basicConfig` is global,
so various unit test modules were calling it, and `unittest discover` was loading all these modules to
discover tests - in the process one of them would win and set the log level, while subsequent test
modules would be ignored. The fix (for now) is to comment out the call to `logging.basicConfig` by
default and only enable it for debugging a particular test module.

## February 5, 2018B JMJ: Importing a lower-level module
`from .comm.common import DatagramTransport` Allows `comm_helper.py` to import `DatagramTransport`
from `./comm/common.py`. At some point the various public things under `comm` should be exposed directly
as its own attributes instead of referencing sub modules like `common`, `channel` and `robotcomm`.

## February 5, 2018A JMJ: Thoughts on porting the UDP Transport
Note that there is a `socketserver.UDPServer` class. We can't and don't want to use it because Robotutil's
`DatagramTransport` is simple message passing, so this is just FYI.

It's not a port, it's a re-implementation because the socket APIs are different.
- The returned 'node' is simply a tuple: `(host-address, port)` - that is directly
taken by `socket.sendto` and that is a parameter to the handler called by `socket.recvfrom`.
No cache of nodes is maintained. The tuple reported by `socket.recvfrom` is directly passed as the `node`
parameter to the datagram receive message handler.
- `UdpTransport.new_remote_node` takes an optional parameter `lookup_dns` that defaults to `False`. If
  `True` it will lookup DNS. So we punt whether or not to lookup DNS to the client, which is a good thing.
- All exceptions when sending or in the receive handlers' background thread are caught. A count of send
exceptions is kept and an error is logged for the first exception in send. Exceptions in send and 
in the receive thread always generate trace messages.
- Over-the-wire format is UTF-8 encoding of the string message.
- As with the Java version, the socket is created on demand - when listening is enabled or when the first
 send is attempted. Also, if listening is stopped, the socket is closed. A subsequent send will generate a new
 socket.

At this point, tests are not written, but `comm_helper` compiles with no Pylint errors (not counting
the ones explicitly disabled, in particular the catching of all exceptions when sending and in the
listen thread.

## February 4, 2018B JMJ: Design Note - Eliminating `DatagramTransport.RemoteNode`
Rationale: `RemoteNode` has two methods: `send` and `address`. `node.send(...)` can be
replaced by `transport.send(node, ...)`. `node.address()` can be replaced by `str(node)`.

Both the `MockTransport` and the `UdpTransport` have very little state for the so-called remote node.
For `MockTransport` it is the string address. For `UdpTransport` it is the tuple `(address, port)`. So
these can be directly returned as a node object. 

Successfully removed `RemoteNode` and got existing unit tests to pass. It was an easy exercise, underscoring
the fact that it was unnecessary.

Added this note to `DatagramTransport` docstring:

```
    NOTE TO IMPLEMENTORS: The destination/remote node object is opaque
    to the clinents, however there are two requirements for this object:
    1. It must support str to obtain a text representation of the address
    2. It must be suitable for use as a key or set element, hence immutable.
       Strings and tuples work well for this.
```



## February 4, 2018A JMJ: Added stress tests for CountDownLatch

The stress tests are in class `test_concurrent_helper.TestCountDownLatch`. They verify timeout accuracy
and test concurrent invocations of count down and wait. The tests pass.

Note:
`ThreadPoolExecutor` does not kill running background tasks even if the main thread is exiting. Nice explanation 
here: https://stackoverflow.com/questions/49992329/the-workers-in-threadpoolexecutor-is-not-really-daemon

## February 3, 2018C JMJ: Implemented concurrent_helper.CountDownLatch
This is the Python version of Java's `CountDownLatch`. There are some suggestions online, such as
http://www.madhur.co.in/blog/2015/11/02/countdownlatch-python.html
The one I implemented also supports timeout. Code is `robotutils.concurrent_helper.CountDownLatch`. It has
a basic doctest. Unit tests are in `test_concurrent_helper.TestCountDownLatch`.
At this point basic tests pass and Pylint is happy.

## February 3, 2018B JMJ: Converted camel casing to snake casing in test_robotcomm.py
Visual Studio Code was a big help in renaming - works great for local variables and method calls. It may
not catch all cases of the latter - if the type of the object can't be discerned. But overall it is a
huge help. It is essential, however, to have good tests in place because Pylint will not catch 
cases of calling methods if it can't find the implementation of the corresponding classes. Also it doesn't seem
to verify named tuples.

All tests pass. I haven't yet disabled `invalid-name` as there are other naming issues still to be resolved
(chiefly, 1 2 letter names).


## February 3, 2018A JMJ: Investigating why Robotcomm send/receive are failing under high stress - RESOLVED
The problem was first mentioned in the 'February 2, 2018A' note.
The problem has been resolved (discussion below) and we can now batch send 300K messages with random drops
and delays.

Sending 100K with delay of 1 second fails, even if we wait for 260 seconds. 8 messages are reported missing.
This is with `ConcurrentInvoker` temporarily disabled - so not using the executor thread pool (to help isolate the problem).
At the rate of 5K messages/sec, we expect to 100K messages to take 20 seconds. But it fails
after waiting for 260 seconds. So those 8 messages are unaccounted for.

What worked: the mock transport could send a million messages with delays and force-drops (no random drops), with
every message accounted for - it does this at about 10K messages per second.

The open question is why transport failures are failing if there is delay (even just 0.1sec) - sending 40K messages,
when I can send 300K with NO delay - when also the transport tests are fine sending a million messages with delays.

Works:
300K, no delay: takes about 30 seconds. So 10K/sec
20K, 0.01 delay - about 2.5 seconds
20K, 0.1 delay - about 3 seconds
20K, 1.0 delay  about 4.5 seconds
20K, 10.0 delay  about 13.5
50K, 0 delay - about 4.8 seconds

Doesn't work:
50K, 0.1 delay - bails after 35 seconds with 105 missing
50K, 0.01 delay - bails after 35 seconds with 295 missing

Occasionally:
30K, 0.01 delay - about 3 seconds OCCASIONALLY, else bails
30K, 0.1 delay - about 3 seconds OCCASIONALLY, else bails
30K, 1.0 delay - about 6 seconds, else bails after 33 second s with 23, 51 missing

Summary:
30K is when issues start to happen with any non-zero delay. 20K is fine with any delay (up to 10 sec tested).
Only a small number of messages (typically under 100) go missing.

Resolution:
`submitMessageBatch` schedules a bunch of deferred calls to poll the receive message queue. It takes 
special care to schedule a 'final' call (the `if i == 0` case below). It was scheduling this
call just 1 second after the submission time span. I changed this to 10 seconds and now things
work - up to 300K messages, sent with up to 1 second transmission and with random and force drops.
```
extra_delay = 10 # TODO: need to compute this more carefully
            if i == 0:
                delay = maxReceiveDelay + extra_delay
            self.scheduler.schedule(delay, receive_all)
```
So it's a test bug. The proper fix for this is to properly schedule the final poll message - or keep
retrying when waiting to exit test in `submitMessage` - which currently waits up to a fixed amount
of time for missing messages to be accounted for. This timeout scheme needs to be refined to work
even on slow processors, and with any number of messages. That is TBD.

## February 2, 2018B JMJ: Robotcomm send/receive message stress tests pass for more complex cases
Can successfully send 100,000 messages, with 10 threads and random delays and drops. The max rate of
submission on my Elitebook seems to be about 5000 per second - this is less than 1/10th the rate of the 
Java version - which can handle about 100,000 messages per second. Nevertheless, this is a
great state of affairs as it is testing all the most complex library usage including concurrent
executors, timers and concurrent data structures.

There is an issue (perhaps a testk issue) that if the rate of message submission is set to too high AND there
are too many messages (>  	, that
the test quits early and complains that some messages did not go through. This does not reproduce if the rate
is kept below 5000/second.  For reference, on my Elitebook, sending 20K messages at a rate of 10K/sec works,
but it fails if the rate is bumped up to 20K/sec. Sending 50K at 10K/sec also fails.

This failure persists even if `TestHarness.submitMessages` waits for much longer before calling
`finalSendMessageValidation` - so this issue needs to be investigated.

## February 2, 2018A JMJ: Milestone. Robotcomm send/receive message stress tests pass for basic cases
Successfully sent and received 10,000 messages over the mock transport with 'trivial' settings
of a 1-thread worker pool and no delays! The fixes were just in how different fields of a received 
message were interpreted by the tests - no fixes in the actual Robotcomm implementation.

Logging is paying dividends in debugging, especially logging of call stack on exceptions in the
background threads, by `ConcurrentInvoker`. Also helpful to be able to set log levels.

## February 1, 2018B JMJ: Set background thread's daemon attribute to True
Tests used to block - requiring me to kill the entire bash session - if the main thread hit an assertion failure,
including test failure. `CTRL-C` had no effect. This is because `concurrent_helper.EventScheduler` keeps
a background thread alive and the process would hang waiting for that thread to exit, which it would never
do because the scheduler's `close` method was not called.

The fix is simply to set the `daemon` property of the background thread to `True` before starting it.
Works great - now my comm unit tests no longer hang on exceptions.

Reference: https://docs.python.org/3/library/threading.html#threading.Thread.daemon

## February 1, 2018A JMJ: Implemented LevelSpecificLogger
This replaces earlier notes on logging:
	January 31, 2018B
	January 25, 2018B
	January 22, 2018A

This is a more streamlined and also customizable approach compared to what was suggested in the above
design notes. Note use of `__call__` so that the specialized logger object can have methods but also be
invoked directly. Note also use of polymorphism when specifying the underlying logger object.
```
	trace = LevelSpecificLogger(TRACELEVEL, "comm") # by name
	trace.pause()
	assert not trace.enabled()
	trace.resume()
	if trace.enabled(): trace("This is a trace message. i=%d", 42)
	logger  = logging.getLogger("comm")
	dbglog = LevelSpecificLogger(logging.DEBUG, logger) # by logger
	dbglog("This is a debug message")
```
It is up to individual modules to decide whether to define global, per-class, or per-instance
loggers (or some combination of all three). Most modules should define module-level level-specific
loggers.

This code is checked in as `robotutils/logging_helper.py`

Following is typical module-level initialization:
```
import logging
...
from .. import logging_helper

_LOGNAME = "robotutils.comm.channel"
_LOGGER = logging.getLogger(_LOGNAME)
_TRACE = logging_helper.LevelSpecificLogger(logging_helper.TRACELEVEL, _LOGGER)
```
Existing code under `robotutils/comm` has been updated to this new scheme.

## January 31, 2018B JMJ: Simplifying the `_trace` method
It used to be that `_trace` took an extra (and first) argument recording message type,
which was inserted with a `_ty: ` prefix. There were several places in the code where this
first argument was forgotten, only caught at runtime. Also it was somewhat tedious to add
this extra parameter. I considered inserting a `_ty: ` prefix any, assuming the
message starts with a word that could be interpreted as the message type. Unfortunately
that doesn't require a boundary between that first word and subsequent text. To keep things
simple, the new `_trace` simply prefixes `_msg: `. There is no type value. A future
trace method (`trace2`?) could add a type. Let's actually use trace output in
programs that analyze logs before imposing this extra burden. The plan for now is to
simply follow the convention that the first word in trace messages is a kind of type. Perhaps
post-processing of logs can promote that to a type. For example: `"DROP_SEND dropping send because
transport closing"`. Perhaps we can standardize on these tags - for now they are not
specified formally - perhaps comments can list the current list of pseudo-message-types.


## January 31, 2018A JMJ: New class concurrent_helper.ConcurrentInvoke
I implemented class `ConcurrentInvoke` to make sure that concurrently executed
tasks properly log exceptions and there is a way to stop future executions of
concurrent tasks. This came up writing comm tests, where I wanted to execute
certain functions in a different thread context, so was using a
`ThreadPoolExecutor`, but the problem is that exceptions thrown in the context
of the executor are hidden unless the future (returned by `executor.submit`) is
checked for exceptions. In the case where we simply want to run the task in a
different thread's context, we don't track these futures so never know if an
exception was thrown.

`ConcurrentInvoke.invoke` or `ConcurrentInvoke.tagged_invoke` are equivalent to
`Executor.submit`, however they will save away a bounded number
(`ConcurrentInvoke.MAX_EXCEPTIONS`) of exceptions for later analysis, AND they
will suppress invocations if a past exception has occurred. Additionally, if a
logger is supplied in the constructor, the details of the exception are logged.

`ConcurrentInvoke` has been implemented, along with unit test class
`TestConcurrentInvoker`. Tests pass and Pylint score remains 10.0

## January 29, 2018G JMJ: Wrote helper method `getsome`
Implemented a generator that calls a supplied function multiple times, up to a maximum number of times.
This replaces a common case where one uses a `try-except` and or while loop with sentinel check.
Instead of:
```
	try:
	    for _ in range(maxnum):
	        value = somefunc()
                process(value)
	except IndexError:
	    pass
```

Or ...
```
	for _ in range(maxnum):
	    value = somefunc()
	    if value == sentinel:
	        break
            process(value)
```

We now have
```
	for value in getsome(somefunc, maxnum, sentinel):
	    process(value)
```

Pretty cool! Note that `somefunc` must take no arguments in this implementation.

The code is below, currently residing in `test_robotcomm.py`.
[UPDATE: Code moved to `robotcom/_utils.py` because it's now being used by `comm_helper.EchoServer`]

```
def getsome(func, maxnum, sentinel=None):
    """A generator that returns up to {maxum} things. It
    will return less if {func}():
     - raises an IndexError exception
     - raises a StopIteration exception
     - returns the sentinel value.
    >>> for x in getsome((x for x in range(10)).__next__, 5):
    ...     print(x, end=' ')
    0 1 2 3 4
    >>>
    """
    try:
        for _ in range(maxnum):
            value = func()
            if value == sentinel:
                return
            yield value
    except (IndexError, StopIteration):
        pass
```

## January 29, 2018F JMJ: Performance cost of nested functions

Great discussion on whether or not nested functions are "compiled":
	https://stackoverflow.com/questions/6020532/are-python-inner-functions-compiled
Also good examples of calling `dis.dis(function)`, using `timeit`, and reporting the `id` of objects.
Short answer: they are _parsed_ when the module is parsed, creating a code object. However a function
object is created each time the outer function is called. This function object includes any referenced
outer objects and globals. A profile of a very simple outer and inner function showed that inner functions
incur a 50% penalty if the bodies are essentially empty. This is very little but could be an issue for
doing this in very performance critical situations. 

Here's an example of calling `dis.dis`:
```
>>> def outer():
	def inner(i): return i+42
	return inner(32)

>>> dis.dis(outer)
  2           0 LOAD_CONST               1 (<code object inner at 0x00000214F03AD0C0, file "<pyshell#61>", line 2>)
              2 LOAD_CONST               2 ('outer.<locals>.inner')
              4 MAKE_FUNCTION            0
              6 STORE_FAST               0 (inner)

  3           8 LOAD_FAST                0 (inner)
             10 LOAD_CONST               3 (32)
             12 CALL_FUNCTION            1
             14 RETURN_VALUE

Disassembly of <code object inner at 0x00000214F03AD0C0, file "<pyshell#61>", line 2>:
  2           0 LOAD_FAST                0 (i)
              2 LOAD_CONST               1 (42)
              4 BINARY_ADD
              6 RETURN_VALUE
>>> 
```


## January 29, 2018E JMJ: Do not define nested functions within loops!

Got caught by defining a deferred function in a loop in `TestTransport.submitMessageBatch`. 
Examine the following:
```
	>>> funcs = [lambda: i*i for i in range(10)]
	>>> for f in funcs: print(f(), end=' ')
	81 81 81 81 81 81 81 81 81 81
```
I had forgotten that it is a _reference_, not copy of any variables that are included in the closure. So by the
time the functions are actually invoked, this shared variable (i) has it's final value (9). This is
_different_ than Java's closure, which always makes a copy (and insists that the variables are final). And I seem to
remember that JavaScript behaves similarly to Python.

To fix, do not define functions in loops. Instead delegate it to a function outside the loop:
```
	>>> def makefunc(i): return lambda: i*i
	>>> funcs = [makefunc(i) for i in range(10)]
	>>> for f in funcs: print(f(), end=' ')
	0 1 4 9 16 25 36 49 64 81
```
Each invocation of `makefunc` creates a fresh instance of local variable i (not to be confused with
the list comprehension index i)

[UPDATE] Here's another option, keeping the function inside the loop, but using default initialization of
input parameters (`i=i`), so that the function can be called without any parameters. 
It could have been `x=i ... return x*x` too.
```
def func1():
    allfuncs = []
    for i in range(10):
        def func2(i=i): # was func2() which has the bug
            return i*i
        allfuncs.append(func2)
    return allfuncs

funcs = func1()
for f in funcs:
    print(f())
```
Without the `i=i`, the code prints 10 81s. Another option is to use `functools.partial`.

## January 29, 2018D JMJ: Python equivalent of Java's System.currentTimeMillis

`System.currentTimeMillis()` (in milliseconds, long) -> `time.time()` (in seconds, float) - both UT

## January 29, 2018C JMJ: Cleaned up most camel case - > moved to snake case
It was fairly straightforward. The unittests caught some inter-module mistakes - it seems Pylint does
not catch badly named attributes if the class in question is external - I suppose that makes sense.
The following module are almost clean: `_protocol.py, channe.py and robotcomm.py`. They are clean as reported
by Pylint. However Pylint does not report camel casing in named tuples (for example,
`ServerStatistics`).

## January 29, 2018B JMJ: Milestone - simple Robotutils send and receive test works!
This is `TestRobotComm.test_message_basic` that sends a single message over the test transport
and verifies the message is received. Far from the light at the end of the tunnel, more like 
light from a small skylight. But still, a solid milestone!

FYI: To return milliseconds from start of the Unix Epoch as an integer: `int(1000 * time.time())`. This 
is used for the time stamp in `ReceivedMessage`, and the Python equivalent to Java's `System.currentTimeMillis()`

## January 29, 2018A JMJ: Removing `DatagramTransport.new_remotenode`
I decided to remove the `new_remotenode` method from the `DatagramTransport` abstract base class.
This is because `RobotComm` itself does not use it - it would only be the clients of `RobotComm` that
may (potentially) use it. It will likely turn out that specific implementations of `DatagramTransport` will
have more appropriate ways to construct remote nodes, perhaps methods that take more than one argument such
as IP address and port number, or have more than one way to construct remote nodes.

Here is the removed method:
```
    @abc.abstractmethod
    def new_remotenode(self, address): # -> RemoteNode:  but pylint doesn't like it
        """
        Creates a new remote node from a string representation of the address
        WARNING: Depending on the transport and the string representation, this
        method can block as it attempts to perform network operations.
        NOTE: Robotcomm never calls this method. This is for the client's benefit.
        Consider deprecating.
        """
```

## January 28, 2018D JMJ: Had to disable Pylint protected-access check at several places
The `Channel` class and `Server` and `Client` classes are all really part of the same implementation and
sometimes need to reach into each others' protected attributes and members. These attributes and members
(such as `Channel._server`, and `Channel._handle_received_messages`) do need to be protected as the class 
(`Channel` in this case) is public, exposed to clients of Robotutils. For now, I explicitly 
disable the Pylint warnings at each instance, such as:
```
    server = chan._server # pylint: disable=protected-access
    client = chan._client # pylint: disable=protected-access
    ...
    elif dgram.dgType == DgEnum.MSG:
         # pylint: disable=protected-access
        chan._handle_received_message(dgram, rn)
```
There are relatively few of these explicit disables and I'm happy with living with them. Certainly don't 
want to globally disable this check!

## January 28, 2018C JMJ: Finished 1st-cut port of robotutils.Channel
This is `comm/channel.py`. It is a complete port. However, all command and rt-command handling
is delegated to the 'client' and 'server' objects. Message handling is handled in `Channel`, and 
that is the first comm feature we are going to test end to end on multiple platforms.

At this point, Pylint is happy with `channel.py` except for bad variable naming from the Java port,
which is disabled until unit tests run. (per "January 15, 2018A" porting guidelines).

## January 28, 2018B JMJ: Policy on read-only public attributes
There are vigorous discussions on stack overflow re how to expose read-only (or rather, init-only)
attributes. The simplest and most often suggested method is to simply expose them, not try to hide
or limit them in any way. Other options are to use decorators, such as `@property`, which, frankly, I think
is overkill.

So for Robotutils, I have come up with the following guidelines. is to simply
expose public attributes. They should ONLY be read-only (not settable).  They
should be initialized in their own section at the beginning  `__init__`, with
comments identifying them as such.  Also, they should be called out as a list
of read-only attributes in the class docstring.

## January 28, 2018A JMJ: New method ConcurrentDict.remove_instance
`ConcurrentDict.remove_instance` emulates Java's `ConcurrentHashMap.remove`
Implemented in `concurrent_helper.py`. Unit tests updated in `test_concurrent_helper.py`. Tests pass.

## January 25, 2018E JMJ: Moved shared definitions to ./comm/common.py
These definitions were sitting in `robotcomm.py`, but are needed in `channel.py`. But
`channel.py` must be imported by `robotcomm.py`. To avoid the circular import
(see "January 22, 2018D" note), I'm moving these definitions to `common.py`

## January 25, 2018D JMJ: Checking in untested `robotcomm.py` and skeleton `channel.py`
`robotcomm.py` is a port of the Java `RobotComm` class. It has also been refactored - with
the datagram parsing and generation code stuff that lives in embedded class `MessageHeader`
moving to the previously checked in `_protocol.py`.

This code cannot yet be tested because it simply delegates work to channels and channels are not
yet implemented - the checked in `channel.py` is just a skeleton.

## January 25, 2018C JMJ: Removed `__len__` from ConcurrentDict
[UPDATE: Jan 30, 2018: `__len__` was added back - was needed for `RobotComm` tests]
It was triggered by a Pylint warning in `comm/robotcomm.py` not test
for a collection to be empty by `len(collection) == 0`. You are supposed to just use
`not collection`. But I can't do that for `ConcurrentDict`, and anyway, its 
questionable to ask what the length is at any instant. So I removed `__len__` and added
`empty`. The doc tests and unit tests have been changed to reflect this and tests pass.


## January 25, 2018B JMJ: Checking in logging related files under `comm/`
File `_commlogging.py` defines `_trace` and `_tracing` and a default logger, more or less
following the design outlined in the "January 22, 2018A" design note.
File `_comlogmsgtypes.py` is for collecting together common log message types.
These files have not been tested other than basic proof of concept.

## January 25, 2018A JMJ: Implemented `comm/_protocol.py` completely

Java's `MessageHeader` has become `Datagram`, that includes the message body.
This is cleaner. Earlier (and Java) code extracted the message body separately.
That may be been motivated by reducing effort spent handling bad messages.
However the check for the protocol signature is still there, and the bulk of
the work involves parsing the header, which was anyways happening.

In `_protocol.header_from_str`, I just check `KeyError` at the bottom, and report it as the cause exception.
```
try:
	...
except KeyError as exp:
	raise ValueError("Malformed header - invalid cmd ID") from exp
```

I use predefined `frozenset` objects to check messages for validity. The following code is from
`_protocol.py`. There may be better/shorter ways of doing this, but this is what I came up with.
I did consider making two sets and intersecting them - but that does more work because we just need
to know whether or not there is overlap, not compute the overlap. Also, not clear if there is
any actual benefit (or perhaps penalty) to using frozen sets vs strings when the
strings are short. For long strings, using frozen set would be O(n) vs O(n^2) using strings.
```
BAD_HEADER_CHARS = frozenset(string.whitespace)
BAD_CHANNEL_CHARS = frozenset(',' + string.whitespace)

def containschars(str_, charset) -> bool:
    """Returns if {str} contains any chars in {chars}"""
    for char in str_:
        if char in charset:
            return True
    return False
```

Used `Enum` and `IntEnum` for various fields.
```
class DatagramType(Enum):
    ...
class CommandStatus(Enum):
    ...
class Position(IntEnum):
    ...
```
The `name` attribute is used to extract the string version, and these also form the over-the-wire
versions.

There is a nice docstring at the head of `_protocol.py` that exercises the functionality.

## January 24, 2018B JMJ: Some Vim Tips

### Reflowing text and 80-column visual check
Vim - help with PEP 8 80-column guidance
Set column 80 to have a red color (by default)
`:set colorcolumn=80` or `:set cc=80`
Then use the `gq<motion>` command, for example `3gq<ret>` to re-flow 3 lines of
text. Or `vjjjjgq`

Works pretty well, including respecting indentation, and
automatically adding language specific comment lines, and smart enough to
re-flow multi-line comments in Java/C/C++: `/* a very long line */` re-flows
really well!  Vim is certainly aware of the file type, because, for example, it
treats `//` differently
when editing Python vs. Java source code.

`gq` uses `textwidth` if set, else 79 (or window width, if it is smaller). The 79 default works well
with PEP 8!

### Uppercase/lowercase reminder
- `{visual}U`
- `gU{motion}`
- `gUU`
(replace 'U' by 'u' for lowercase), and '~' to toggle case.


## January 24, 2018A JMJ: Porting parsing and serializaton of Messages

Moving MessageHeader Java class to `_protocol.py` named tuple.

'Normal' Enums can't be compared with their underlying bare values - they can only be compared with
each other! They can be enumerated, and support the `in` containment check.
```
	>>> class Status(enum.Enum): A=0; B=1; C=2

	>>> Status.A
	<Status.A: 0>
	>>> Status.A == 0
	False
	>>> [x for x in Status]
	[<Status.A: 0>, <Status.B: 1>, <Status.C: 2>]
	>>> Status.A in Status
	True
	>>> 0 in Status

	Warning (from warnings module):
	  File "__main__", line 1
	DeprecationWarning: using non-Enums in containment checks will raise TypeError in Python 3.8
	False
```
This is all by design. There is a `IntEnum` that is a subclass of int, so can be used wherever
an `int` can:
```
	>>> class Status(enum.IntEnum): A=0; B=1; C=2

	>>> [x for x in Status]
	[<Status.A: 0>, <Status.B: 1>, <Status.C: 2>]
	>>> Status.A == 0
	True
	>>> Status.A < 2
	True
	>>> Status.B + 2
	3
	>>> 2 in Status # Note this - Stats.B is in Status, but Status.B.value is NOT in Status
	False
	>>> [x.value for x in Status]
	[0, 1, 2]
	>>> len(Status)
	3
```
`RobotComm._protocol` will use a regular `Enum` for various keywords in the on-the-wire protocol,
and an `IntEnum` for indexing into the string representation.

## January 23, 2018A JMJ: Added ConcurrentDict.upsert
Added `upsert` because it is a common case - there was code in 
Robotutils that attempted to do this. It's better included in
`ConcurrentDict` because it controls the lock. Decided to add a creation function so that
the chance of creating and then discarding a value is minimized.
Updated `TestConcurrentDict` to also include `upsert`. All tests pass.
```
    def upsert(self, key, valuefunc, *args, **kwargs):
        """ Atomically, if there is no value or None associated with {key}, then
        call {valuefunc}(*args, **args) to generate and set a new value 'newvalue' and
        return tuple (newvalue, True).
        If, on the other hand, there was a non None previous value `prevvalue`, return
        (`prevvalue`, False). Thus, the return value is a tuple. The first element is the
        new or previous value, and the second element is True if the value was created and
        False if the value was preexisting. NOTE: {valuefunc} is called WITHOUT the dictionary lock
        being held. There is a small chance that {valuefunc} may be called but the resultant
        object discarded. There is also the chance that the previous or newly set value
        may be deleted concurrently by another thread before upsert returns.
        """
```

## January 22, 2018D JMJ: Regarding Circular Imports
Lots of good discussions in circular imports - for example:
https://stackoverflow.com/questions/744373/circular-or-cyclic-imports-in-python

Pylint complains of circular references:
```
test_robotcomm.py:1:0: R0401: Cyclic import (robotutils.comm.channel -> robotutils.comm.robotcomm) (cyclic-import)
```
I think that circular imports should be avoided - they are confusing. So the question is
how to avoid them in the `RobotComm` <-> `Channel` relationship. Let's see as we proceed
with the port.

## January 22, 2018C JMJ: Moved to flatter structure, moved some tests to tests directory
Per Design Note "January 22, 2018B", various modules have been moved up to to the top level.
Also tests for stable code have been moved to the `tests` directory that is a sibling of 
`robotutils` which is the recommended location. All tests under `tests` pass and have a 10/10 Pylint
score (of course they have several warnings disables within their code, in particular disable
the invalid name check because of lots of single-char and double-char variables.

## January 22, 2018B JMJ: Design Note: Flattening the Directory Structure
In keeping with "Flat is better than nested", I'm removing  the `misc` and `conc` sub directories.
The only subdirectory that remains is `comm`. The new rule: only make a sub directory (sub package)
if the logical content spans more than one module (file). So only `comm` qualifies.
See "January 4, 2019A" note on the original code structure proposal for additional context.

For now the unit tests still live side-by-side with the modules they test, but they should move
to a `test` directory in parallel with `robotutil` once the code is stabilized.
When that happens, we'll need to add (just for the tests), `robotutil` to the python path. Follow
the instructions in https://docs.python-guide.org/writing/structure/ (Hitchhiker's Guide to Python),
in particular, create a `tests/context.py` file:
```
import os
import sys
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

import sample
```
And import this from individual test modules.


## January 22, 2018A JMJ: Design Note: Logging Design
After much (too much?) thought, here is the plan. Each package and significant sub-package
will have a file called `_mylogging.py`.  This module adds `TRACE` as a new level, and also defines 
a logging instance, `_logger`, and module-level functions `_tracing` and `_trace`.

Here's a sample `_mylogging.py`:

```
import logging

TRACELEVEL = 9 # Default level of trace messages
LOGNAME = "robotutils"

_logger = logging.getLogger(LOGNAME) # pylint: disable=invalid-name
logging.addLevelName(TRACELEVEL, "TRACE")

def _tracing() -> bool:
    """Whether or not tracing is enabled. Use it to conditinally execute code
    just for tracing."""
    return _logger.isEnabledFor(TRACELEVEL)

def _trace(*args, **kwargs) -> None:
    """Trace message. Tracing analog to logger.debug, etc"""
    _logger.log(TRACELEVEL, *args, **kwargs)
```
Modules can import one or more of the module attributes as follow
Sample `somemodule.py` below imports various `_myloggin` attributes by name and uses them. Function
`testfunc` is called twice, once with the default log level (only warnings and errors displayed)
and once with the log level set to `TRACELEVEL`.


```
import logging
from _mylogging2 import _logger, _tracing, _trace
import _mylogging

logging.basicConfig() # setups up logging of to console by default

def testfunc():
    """Main Test function"""
    print("---in testfunc---")

    if _tracing():
        print("main: going to trace...")
        result = sum(x for x in range(20000000)) # Complex prep work
        _trace("This is a trace message from main. sum: %d", result)

    _trace("This an UNGUARDED trace message")
    _logger.error("This is an error message")


testfunc()

_logger.setLevel(_mylogging2.TRACELEVEL)

testfunc()
```
This produces output:

```
---in testfunc---
ERROR:robotutils:This is an error message
---in testfunc---
main: going to trace...
TRACE:robotutils:This is a trace message from main. sum: 199999990000000
TRACE:robotutils:This an UNGUARDED trace message
ERROR:robotutils:This is an error message
```
Note that, by default, each line in the output starts with the text version of the logging level,
and in the about output, TRACE has been added to the list of levels.
This was done in `_mylogging.py`, with `logging.addLevelName(TRACELEVEL, "TRACE")`.
Without this call, the level gets reported as "Level 9".

### A discarded variation - automatically populate module-level attributes
The following version of `_mylogging.py` exposes method `setuplogging` that takes a module name.
It looks up that module and populates attributes `_logger`, `_tracing` and `_trace`, so that
that module can use them without having to import them explicitly. This option does work, but
was discarded because it violates Pythonic principles "explicit is better than implicit" and
"simple is better than complex". Also, Pylint throws a fit because it can't find these inserted
attributes.

```
import sys
import logging

TRACELEVEL = 9 # Default level of trace messages
logging.addLevelName(TRACELEVEL, "TRACE")

def setuplogging(modulename, logname=None) -> None:
    """Sets up logging for module with name {name}. Optional {logname}
    provides a logical name for this logger. If None it is set to {name}."""

    try:
        module = sys.modules[modulename] # wil throw Key error if not found
    except KeyError:
        raise ValueError("Invalid module name [{}]".format(modulename))

    logname = logname or modulename
    logger = logging.getLogger(logname)

    def tracing() -> bool:
        """Whether or not trace is enabled"""
        return logger.getEffectiveLevel() <= TRACELEVEL

    def trace(*args, **kwargs) -> None:
        if tracing():
            logger.log(TRACELEVEL, *args, **kwargs)

    #pylint: disable=protected-access
    module._tracing = tracing
    module._trace = trace
    module._logger = logger
```

## January 21, 2018A JMJ: Some logging resources

Official docs, tutorial and cookbook:
	https://docs.python.org/3/library/logging.html
	https://docs.python.org/3/howto/logging.html#logging-basic-tutorial
	https://docs.python.org/3/howto/logging.html#logging-advanced-tutorial
	https://docs.python.org/3/howto/logging-cookbook.html#logging-cookbook

Tips on logging: https://fangpenlin.com/posts/2012/08/26/good-logging-practice-in-python/
From Hitchhiker's guide:
https://12factor.net/logs
https://12factor.net/

Logging level aka severty level: higher is more important. So opposite of 'priority' in general.
`exc_info, stack_info`. Latter can be specified even if there is no exception - any time you
want the full stack trace
extra

Can define your own levels. (don't know how).
```
CRITICAL 50
ERROR 40
WARNING 30
INFO 20
DEBUG 10
NOTSET 0
```

`logging.captureWarnings(capture: bool)` - route warnings to logging system

### Design Decisions

Looking at video 
https://www.youtube.com/watch?v=HTLu2DFOdTg&t=33m8s

-Use clasmethods if you want to properly deal with subclassing
-Use slots for lightweight objects. Use slots only if you really need them -
 you can add them later. If someone subclasses, the slots doesn't inheret - so
 subclassers can add attributes.
-Use properties as required if there is a need to hide previously exposed
 attributes.
-Use __ (dunder) prefix to enable subclasses to NOT override your attributes.


For a sub package containing multiple modules, the main module, called
`mainmod` here, would have:
```
import logging
logger = logging.getLogger('mainmod') # pylint: disable=invalid-name
def tracing():
    return true
def trace():
    return logger.level >= _TRACELEVEL
```

Other sibling modules would include:
```
from .mainmod import logger, tracing, trace
```

Code can call...
```
    logger.info("blah bla')
    if tracing():
        trace("this is a trace message")
```


## January 20, 2018B JMJ: Started porting RobotComm
Started porting `RobotComm` class and tests...
- `ReceivedMessage` is a named tuple. 
- `Channel` Java interface is the top-level `Channel` ABC

To disable a particular unit test: `@unittest.skip("Unimplemented")`

More porting strategies (see also January 15, 2018A note)
-  Only use interfaces if the client will need to provide an implementation - like for
   `DatagramTransport`. The Java version had many interfaces, like `RobotComm.Client` and
   `RobotComm.ReceivedCommand` that were implemented by RobotComm itself. These all go away,
   replaced by the implementation classes.
- Flatten class structure. In Python, we have the module-level (file-level) namespace
  that wasn't there in Java, where each file had to be a class. So we can promote
  classes & interfaces living inside the RobotComm class to the top (file) level.
- Java `enum` become Python `Enum` instances.

```
Java                		Python
--------------------------------------
RobotComm.SentCommand &         SentCommand class
SentCommandImplementation

RobotComm.ReceivedMessage &     ReceivedMessage (named tuple)
ReceiveMesssageImplementaion

RobotComm.ReceivedCommand &     ReceivedCommand class
ReceivedCommandImplementation

RobotComm.Channel  &            Channel class
ChannelImplementation

```

## January 20, 2018A JMJ: Documenting named tuples and Pylint smarts

Got this from a Stack overflow answer: Since Python 3.5, docstrings for `namedtuple` objects
can be updated.  From https://docs.python.org/3/whatsnew/3.5.html#collections

```
	Point = namedtuple('Point', ['x', 'y'])
	Point.__doc__ += ': Cartesian coodinate'
	Point.x.__doc__ = 'abscissa'
	Point.y.__doc__ = 'ordinate'
```
Using this for `ReceivedMessage` which is now a named tuple (as an interface in Java)

Pylint is _very_ smart. 

```
def myfunc():
	return 'blah' # return a string

x = myfunc()
x.operation()
```
Pylint warns that `str` has no method `operation`! In other words, it discovers
that `myfunc` returns a `str` object and then checks what's being done to that 
object!


## January 18, 2018C JMJ: Handling exceptions in EventScheduler's background thread
`EventScheduler`'s background thread now exits if the client's event handler function throws
an exception, after first logging the stack trace by calling `logger,exception`:

```
EventScheduler.start.threadfn:
	...
	except Exception: # pylint: disable=broad-except
	    self._event_exception = True
	    # This would log an ERROR message with details about the exception.
	    # By default this prints to stderr, so we get to reports of this error
	    logger.exception("EventScheduler: Client's threadfn threw exception")
	    break  # Get out of the loop
```
Previously it was catching the exception and ignoring it (after tracing it, which didn't show up
because tracing is off by default. `logger.exception` logs as an error information about
the last caught exception available via `sys.exc_info()`.
I also experimented with using `warnings.warn()`, but settled on logging the exception. Errors
are sent to `stderr` by default so they show up on the command line.

The status of `EventScheduler's` background thread can be checked by querying
`EventScheduler.healthy` which checks flag `_event_exception` set by the background thread
if the latter exits because of an exception.

There is an OPEN ISSUE - if the main thread exits, the background thread is left blocked
(probably on `sched.wait`) and the process does not exit, even with CTRL-C. So if any
test assertion fails before the mock transport is closed, the bash window has to be killed - the
only I could find to kill the process :-(.  Need to look at ways for the background thread to
realize that it has been abandoned and exit on its own. But that's for another day.

## January 18, 2018B JMJ: MockTransport test: Replaced threading.Timer by our own EventScheduler
And got an expected 10x speedup! Now processing about 50,000 messages per second.
Previously it was about 5000 messages per second.

## January 18, 2018A JMJ: Fixed corner cases with EventScheduler
Function `EventScheduler.start.threadfn` had to be fixed  because it was exiting without
processing events if `EventScheduler.stop` was called immediately after scheduling an event -
it was a small window which would sometimes be hit. The main part of the fix was to check
*before* calling `sched.scheduler.run` if the client had called `stop`, but act on this only
after returning from `run`.

Now all `EventScheduler` tests pass.

## January 17, 2018B JMJ: Fixing issues with EventScheduler
There were two problems:
1. `sched.scheduler.run(blocking=True)` doesn't return if it is waiting for an event, even if
    the event has been canceled. This is because it's `delayfunc` defaults to `time.sleep`, and
    the latter will simply sleep for the entire duration to the (now canceled) event. I first fixed
    by providing my own `delayfunc` that would wait for `self._cancelevent` - so it would bail out
    early if the lattter was signaled. This worked, but I backed out of that because of the next
    problem.
2. `sched.scheduler.cancel(event)` is slow - about 1000 calls per second on my Elitebook. For
    comparison, `time.sleep(0)` is about 1 million calls per second. So it doesn't make
    sense to cal `cancel` on every remaining scheduled event in `cancel_all` - when the 
    scheudler is winding down. So instead `cancel_all` simply signals the background thread
    using `self._cancelevent`. The background thread can no longer call 
    `self._scheduler.run(blocking=True)` because the scheduler would never quit early if
    there are still events in the queue. So I have to use the nonblocking version and implement
    delays in the background function itself:
    ```
       def threadfn():
            more = True
            while more:
                    delay = self._scheduler.run(blocking=False)
                    if delay:
                        if self._cancelevent.wait(delay):
                            trace("threadfn: CANCELING")
                            more = False # we are done - abandon the rest
                    else:
                        self._event.wait() # wait for more events...
                        self._event.clear()
                        more = not self._quit
    ```

With this change `test_cancel_scheduler` can now pump about 100,000 events per second
(was about 1000 events / sec) and `cancel_all` returns immediately.

NOTE: `time.sleep(0)` is about 1 million calls per second, but `time.sleep(0.00001)` is about
1000 calls per second - so any non-zero sleep, however small takes a minimum of about a millisecond.

```
$ py -m timeit -n 1 "for _ in range(10000): time.sleep(0)"
1 loop, best of 5: 8.8 msec per loop
$ py -m timeit -n 1 "for _ in range(10000): time.sleep(0.000001)"
1 loop, best of 5: 15.3 sec per loop
```


## January 17, 2018A JMJ: Schedule large numbers of events.
In Java, we have `java.util.Timer` with its `schedule` methods that can be used to
schedule a large number of tasks with little overhead, all sharing the same thread.

The closest equivalent in the Python standard library is `sched.scheduler`. But they are not 
completely equivalent, in that in Python we have to provide the thread execution context, and
some extra support when we have concurrent additions to the events being scheduled.
So I created added the following class to `concurrent_helper`:

```
class EventScheduler:
    def start(self):
        """Starts the scheduler. It starts a background thread in which context the
        scheduling is done"""

    def schedule(self, delay, func) -> None:
        """Schedule event {func} to run after waiting {delay} from the point this
        call was made OR the scheduler was started, whichever happened later."""

    def cancel_all(self):
        """Cancel any pending and future events"""

    def get_exception_count(self) -> int:
        """Returns the count of exceptions thrown by scheduled events"""

    def stop(self, block=False):
        """Stop running once all pending events have been scheduled. If {block}
        then block until all events are completed. Once stopped, the scheduler
        cannot be restarted."""
```
Also added unit test class TestEventScheduler. Both pass.
The implementation has subtle sequencing to prevent corner cases of endless waiting.
See the "IMPLEMENTATION NOTE" at the head of class `EventScheduler`.

## January 16, 2018A JMJ: Property calling unittest from the command line.

`Unittest` wasn't working when sub modules are reaching over to others, like

```
from ..conc import concurrent_helper as ch
```
Fix is to specify the `-s` parameter when launching unittest, here from a directory
under `robotutil` such as `robotutil/comm`:

```
py -m unittest discover -s ../.. -k mock
```
Note it's `unittest discover`, not just `unittest`. It's the discover option that accepts
the `-s` (start directory) option.

The `-k testname_pattern` specifies a substring of test class or method to run. The following
alias seems to work:

```
alias unittest='py -m unittest discover -s ../.. -k'
```
I can now type `unittest mock` and it will discover and run all classes or methods that match
"mock". To run all tests, type `unittest .`


## January 15, 2018A JMJ: Porting notes for comm/test_robotcomm.MockTransport
Ported over `MockTransport` (not executed yet - just passes Pylint)

Porting strategy:
- Start by copying over chunks of Java code - smallest unit for which a simple unit test can be
  ported or written.
- Bulk remove ending ';', clean up trailing whitespaces, bulk change '//' to '#'
- Bulk change 'this.' to 'self.'
- Temporarily disable  Pylint invalid name warning: `# pylint: disable=invalid-name`
- Starting porting from the top. Do NOT change names from camel casing to snake-casing (yet).
  This is so that we don't introduce typos before any unit testing is done.
- Once the first pass is complete, clean up Pylint warnings until you get a perfect score.
  (if necessary selectively disable Pylint warnings - but sparingly)
- Then get unit tests to run (these will themselves have to be ported over - following the same
  process)
- Random: I'm just using the module-level random functions - random.random(), etc, rather
	than instantiate an instance of random.Random. We don't mind sharing randomness state.
- Timer: Each Python `threading.Timer` instance is a one-shot timer, compared with
	Java`Timer` object.
- Where we print to `sderr` - use `warning.warn()` instead, and consider other case here we could use
	`warn`.
- For now, try to use native logging as-is, based on Python's recommendations. At some point,
  look into using the exact same output format between Java and Python.
  	- define `trace` as a module-level function, and use this pattern for trace messages:

	```
		if trace:
		    trace(...)
	```
  I had tried `trace and trace(...)` by Pylint complained about not using the result of
  that expression, and I didn't want to disable that warning. Pylint does warn that 
  about the module-level `trace` not being all-caps, but I disable that selectively (for now) - 
  see the beginning of test_robotcomm.py.
  [UPDATE] Pylint complains about `if trace:` because it considers `trace` to be a constant.
  So now it's `if tracing():`, where tracing is a method.

- Remove @Override - don't bother commenting it. Visual noise. Maybe instead cluster all
  methods that implement an interface together with some heading comments

Used chained comparison `assert 0 <= failurerate <= 1` - Pylint pointed this out

## January 14, 2018E JMJ: Finished concurrent unit test for ConcurrentDict
This is `TestConcurrentDict.test_concurrent_cdict`. Multiple threads party on
a shared `ConcurrentDict`. There are shared keys - all threads party on them -- and
private keys, that are specific to specific threads. When using private keys, the tests
verify that the dictionary values are exactly as expected.

One strange thing: I disabled the locking for the `set` and `pop` methods on
`ConcurrentDict`, expecting the concurrent tests to fail when attempting to run `process_all`.
They ran fine, even with 100,000 iterations with multiple threads! I think that the
`list(_dict)` that extracts the keys of the dictionary into a list is effectively
atomic. If true, this means there is no need to use a lock after all, at least assuming
the GIL in CPython. However the lock should remain, as it allows for future, more complex 
atomic operations such as 'construct new object if object not present`.

## January 14, 2018D JMJ: Displaying uncaught exceptions:
In `test_concurrent_helper.py`, Started wrapping the top of threadpool workers with this - so we get information about
line numbers, etc.
```
	except Exception as exc:
            print("Worker {}: UNCAUGHT EXCEPTION {}", name, exc)
            traceback.print_exc()
            raise exc

```
See `TestConcurrentDict.test_concurrent_cdict` for an example.

## January 14, 2018C JMJ: Added ConcurrentDict.pop, and started unit tests
Needed a way to delete items in the dictionary (`del` or equivalent). Decided on
implementing `pop` - same semantics as `dict.pop`. Additional methods can be added as needed,
especially smart insert and delete that atomically call a function and conditionally
perform the operation (equivalent to Java's methods for concurrent map and list).

`pop` has an optional parameter that doesn't have a default. I didn't know how to implement
this, and looked online. Settled on the following:

```
# module level...
_NO_DEFAULT = object() # To check if an optional parameter was specified in selected method calls
...
class ConcurrentDict:
    ...
    def pop(self, key, default=_NO_DEFAULT):
        with self._lock:
            if default is _NO_DEFAULT:
                return self._dict.pop(key)
            return self._dict.pop(key, default)
```
This works as long as the client does not pass in this module's `-NO_DEFAULTi` value as the default,
which they have no business doing. The overhead is one object for the whole module.
Presumably `dict.pop` does something similar - should check.


## January 14, 2018B JMJ: Added AtomicNumber methods add and value.
In `conc/concurrent_helper.py`. Also added unit tests for them. All pass.

## January 14, 2018A JMJ: Finished complex unit test: TestConcurrentDeque.test_concurrent
This tests creates a shared `ConcurrentDeque`. Each concurrent worker inserts a unique "stream"
if tuples to either end of the deque. The worker randomly appends and pops items, occasionally
clears and occasionally tests the state of a snapshot of the queue using the `process` method.
Exceptions in the worker thread are propagated to the main thread so failures are properly
reported by `unittest` (see January 13, 2018A note).

On thing I don't understand is how the `self` object is propagated in the call to `_worker` below:
[UPDATE: This is because `self._worker` is a so-called "bound function" - see Python docs]

```
with concurrent.futures.ThreadPoolExecutor(max_workers) as ex:
            futures = [ex.submit(self._worker, 'task'+str(i), cdq, opcount)
                       for i in range(num_workers)]

```
Initially, I passed in `self` explicitly (before `'task'+str()`), but that actually seemed to
insert `self` twice! I'm confused, because I thought that the expression `self._worker` simply
passes a pointer to the function object - so how does Python know to insert `self` (and where
does it even get a hold of `self`?). It's like it's created a curried version of `self._worker`
that includes `self` as the first argument, appending the remaining arguments. Perhaps this
is what happens whenever the form `a.func` is passed in where a function is expected? If so
I was unaware of this.

Other learnings:
- Made use of `unittest.TestCase` methods `assertGreater`, `assertLess` (not `assertLesser`)
  and `fail` for the first time.
- Realized that popping from an empty collection raises an `IndexError`.
- Realized that functions nested within a function must be defined before they are used. This
  makes sense, of course, but I was treating those as class-level or module-level functions
  which can be specified in any order. The latter makes sense because the functions are
  actually not _executed_ when evaluating the class or module. If we attempted to execute
  a function defined later in the top level of a class (say when defining a class attribute)
  or top level of the module, then that function _would_ have to be defined earlier.


## January 13, 2018B JMJ: Disabling specific Pylint warnings
Just add a comment to the line as follows. There are more options too, but this one is most
locally targeted

```
 _deque = cdq._deque # pylint: disable=protected-access
```
The above was in a piece of test code that needed to verify the state of a private variable.
Without the Pylint comment, Pylint generates the following warning:

```
test_concurrent_helper.py:171:17: W0212: Access to a protected member _deque of a client class (protected-access)
```

## January 13, 2018A JMJ: Dealing with exeptions in concurrent.futures.ThreadPoolExecutor threads
To catch exceptions in unitttest, you have to transfer over any
assertions raised. You can do this by calling
exception = future.exception() # gets exception raised (of None) in that task
self.assertFalse(exception) - will display the exception raised. This works
even with unit tests. 

For example:

```
  def test_concurrent(self):
  	...

	# this function is called in a thread pool thread
        def worker0(name):
            print("\nI AM WORKER " + name)
            self.assertAlmostEqual(3.15, 3.2)

	# main thread launches a bunch of instances of the above, and then
	# checks each for an exception - the first one that has an exception
	# causes the 'self.assertFalse` to fail
        with concurrent.futures.ThreadPoolExecutor(max_workers) as ex:
            futures = [ex.submit(worker0, 'task'+str(i)) for i in range(num_workers)]
            for future in futures:
                exception = future.exception() # wait until task complete
                self.assertFalse(exception)
```
The above code fails in unittest with the following output:

```
	Traceback (most recent call last):
	  File "c:\Users\jmj\Documents\GitHub\robotics\python_robotutils\robotutils\conc\test_concurrent_helper.py", line 116, in test_concurrent
	    self.assertFalse(exception)
	AssertionError: AssertionError('3.15 != 3.2 within 7 places (0.050000000000000266 difference)') is not false
```
If the main line code had not called `future.exception` the test would have passed without
reporting anything wrong!

## January 11, 2018A JMJ: Exploring concurrent data structures for queues and dictionaries
In the Java version of Robotutils, we use the following concurrent classes:

```
java.util.concurrent.ConcurrentHashMap;
java.util.concurrent.ConcurrentLinkedQueue;

java.util.concurrent.ExecutorService;  -- created by Executors.newFixedThreadPool
	- map nicely to Python concurrent.futures.ThreadPoolExecutor (there is also a process pool
	  version, but we don't want that)
java.util.concurrent.atomic.AtomicLong;
	  (concurrent.futures.Future) to get the result either synchronously (with timeout option)
	  or asynchronously via callback.
	- didn't find an equivalent, so wrote my own (AtomicNumber)
```
I haven't found a definitive replacement for concurrent queues and dictionaries. Here
are candidates for replacing `ConcurrentLinkedQueue`:

```
queue.Queue
queue.SimpleQueue (3.7)
collections.dequeue
```
I couldn't find candidates for replacing `ConcurrentHashMap`

One key requirement is the ability to iterate over the items in the queues and dictionaries - with
semantics similar to the Java versions.

`queue.Queue` has semantics I don't need like blocking and for clients to be able to check if 
a queue item has been processed. Both `Queue` and `SimpleQueue` seem tailored for inter thread
communications, while we are using these data structures to keep track of ongoing messages or commands.
Their documentation mentions nothing about iterating over items in the queue.
So I'm disinclined to use either `Queue` or `SimpleQueue`.

Proposal: to write a set of helper methods that perform thread safe lookup and iteration over
the Python  `dequeue` and `dict` objects.

```
ConcurrentDict
	methods: get, set, process_all, etc
	underlying data structure: dict

ConcurrentDeque
	methods: append, popleft, process_all, etc
	underlying data structure: collections.dequeue
```
We may find better standard library classes to use later, but for now it unblocks us from
going on with the port of Robotcomm.

## January 10, 2018A JMJ: Pylint doesn't like nested classes as function return annotation

```
class DatagramTransport(abc.ABC):
    ....
    class RemoteNode(abc.ABC):
    ...
    @abc.abstractmethod
    def new_remotenode(self, address): -> RemoteNode # Pylint doesn't like it
    ...

```
In the above, Pylint reports that `RemoteNode` is an undefined variable, but the code actually 
runs. If I replace `RemoteNode` with `DatagramTransport.RemoteNode`, `__class__.RemoteNode`
or `__self__.RemoteNode`, then Pylint is happy but I can't actually run the code using unittest.
I think Pylint is wrong here - the internal class was just defined earlier in the code, so it
should be recognizable, but it is not. For now I've commented out the return type.

## January 9, 2018B JMJ: Implemented AtomicNumber class, and started using doctest
Wrote `misc/atomic.py`, a module that implements various atomic operations (for now just `next`).
This code is adapted from https://gist.github.com/benhoyt/8c8a8d62debe8e5aa5340373f9c509c7,
including the use of `doctest`, which is so elegant! The original (Ben Hoyt's) code 
had more elaborate doc tests that including creating many threads.

```
class AtomicNumber:

    ...
    def next(self):
        """
        Atomically increment the counter and return the new value
        >>> counter = AtomicNumber(40)
        >>> counter.next()
        41
        >>> counter.next()
        42
        """
        with self._lock:
            self._value += 1
            return self._value


if __name__ == '__main__':
    import doctest
    doctest.testmod()
```
Later we can add compare exchange, etc, as required.

## January 9, 2018A JMJ: Porting over the DatagramTransport interface
Planning to eliminate the Address interface. The original idea behind Address was to
separate out the time-consuming operation of address resolution. In the case of UDP,
this can involve a time-consuming DNS lookup. The idea is that `DatagramTransport.resolveAddress` can
be time consuming, while `DatagramTransport.newRemoteNode` can be a non-blocking, in-memory operation

On the other hand, in actual use, it looks like both `resolveAddress` and `newRemoteNode` are called exactly once per distinct address, so as long as we make it clear in the docs that `newRemoteNode`
can potentially block, depending on the specific transport and the contents of the text-form of the
address (for UDP, it's an IP address vs. a name), it should be fine and gets rid of `Address` as an interface type.

## January 7, 2019C JMJ: What about Python's own logging support?
Python has pretty good logging functionality on its own - see
https://docs.python.org/3/library/logging.html

It supports multiple consumers, and a hierarchy of named loggers and multiprocessor support.
It is very sophisticated, with lots of documentation,
including this cookbook: https://docs.python.org/3/howto/logging-cookbook.html

This begs the question: can't we just adapt Python's existing logging support instead of a strict
port of Robotutils's structured logger from Java?

Generating log messages is similar: methods like:
```
log.info("msg") # Robotutils
vs.
logger.debug("msg") # Python
```
I think the _right_ approach is to leverage Python's logging support. To replace the innards of
our logging with Python's, but add the things we have, like:
- start/stop logging dynamically
- add dynamic tags to a logger that get inserted automatically, including relative time stamping.
- output that is in the "structured" format, including time stamp and unique ID.

This approach has the added benefit of getting to know Python's logging well, so it may be used in other
projects unrelated to Robotutils.

The plan: don't attempt to port over `StructuredLogging`. Instead just start porting `RobotComm` and
`_testRobotComm`, as comm/robotcomm and comm/test_robotcomm, and try to map its logging into
Python logging apis, or create a simple wrapper, especially for trace messages.


## January 7, 2019B JMJ: What to do about all the Java interface definitions in Robotutils?
I decided to define abstract base classes for most (all?) of them. Relevant articles:
https://pymotw.com/3/abc/ and article's it references, including Guido's original PEP.

The Java `StructuredLogger` class has the complete implementation of structured logging, including all
the interfaces and private classes it uses. For the first port, I'm inclined to keep this model,
i.e., a single module that contains everything (separate from the port of `LoggerUtils` that
contains higher level functionality).

Proposed module names:
```
logger/structlogger.py - the complete implementation of structured logging
logger/test_struct_logger.py - unit tests for the above

import ./structlogger as sl
```


## January 7, 2019A JMJ: All config_helper unit tests pass!
Apart from finding the right file objects to use, the porting of both the module and its
test was straightforward, if tedious. There are probably more elegant Python ways to
do the string processing but we have PEP 8 - clean code (Pylint reports no errors) and
the tests work, so time to move on to the next thing, which is BIG - the structured
logging framework...

## January 6, 2019C JMJ: First ported over test for config_helper passes
The first ported unit test for `config_helper` passes! It's `test_simple_section_usage`.
The main thing to fix was to remove the extra layer of buffered-reader. Instead I seek
back to the start of the input file when reading a section.

Details:
```
inp = "abc\ndef"
    reader = io.StringIO(inp) # in-memory stream input
    reader2 = io.TextIOWrapper(reader)
    for x in reader2:
        print(x)
Produces:
TypeError: underlying read() should have returned a bytes-like object, not 'str'
```
So we can't layer a `TextIOWrapper` over a text reader. Instead of attempting to open a new
`TextIOWrapper`, just use the passed-in reader (and writer), and in the case of the reader,
remember the initial position and seek back to it on exit.

The test code can read and write from / to strings:

```
    inp = "abc\ndef\nghi"
    reader = io.StringIO(inp) # in-memory stream input
    writer = io.StringIO()
    for x in reader:
        writer.write(x)
    print(writer.getvalue())
    writer.close()
Prints...
abc
def
ghi
```
This has been incorporated into `config_helper`.


## January 6, 2019B JMJ: Begun port of Java ConfigurationHelper to misc/config_helper
- Class `ConfigurationHelper` becomes module `config_helper`. The class only has static methods.
- JUnit test `ConfigurationHelperTest` becomes class `TestConfigHelper` in module
`test_config_helper`.
- Java `BufferedReader` and `BufferedWriter` map to `io.TextIoWrapper`.

Finished porting all the code in `ConfigurationHelper.java` to `config_helper.py`. However the 
one unit test ported over earlier fails - so need to debug - most likely the file I/O calls
need to be tweaked. But the ported code does pass PEP 8.

## January 6, 2019A JMJ: Completed implementation and tests for misc/strmap_helper
All tests pass. Unit tests did catch a bug - see the delta to `misc/strmap_helper.py` in the
accompanying checkin. Otherwise, debugging was chiefly type handling in the unit tests themselves.
One gotcha was that `isinstance(x, int)` returns True if `x` is a `bool`, which was not
intended. I switched to this check: `type(x) is int`, but eventually settled for
`isinstance(x, int) and not isinstance(x, bool)` because Pylint complained about 
using `type` instead of `isinstance`.


Note use of `@staticmethod` decoration for a few methods in `misc/test_strmap_helper`, such
as `build_random_input`. Works fine - can be invoked either via `self.method` or 
`Class.method` - in either case, the `self` argument is not supplied. `@staticmethod`
is not _required_ if you invoke that method via `class.method`, however if not specified,
you cannot invoke it via `self.method`, plus it is helpful to announce to everyone that
it is a static method. Doc: https://docs.python.org/3.7/library/functions.html#staticmethod

## January 4, 2019E JMJ: misc/strmap_helper.get_as_num method
Interesting that this method implements *six* methods in Java
(`[int, long, float] X [with-minmax, without-minmax]`).  The method
uses the type of the default value to determine what type of value to return. So
use `helper.get_as_num(key, 10, ...)` to get an integer, and
`helper.get_as_num(key, 10.0, ...)` to get a float value. The docstring reports this, but 
it may be a bit too clever.

## January 4, 2019D JMJ: Started adding unit tests for misc/strmap_helper.py
Unit tests are in `misc/test_strmap_helper.py`. The one test that's in there,
that tests (partially) the empty-dictionary case, runs successfully.

## January 4, 2019C JMJ: Started implementing misc/strmap_helper,py
So far so good. Very basic test at the bottom of the file. Code so far is 100% PEP 8 compliant.

## January 4, 2019B JMJ: New code structure
With relative module addressing in `test_msgmap.py`: `from . import msgmap`, 
I can run the unit tests there as long as I start `unittests` from either one or two
levels up - under `python_robotutils\robotutils` or `robotutils`. Also, for the firs time,
I can discover and run these test from Visual Studio Code!
Nothing special for Visual Studio Code configuration:

```
	"python.unitTest.unittestArgs": [
		"-v",
		"-s",
		"",    #<--- this is the test directory, set to empty
		"-p",
		"test*.py"
	    ],
	    "python.unitTest.pyTestEnabled": false,
	    "python.unitTest.nosetestsEnabled": false,
	    "python.unitTest.unittestEnabled": true
```
I earlier had the test directory set to "test" because the test code used to live under 
a test directory. This somehow prompted VS code to include a whole bunch of python system tests. 
I never got to the bottom of that. But for now things work well.

## January 4, 2019A JMJ: New code structure
Looked at various suggestions for structuring python projects, including...
- https://docs.python-guide.org/writing/structure/
- https://blog.ionelmc.ro/2014/05/25/python-packaging/#the-structure
It seems that larger projects advocate a separate test directory that sits parallel to the
code. However, for low-level unit tests, it's more convenient to keep the test source close
to the corresponding code to be tested, while both are being ported. So the plan is to break
the `robotutil` classes into sub packages, and embed the test code modules with the code - just
prefix each module with `test_`. The sub packages are:
- misc - `StringmapHelper` and `StructuredMessageMapper`
- logger - ported from `StructuredLogger`
- comm  - ported from `RobotComm`
- `logger_utils` - ported from `LoggerUtils`
- `comm_utils` - ported from `CommUtils`
These all can be hoisted out and exposed directly as modules off `robotutils`, but at this point
this structure will afford most rapid porting of functionality and unit tests, and there is a
_lot_ of porting to be done.

## December 24, 2018A JMJ: Porting Plan
- Top level classes, listed in the order they should be ported
	1. `StringmapHelper`
	1. `StructuredMessageMapper`
	1. `StructuredLogger`
	1. `ConfigurationHelper`
	1. `RobotComm`
	1. `LoggerUtils`
	1. `CommUtils`

- For each class in above list, port over `JUnit` tests and implementation in parallel

## December 23, 2018A JMJ: Considered, then dropped Pytest
`Pytest` is well supported and has many features over `unittest`. However it is
a separate package, and thus far, `robotutils` just uses built in Python
libraries, so we don't want to add anything unless there is a really compelling
reason to do so.



# TODOs and Open Issues for the Python robotutils package

#Plan
1. Implement rcping and rcecho - scripts to exercise EchoClient and EchoServer
1. Proof NOTES.md - there are a few typos here and there
1. [BIG] Pi: Install latest Raspbian and Python on BabyBot
1. [BIG] Get all `robotutils` unit tests to work on the Pi (BabyBot)
1. Get latest firmware and OS and FTC libs on the BabyBot roboRIO.
1. [BIG] Get Java `robotutils unit tests to work on the RIO (figure out how to run
   JUnit tests from the command line, and telnet into the RIO and run those tests.)
1. [BIG] Get the desktop PC, the RIO and the Pi to send messages to each other.
1. [BIG] Port commands and RT commands to Python, including unit tests.
1. [BIG] Get these command tests running on the Pi and RIO
1. [BIG] Get some kind of message + command test going between the Desktop, RIO and Pi



The following are postponed
1. [BIG!] StructuredLogger and part of StructuredLoggerTest (the part that doesn't use
	LoggerUtils).
	- Goes under robotutils/logger (haven't decided module names yet)
1. LoggerUtils and LoggerUtilsTest - the latter includes some test functionality not yet
	ported over from StructuredLoggerTest.
	- Goes under robotutils/logger (haven't decided module names yet)
1. Various message types and categories defined in StructuredLogger. A couple of them 
   have been temporarily placed in comm/_commlogger.py (E.g, TAG_TYPE and TAG_DEF_MSG,
   but there are a whole not more.

#Future
-`ConcurrentDeque.remove_some(quickfunc)` - hold the internal lock and removes elements for which
`quickfunc` returns true. Similarly, one for `ConcurrentDict`. Challenge is to do this
effeciently whether many or just a few items are to be removed.

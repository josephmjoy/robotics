# Design and Development Notes for Python port of Robotutils.


## January 16, 2018A JMJ: Schedule large numbers of events.
In Java, we have `java.util. Timerk` with its `schedule` methods that can be used to
schedule a large number of tasks with little overhead, all sharing the same thread.

The closes equivalent in the Python standard library is `sched.scheduler`. But they are not 
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

## January 16, 2018A JMJ: Propertly calling unittest from the command line.

`Unittest` wasn't working when submodules are reaching over to others, like

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




## January 14, 2018A JMJ: Finished concurrent unit test for ConcurrentDict
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

## January 4, 2019D JMJ: misc/strmap_helper.get_as_num method
Interesting that this method implements *six* methods in Java
(`[int, long, float] X [with-minmax, without-minmax]`).  The method
uses the type of the default value to determine what type of value to return. So
use `helper.get_as_num(key, 10, ...)` to get an integer, and
`helper.get_as_num(key, 10.0, ...)` to get a float value. The docstring reports this, but 
it may be a bit too clever.

## January 4, 2019C JMJ: Started adding unit tests for misc/strmap_helper.py
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

## December 23, 2018A JMJ: Considered, then dropped pytest
`Pytest` is well supported and has many features over `unittest`. However it is
a separate package, and thus far, `robotutils` just uses built in Python
libraries, so we don't want to add anything unless there is a really compelling
reason to do so.



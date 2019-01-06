# Design and Development Notes for Python port of robututils.

## January 6, 2019A JMJ: Completed implementation and tests for misc/strmap_helper
All tests pass. Unit tests did catch a bug - see the delta to `misc/strmap_helper.py` in the
accompanying checkin. Otherwise, debugging was chiefly type handling in the unit tests themselves.
One gotcha was that `isinstance(x, int)` returns True if `x` is a `bool`, which was not
intended. I switched to this check: `type(x) is int`, but eventually settled for
`isinstance(x, int) and not isinstance(x, bool)` because pylint complained about 
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



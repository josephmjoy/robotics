# Design and Development Notes for Python port of robututils.

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



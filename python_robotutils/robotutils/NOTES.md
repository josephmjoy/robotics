# Design and Development Notes for Python port of robututils.

## December 24, 2018A JMJ: Porting Plan
- Top level classes, listed in the order they should be ported
	1. StringmapHelper
	1. StructuredMessageMapper
	1. StructuredLogger
	1. ConfigurationHelper
	1. RobotComm
	1. LoggerUtils
	1. CommUtils

- For each class in above list, port over JUnit tests and implementation in parallel



## December 23, 2018A JMJ: Considered, then dropped pytest
`Pytest` is well supported and has many features over `unittest`. However it is
a separate package, and thus far, `robotutils` just uses built in Python
libraries, so we don't want to add anything unless there is a really compelling
reason to do so.



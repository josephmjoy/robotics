# Design and Development Notes for Python port of robututils.

## December 23, 2018B JMJ: Porting Plan
- Run `pylint` on `msgmap.py`, fix issues


## December 23, 2018A JMJ: Considered, then dropped pytest
`Pytest` is well supported and has many features over `unittest`. However it is
a separate package, and thus far, `robotutils` just uses built in Python
libraries, so we don't want to add anything unless there is a really compelling
reason to do so.



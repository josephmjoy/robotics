""" Helper classes and constants for logging """
import logging


# Add "TRACE" to the list of supported levels.
TRACELEVEL = 9 # Default level of trace messages
logging.addLevelName(TRACELEVEL, "TRACE")

class LevelSpecificLogger:
    """Creates a level-specific logger.
    >>> trace = LevelSpecificLogger(TRACELEVEL, "comm") # by name
    >>> trace.pause()
    >>> assert not trace.enabled()
    >>> trace.resume()
    >>> if trace.enabled(): trace("This is a trace message. i=%d", 42)
    >>> logger  = logging.getLogger("comm")
    >>> dbglog = LevelSpecificLogger(logging.DEBUG, logger) # by logger
    >>> dbglog("This is a debug message")
    """

    def __init__(self, level, logger):
        """Binds the level-specific logger to a logger, either specified by
        name (if {logger} is a string) or as a logger object."""
        if isinstance(logger, str):
            logger = logging.getLogger(logger)
        self.logger = logger # don't change once set
        self.level = level # don't change once set
        self._paused = False

    def __call__(self, *args, **kwargs):
        if not self._paused:
            self.logger.log(self.level, *args, **kwargs)

    def enabled(self) -> bool:
        """Whether or not the logger is enabled at this point in time."""
        return not self._paused and self.logger.isEnabledFor(self.level)

    def pause(self) -> None:
        """Pause logging"""
        self._paused = True


    def resume(self) -> None:
        """Resume logging, assuming logging is enabled for this level."""
        self._paused = False

if __name__ == '__main__':
    import doctest
    doctest.testmod()

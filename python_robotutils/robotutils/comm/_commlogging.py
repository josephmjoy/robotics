"""
Sets up logging for the comm module.
"""
import logging

TRACELEVEL = 9 # Default level of trace messages
LOGNAME = "robotutils.comm"

logging.addLevelName(TRACELEVEL, "TRACE")
_logger = logging.getLogger(LOGNAME) # pylint: disable=invalid-name

#
# Definitions pulled from Java robotutils.StructuredLogger
#
TAG_MSG = '_msg: ' # Message type


def _tracing() -> bool:
    """Whether or not tracing is enabled. Use it to conditinally execute code
    just for tracing."""
    return _logger.isEnabledFor(TRACELEVEL)


def _trace(msg, *args, **kwargs) -> None:
    """Trace message. Tracing analog to logger.debug, etc. It prefixes
    the message with '_ty: '. So the first word is expected to be the message
    type."""
    if _tracing():
        # There MUST be a space after ':' for YAML-like parsing to work.
        msg = TAG_MSG + msg
        # Generated examples: _ty: WARN_DROP Packet dropped
        # Generated examples: _ty: POINT x: 29.1 y: 40
        _logger.log(TRACELEVEL, msg, *args, **kwargs)

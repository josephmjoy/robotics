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
_TAG_TYPE = '_ty:' # Message type
_TAG_DEF_MSG = '_msg:' # Default key form essage contents


def _tracing() -> bool:
    """Whether or not tracing is enabled. Use it to conditinally execute code
    just for tracing."""
    return _logger.isEnabledFor(TRACELEVEL)


def _trace(msgtype, msg, *args, **kwargs) -> None:
    """Trace message. Tracing analog to logger.debug, etc"""
    if _tracing():
        assert _validtag(msgtype)
        # There MUST be a space after ':' for YAML-like parsing to work.
        msg = " ".join((_TAG_TYPE, msgtype, _TAG_DEF_MSG, msg))
        # Generated examples: _ty: WARN_DROP _msg: Packet dropped
        # Generated examples: _ty: POINT _msg: x: 29.1 y: 40
        # In the above case `_msg` would be empty
        _logger.log(TRACELEVEL, msg, *args, **kwargs)


def _validtag(tag):
    """Return True iff {tag} is valid: contains no whitespace or ':'"""
    for char in tag:
        if char in ': \t\r\n\f':
            return False
    return True

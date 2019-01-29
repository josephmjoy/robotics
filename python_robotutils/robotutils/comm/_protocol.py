"""
Internal module containing Robotcomm on-the-wire protocol constants and
helper classes. Some exmples:
    >>> msg1 = Datagram(DatagramType.MSG, 'ch1', None, None, None, None)
    >>> strmsg = str_from_datagram(msg1)
    >>> print(strmsg)
    3wIC,MSG,ch1,,,
    <BLANKLINE>
    >>> msg2 = datagram_from_str(strmsg)
    >>> msg1 == msg2
    True
    >>> moreparams = ('ch1', 'resptype', 42, CommandStatus.COMPUTING, 'respbody')
    >>> resp1 = Datagram(DatagramType.CMDRESP, *moreparams)
    >>> strresp = str_from_datagram(resp1)
    >>> resp2 = datagram_from_str(strresp)
    >>> print(strresp)
    3wIC,CMDRESP,ch1,resptype,2a,COMPUTING
    respbody
    >>> resp1 == resp2
    True
    >>> command_pending(resp2)
    True
    >>> command_completed(resp2)
    False
"""

from enum import Enum, IntEnum, auto
from collections import namedtuple
import string

# TODO: Remove...
# pylint: disable=invalid-name
PROTOCOL_SIGNATURE = "3wIC" # About 1 of 10E7 combnations.

#
# WARNING: The names in DatagramType and CommandStatus match their on-the-wire
# representations. Therefore they MUST NOT be changed or else it will break the
# protcol.
#

class DatagramType(Enum):
    """The type of the datagram"""
    MSG = auto()
    CMD = auto()
    CMDRESP = auto()
    RTCMD = auto()
    RTCMDRESP = auto()
    CMDRESPACK = auto()

class CommandStatus(Enum):
    """Command status"""
    QUEUED = auto()
    COMPUTING = auto()
    COMPLETED = auto()
    REJECTED = auto()


class Position(IntEnum):
    """Position of a field within the datagram header"""
    PROTO = 0
    DG_TYPE = 1
    CHANNEL = 2
    BODY_TYPE = 3
    CMDID = 4
    CMDSTATUS = 5


BODYTYPE_IDLIST = "IDLIST" # body of CMDRESP is a list of IDs.


Datagram = namedtuple('Datagram',
                      ('dgType', 'channel', 'bodyType', 'cmdId', 'status',
                       'body'))


def command_pending(dgram):
    """Whether or not the datagram indicates a command is pending"""
    return (dgram.dgType == DatagramType.CMDRESP
            and (dgram.status == CommandStatus.COMPUTING
                 or dgram.status == CommandStatus.QUEUED))


def command_completed(dgram):
    """Whether or not the datagram indicates a command is complete"""
    isresp = (dgram.dgType == DatagramType.CMDRESP
              or dgram.dgType == DatagramType.RTCMDRESP)
    return isresp and not command_pending(dgram)


# Extration attributes for differnt kinds of datagram types
# Used by parse_message
_COMMAND_INFO = {
    # pylint: disable=bad-whitespace
    #                      getCmdId  getStatus
    DatagramType.MSG:        (False, False),
    DatagramType.CMD:        (True,  False),
    DatagramType.CMDRESP:    (True,  True),
    DatagramType.RTCMD:      (True,  False),
    DatagramType.RTCMDRESP:  (True,  True),
    DatagramType.CMDRESPACK: (False, False)
}

BAD_HEADER_CHARS = frozenset(string.whitespace)
BAD_FIELD_CHARS = frozenset(',' + string.whitespace) # fields in headers

def datagram_from_str(dgramstr) -> Datagram:
    """Generates a datagram from text. Raises ValueError on errortr1
        Examples:
        - "1309JHI,MY_CHANNEL,MSG,MY_MSG_TYPE"
        - "1309JHI,MY_CHANNEL,CMD,MY_COMMAND_TYPE,2888AB89"
        - "1309JHI,MY_CHANNEL,CMDRESP,MY_RESPONSE_TYPE,2888AB89,OK"
    >>> moreparams = (42, CommandStatus.COMPLETED, "mbody")
    >>> resp2 = Datagram(DatagramType.CMDRESP, 'mychannel', 'mymsgtype', *moreparams)
    >>> command_pending(resp2)
    False
    >>> command_completed(resp2)
    True
    >>>
    """

    try:

        (header, headerLength) = _extract_header(dgramstr)
        dgType = DatagramType[header[Position.DG_TYPE]]
        getCmdId, getStatus = _COMMAND_INFO[dgType]
        channel = header[Position.CHANNEL]
        if not channel:
            raise ValueError("Missing channel name")
        if containschars(channel, _protocol.BAD_FIELD_CHARS):
            raise ValueError("Channel name has invalid characters")

        bodyType = header[Position.BODY_TYPE] or None # convert '' to None

        if dgType == DatagramType.CMDRESPACK:
            if bodyType != BODYTYPE_IDLIST:
                raise ValueError("Unexpected CMDRESPACK message type")

        cmdId = None
        if getCmdId:
            if len(header) <= Position.CMDID:
                raise ValueError("Malformed header - missing cmd ID")
            try:
                cmdId = int(header[Position.CMDID], 16) # Id is Hex
            except ValueError as exp:
                raise ValueError("Malformed header - invalid cmd ID") from exp

        status = None
        if getStatus:
            if len(header) <= Position.CMDSTATUS:
                raise ValueError("Malformed header - missing [rt]cmd status")
            statusStr = header[Position.CMDSTATUS]
            status = CommandStatus[statusStr]

        body = dgramstr[headerLength+1:] or None # (+1 to skip '\n') could be empty
        return Datagram(dgType, channel, bodyType, cmdId, status, body)

    except KeyError as exp:
        raise ValueError("Malformed header") from exp


def str_from_datagram(dgram, extrabody='') -> str:
    """Converts a message to its on-the-wire form"""
    d = dgram # pylint: disable=invalid-name
    bodytype = d.bodyType if d.bodyType else ''
    status = d.status.name if d.status else ''
    body = d.body if d.body else ''
    cmdid = hex(d.cmdId)[2:] if d.cmdId else '' # hex without '0x' prefix
    last = ''.join((status, '\n', body, extrabody))
    parts = (PROTOCOL_SIGNATURE, d.dgType.name, d.channel, bodytype,
             cmdid, last)
    return ",".join(parts)

#
# Helper methods
#
def containschars(str_, charset) -> bool:
    """Returns if {str} contains any chars in {chars}"""
    for char in str_:
        if char in charset:
            return True
    return False


def _extract_header(dgramstr):
    """Return tuple (header, headerlen)"""

    if not dgramstr.startswith(PROTOCOL_SIGNATURE):
        raise ValueError("Incorrect protocol signature")

    try:
        headerlen = dgramstr.index('\n') # '\n' MUST be there
    except ValueError:
        raise ValueError("Datagram does not contain '\n' after header")

    headerstr = dgramstr[:headerlen]
    if containschars(headerstr, BAD_HEADER_CHARS):
        raise ValueError("Malformed header: contains invalid characters")

    header = headerstr.split(',')
    if len(header) < 4:
        raise ValueError("Malformed header. Two few header fields")

    assert header[Position.PROTO] == PROTOCOL_SIGNATURE # checked at top

    return (header, headerlen)


if __name__ == '__main__':
    import doctest
    doctest.testmod()

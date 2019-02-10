#!python
"""
rcping - ping a remote robotcomm instance with messages, commands and rt-commands
Author: JMJ
"""
import argparse
import logging
import pprint
import re
import string
import sys

from robotutils import logging_helper
from robotutils.comm_helper import EchoClient


_LOGGER = logging.getLogger('rcping')
_TRACE = logging_helper.LevelSpecificLogger(logging_helper.TRACELEVEL, _LOGGER)


def generate_argparser():
    """Generate the argument parser for this utility"""
    parser = argparse.ArgumentParser(description='Robotcomm ping utility',
                                     allow_abbrev=False)

    # Just one positional argument
    parser.add_argument('address', help='hostname[:port] of destination')

    # Number of messages - mutually exclusive options
    numgroup = parser.add_mutually_exclusive_group()
    numgroup.add_argument('-n', dest='count', metavar='count', action='store',
                          type=int, default=4, help='count of things to send')
    numgroup.add_argument('-t', dest='count', action='store_const', const=None,
                          help='send indifinitely')

    # Message size or explicit message - mutually exclusive options
    sizegroup = parser.add_mutually_exclusive_group()
    sizegroup.add_argument('-l', dest='size', metavar='size', action='store',
                           type=int, help='size of auto payload')
    payload_help = "send PAYLOAD, which has the form mytype[::mybody] or ::mybody"
    sizegroup.add_argument('-payload', help=payload_help)

    # Type of command - mutually exclusive, and exactly must be specified
    cmdgroup = parser.add_mutually_exclusive_group(required=True)
    cmdgroup.add_argument('-msg', action='store_true', help='send messages')
    cmdgroup.add_argument('-cmd', action='store_true', help='send commands')
    cmdgroup.add_argument('-rtcmd', action='store_true', help='send rt-commands')

    parser.add_argument('-c', dest='channel', metavar='CHANNEL',
                        help='name of channel')
    parser.add_argument('-rate', type=float, default=1.0,
                        help='rate in sends per second')
    choices_text = "TRACE DEBUG INFO WARNING ERROR CRITICAL"
    choices_list = choices_text.split()
    loglevel_help = 'sets logging level. LOGLEVEL is one of: ' + choices_text
    parser.add_argument('-loglevel', default='ERROR', choices=choices_list,
                        help=loglevel_help)

    return parser


def parse_args(args):
    """Parse input args, including all error handling.
    Returns a parameters object on success. Exits program
    on failure.
    """
    parser = generate_argparser()
    params = parser.parse_args(args)
    try:
        hostname, port = parse_address(params.address)
        params.hostname = hostname
        params.port = port
        msgtype, msgbody = parse_payload(params.payload)
        params.msgtype = msgtype
        params.msgbody = msgbody
    except ValueError as exp:
        parser.error(str(exp))
    return params


# Note: IPV6 addresses are not supported - it would
# be extra work to distinguish a port from a ':' inside
# the IPv6 address.
_HOSTNAME_REGEX = re.compile(r'(\w|\.)+')
_PORTRANGE = 41 # Range 41000-41999
def parse_address(address):
    """Parse address of the form hostname[:port]"""
    errmsg = '\n'.join(("Invalid address '{}'".format(address),
                        "Hostname should have the form NAME_OR_IP[:PORT]"))
    hostname, *rest = address.split(':')
    if not _HOSTNAME_REGEX.fullmatch(hostname) or len(rest) > 1:
        raise ValueError(errmsg)
    port = int(rest[0]) if rest else None
    if port is not None and port//1000 != _PORTRANGE:
        msg = "Port must be in the range {} to {}"
        minport = _PORTRANGE*1000
        raise ValueError(msg.format(minport, minport+999))
    return (hostname, port)


_BAD_MSGTYPE_CHARS = string.whitespace + ','
_PAYLOAD_SEPARATOR = '::'
def parse_payload(payload):
    """Parse payload, which has the form type or ::body or type::body"""
    msgtype, msgbody = None, None
    if payload:
        errmsg = '\n'.join(("Invalid payload '{}'".format(payload),
                            "Payload should have the form TYPE or ::TYPE or TYPE::BODY"))
        # We pick the first instance of '::'
        try:
            index = payload.index(_PAYLOAD_SEPARATOR)
            msgtype = payload[:index] or None
            msgbody = payload[index+len(_PAYLOAD_SEPARATOR):] # skip past '::'
            msgbody = msgbody or None # convert '' to None
        except ValueError:
            msgtype = payload
        if msgtype and containschars(msgtype, _BAD_MSGTYPE_CHARS):
            raise ValueError(errmsg)
    return (msgtype, msgbody)


def send_messages(client, count):
    """Send messages using an instance of echo client"""
    receive_count = 0

    def send_handler(resptype, respbody):
        msg = "Sending: '{}::{}'".format(resptype, respbody)
        _TRACE(msg)
        print(msg)

    def response_handler(resptype, respbody):
        msg = "Response: '{}::{}'".format(resptype, respbody)
        _TRACE(msg)
        print(msg)
        nonlocal receive_count
        receive_count += 1 # assume call to hander is serialized

    # send_messages will block until done...
    _TRACE("GOING TO SEND MESSAGES")
    client.send_messages(count, send_handler=send_handler,
                         response_handler=response_handler)
    _TRACE("DONE SENDING MESSAGES")
    print("Received = {}".format(receive_count))


def set_loglevel(strloglevel):
    """Sets up logging with the specified logging level"""
    if strloglevel == 'TRACE':
        # Special case - not defined in logging module
        level = logging_helper.TRACELEVEL
    else:
        choices = "DEBUG INFO WARNING ERROR CRITICAL".split()
        index = choices.index(strloglevel)
        level = getattr(logging, choices[index])
    logging.basicConfig(level=level)


def main(args):
    """Main entry point"""
    params = parse_args(args)
    set_loglevel(params.loglevel)
    _LOGGER.info("parameters:\n%s", pprint.pformat(vars(params)))

    client = EchoClient('localhost')
    try:
        client.set_parameters(rate=params.rate)
        if params.msg:
            send_messages(client, params.count)
        elif params.cmd:
            print('send_commands(params)')
        elif params.rtcmd:
            print('send_rtcommands(params)')
    except Exception: # pylint: disable=broad-except
        _LOGGER.exception()
    finally:
        client.close()


def containschars(str_, charset) -> bool:
    """Returns if {str} contains any chars in {chars}"""
    for char in str_:
        if char in charset:
            return True
    return False

main(sys.argv[1:])

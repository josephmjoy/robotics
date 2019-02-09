#!python
"""
rcping - ping a remote robotcomm instance with messages, commands and rt-commands
Author: JMJ
"""
import argparse
import sys
import re

def generate_argparser():
    """Generate the argument parser for this utility"""
    parser = argparse.ArgumentParser(description='Robotcomm ping utility',
                                     allow_abbrev=False)

    # Just one positional argument
    parser.add_argument('address', help='hostname[:port] of destination')

    # Number of messages - mutually exclusive options
    numgroup = parser.add_mutually_exclusive_group()
    numgroup.add_argument('-n', metavar='count', action='store', type=int,
                          default=4, help='count of things to send')
    numgroup.add_argument('-t', action='store_true', help='send indifinitely')

    # Message size or explicit message - mutually exclusive options
    sizegroup = parser.add_mutually_exclusive_group()
    sizegroup.add_argument('-l', metavar='size', action='store', type=int,
                           help='size of auto payload')
    payload_help = "send PAYLOAD, which has the form mytype[::mybody] or ::mybody"
    sizegroup.add_argument('-payload', help=payload_help)

    # Type of command - mutually exclusive, and exactly must be specified
    cmdgroup = parser.add_mutually_exclusive_group(required=True)
    cmdgroup.add_argument('-msg', action='store_true', help='send messages')
    cmdgroup.add_argument('-cmd', action='store_true', help='send commands')
    cmdgroup.add_argument('-rtcmd', action='store_true', help='send rt-commands')

    parser.add_argument('-c', metavar='CHANNEL', help='name of channel')

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


_HOSTNAME_REGEX = re.compile(r'^(\w\.)+$')
_PORTRANGE = 41 # Range 41000-41999
def parse_address(address):
    """Parse address of the form hostname[:port]"""
    errmsg = '\n'.join(("Invalid address '{}'".format(address),
                        "Hostname should have the form name_or_ip[:port]"))
    hostname, *rest = address.split(':')
    if not _HOSTNAME_REGEX.match(hostname) or len(rest) > 1:
        raise ValueError(errmsg)
    port = int(rest[0]) if rest else None
    if port is not None and port//1000 != _PORTRANGE:
        msg = "Port must be in the range %d to %d"
        minport = _PORTRANGE*1000
        raise ValueError(msg.format(minport, minport+999))
    return (hostname, port)


def parse_payload(payload):
    """Parse payload, which has the form type or ::body or type::body"""
    if payload:
        return ("mytype", "mybody")
    return(None, None)
    #raise ValueError("Invalid payload. Payload is like msgtype or msgtype::body or ::body")


def main(args):
    """Main entry point"""
    params = parse_args(args)
    print(params)
    # client = EchoClient(...)
    if params.msg:
        print('send_messages(params)')
    elif params.cmd:
        print('send_commands(params)')
    elif params.rtcmd:
        print('send_rtcommands(params)')
    # client.shutdown()

print(parse_address('localhost'))

#main(sys.argv[1:])
#ARGS1 = [""]
#ARGS2 = ["localhost"]
#ARGS3 = "-msg localhost".split()
#ARGS4 = "-msg -n 7 -payload msgtype rpi0:4900".split()
#main(ARGS4)

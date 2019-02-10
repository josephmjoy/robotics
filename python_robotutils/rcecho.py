#!python
"""
rcecho - runs a robotcomm echo server as a console application.
Author: JMJ
"""
import argparse
import logging
import pprint
import re
import sys
import time

from robotutils import logging_helper
from robotutils.comm_helper import EchoServer


_LOGGER = logging.getLogger('rcecho')
_TRACE = logging_helper.LevelSpecificLogger(logging_helper.TRACELEVEL, _LOGGER)


def generate_argparser():
    """Generate the argument parser for this utility"""
    parser = argparse.ArgumentParser(description='Robotcomm echo server',
                                     allow_abbrev=False)

    # Just one positional argument
    parser.add_argument('server_address', help='hostname[:port] of this server')

    parser.add_argument('-c', dest='channel', metavar='CHANNEL',
                        help='name of channel')

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
        hostname, port = parse_address(params.server_address)
        params.hostname = hostname
        params.port = port
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

    server = EchoServer('localhost')
    server.start()
    try:
        while True:
            server.periodic_work()
            time.sleep(0.1)
    except KeyboardInterrupt:
        _LOGGER.info("KeyboardInterrupt raised. Quitting")
    finally:
        server.stop()

main(sys.argv[1:])

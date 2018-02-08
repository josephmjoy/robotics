import socket
import struct
import sys
import robotutils

multicast_group = '239.255.18.99'
server_address = ('', 41899)

# Create the socket
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

# Bind to the server address
sock.bind(server_address)

# Tell the operating system to add the socket to the multicast group
# on all interfaces.
group = socket.inet_aton(multicast_group)
mreq = struct.pack('4sL', group, socket.INADDR_ANY)
#sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)


# Receive/respond loop
print('starting receive loop')
while True:
    # print ('\nwaiting to receive message', file=sys.stderr)
    data, address = sock.recvfrom(1024)
    
    # print ('received %s bytes from %s' % (len(data), address))
    msg = data.decode('utf-8')
    #d = robotutils.toDict(msg)
    #print(d)
    print(msg)

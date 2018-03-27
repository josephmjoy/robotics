// Various helper classes to make it easier to use the RobotComm communication infrastructure.
// Created by Joseph M. Joy (https://github.com/josephmjoy)

package com.rinworks.robotutils;

import java.util.function.BiConsumer;

import com.rinworks.robotutils.RobotComm.DatagramTransport;

public class CommUtils {
    
    public class EchoServer {
        public EchoServer(String address, int port) {}
        public void run() {}
    }
    
    public class EchoClient {
        public EchoClient(String localAddress, String remoteAddress, int remotePort) {
            
        }
        public void run() {}
    }

    
    public static DatagramTransport createUdpTransport() {
        return new UdpTransport();
    }
    
    
    private static class UdpTransport implements DatagramTransport{


        @Override
        public Address resolveAddress(String address) {
            // TODO Auto-generated method stub
            return null;
        }



        @Override
        public RemoteNode newRemoteNode(Address remoteAddress) {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public void close() {
            // TODO Auto-generated method stub
            
        }



        @Override
        public void startListening(BiConsumer<String, RemoteNode> handler) {
            // TODO Auto-generated method stub
            
        }



        @Override
        public void stopListening() {
            // TODO Auto-generated method stub
            
        }
        
    }

}

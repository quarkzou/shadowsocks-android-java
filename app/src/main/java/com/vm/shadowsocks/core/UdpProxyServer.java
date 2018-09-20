package com.vm.shadowsocks.core;

import android.util.Log;
import android.util.SparseArray;

import com.vm.shadowsocks.tcpip.CommonMethods;
import com.vm.shadowsocks.tcpip.IPHeader;
import com.vm.shadowsocks.tcpip.UDPHeader;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;

public class UdpProxyServer implements Runnable
{
    final static ByteBuffer GL_BUFFER = ByteBuffer.allocate(20000);
    public boolean Stopped;
    public short Port;

    Selector m_Selector;
    DatagramChannel m_ServerSocketChannel;



    Thread m_ServerThread;

    public UdpProxyServer(int port) throws IOException
    {
        m_Selector = Selector.open();
        m_ServerSocketChannel = DatagramChannel.open();
        m_ServerSocketChannel.configureBlocking(false);
        m_ServerSocketChannel.socket().bind(new InetSocketAddress(port));
        m_ServerSocketChannel.register(m_Selector, SelectionKey.OP_READ, m_ServerSocketChannel);

        this.Port = (short) m_ServerSocketChannel.socket().getLocalPort();
        System.out.printf("AsyncUdpServer read on %d success.\n", this.Port & 0xFFFF);
    }

    public void start() {
        m_ServerThread = new Thread(this);
        m_ServerThread.setName("UdpProxyServerThread");
        m_ServerThread.start();
    }

    public void stop() {

    }

    @Override
    public void run() {
        try {
            while (true) {
                m_Selector.select();
                Iterator<SelectionKey> keyIterator = m_Selector.selectedKeys().iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    if (key.isValid()) {
                        try {
                            if (key.isReadable()) {
                                DatagramChannel c = (DatagramChannel)key.attachment();
                                ByteBuffer buffer = GL_BUFFER;
                                SocketAddress addr = c.receive(buffer);
                                Log.i("ZYDEBUG", String.format("%s", addr));
                                c.register(m_Selector, SelectionKey.OP_READ, c);
                            }
                            else if(key.isConnectable())
                            {
                                key.toString();
                            }
                            else if (key.isWritable())
                            {
                                key.toString();
                            }
                        } catch (Exception e) {
                            System.out.println(e.toString());
                        }
                    }
                    keyIterator.remove();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            this.stop();
            System.out.println("TcpServer thread exited.");
        }
    }

    public void HandleUdpPacket(IPHeader ipHeader, UDPHeader udpHeader)
    {
    }
}

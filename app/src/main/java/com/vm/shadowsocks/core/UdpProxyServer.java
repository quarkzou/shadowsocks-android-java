package com.vm.shadowsocks.core;

import android.util.Log;
import android.util.SparseArray;

import com.vm.shadowsocks.tcpip.CommonMethods;
import com.vm.shadowsocks.tcpip.IPHeader;
import com.vm.shadowsocks.tcpip.UDPHeader;
import com.vm.shadowsocks.tunnel.Tunnel;

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

    static final SparseArray<UdpTunnel> Tunnels = new SparseArray<UdpTunnel>();

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
        ByteBuffer buffer = UdpTunnel.GL_BUFFER;
        boolean bconn = false;
        try {
            while (true) {
                m_Selector.select();
                Iterator<SelectionKey> keyIterator = m_Selector.selectedKeys().iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    if (key.isValid()) {
                        try {
                            if (key.isReadable()) {
                                DatagramChannel c = (DatagramChannel)key.channel();
                                short localPortKey = (short)c.socket().getLocalPort();
                                if (localPortKey == Port)
                                {
                                    buffer.clear();
                                    InetSocketAddress addr = (InetSocketAddress)c.receive(buffer);
                                    short portKey = (short)addr.getPort();

                                    UdpTunnel remoteTunnel = Tunnels.get(portKey);
                                    if (remoteTunnel == null)
                                    {
                                        // build connection
                                        InetSocketAddress remoteAddr = getDestAddress(portKey);
                                        if(remoteAddr != null) {
                                            remoteTunnel = new UdpTunnel(remoteAddr, m_Selector);
                                            UdpTunnel localTunnel = new UdpTunnel(remoteAddr, m_Selector);
                                            remoteTunnel.connect(remoteAddr);
                                            InetSocketAddress localAddr = new InetSocketAddress(
                                                    remoteTunnel.getChannel().socket().getLocalAddress().getHostAddress(),0xFFFF & portKey);
                                            localTunnel.connect(localAddr);
                                            remoteTunnel.setBrotherTunnel(localTunnel);
                                            localTunnel.setBrotherTunnel(remoteTunnel);

                                            Tunnels.put(portKey, remoteTunnel);
                                        }
                                        else
                                        {
                                            Log.e("ZYDEUBG", "null remote addresses");
                                        }

                                    }

                                    buffer.flip();
                                    remoteTunnel.write(buffer, true);

//                                        DatagramChannel c2 = DatagramChannel.open();
//                                        LocalVpnService.Instance.protect(c2.socket());
//                                        try
//                                        {
//                                            c2.connect(remoteAddr);
//                                        }
//                                        catch (IOException e)
//                                        {
//                                            Log.e("ZYDEBUG", String.format("[UDP] Connect %s error", remoteAddr));
//                                            closeChannel(c2);
//                                        }
//                                        c2.configureBlocking(false);
//                                        c2.register(m_Selector, SelectionKey.OP_READ, portKey);
//
//                                        try
//                                        {
//                                            buffer.flip();
//                                            while (buffer.hasRemaining())
//                                                c2.write(buffer);
//                                        }
//                                        catch (IOException e)
//                                        {
//                                            Log.e("ZYDEBUG", String.format("[UDP] write to %s error", remoteAddr));
//                                            closeChannel(c2);
//                                        }
                                }
                                else
                                {
                                    UdpTunnel tunnel = (UdpTunnel)key.attachment();
                                    tunnel.onReadable(key);

//                                    buffer.clear();
//                                    int len = c.read(buffer);
//                                    int portKey = (short)key.attachment();
//                                    DatagramChannel c3 = DatagramChannel.open();
//                                    c3.connect(new InetSocketAddress(c3.socket().getLocalAddress().getHostAddress(), portKey & 0xFFFF));
//                                    c3.configureBlocking(false);
//                                    buffer.flip();
//                                    c3.write(buffer);
//                                    Log.i("ZYDEBUG", String.format("%d", len));
                                }
                            }
                            else if(key.isConnectable())
                            {
                                Log.e("ZYDEBUG", "Unexpect key status: connectable");
                            }
                            else if (key.isWritable())
                            {
                                UdpTunnel tunnel = (UdpTunnel)key.attachment();
                                tunnel.onWritable(key);
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

    InetSocketAddress getDestAddress(short portKey) {
        NatSession session = NatSessionManager.getSession(portKey);
        if (session != null) {
            return new InetSocketAddress(session.RemoteHost, session.RemotePort & 0xFFFF);
        }
        return null;
    }

    private void closeChannel(DatagramChannel channel)
    {
        try
        {
            channel.close();
        }
        catch (IOException e)
        {
            // Ignore
        }
    }
}

class UdpTunnel
{
    public final static ByteBuffer GL_BUFFER = ByteBuffer.allocate(20000);
    private ByteBuffer m_SendRemainBuffer;
    private DatagramChannel m_InnerChannel;
    private Selector m_Selector;
    private InetSocketAddress m_ServerEP;
    private DatagramChannel m_brotherChannel;
    public static long SessionCount;
    private UdpTunnel m_BrotherTunnel;
    private boolean m_Disposed;
    protected InetSocketAddress m_DestAddress;

    public UdpTunnel(InetSocketAddress serverAddress, Selector selector) throws IOException {
        DatagramChannel innerChannel = DatagramChannel.open();
        innerChannel.configureBlocking(false);
        this.m_InnerChannel = innerChannel;
        this.m_Selector = selector;
        this.m_ServerEP = serverAddress;
        SessionCount++;
    }

    public void connect(InetSocketAddress destAddress) throws Exception
    {
        if (LocalVpnService.Instance.protect(m_InnerChannel.socket())) {//保护socket不走vpn
            m_DestAddress = destAddress;
            m_InnerChannel.register(m_Selector, SelectionKey.OP_READ, this);
            m_InnerChannel.connect(destAddress);//连接目标
        }
        else {
            throw new Exception("VPN protect socket failed.");
        }
    }

    public void setBrotherTunnel(UdpTunnel brotherTunnel) {
        m_BrotherTunnel = brotherTunnel;
    }

    protected boolean write(ByteBuffer buffer, boolean copyRemainData) throws Exception {
        int bytesSent;
        while (buffer.hasRemaining()) {
            bytesSent = m_InnerChannel.write(buffer);
            if (bytesSent == 0) {
                break;//不能再发送了，终止循环
            }
        }

        if (buffer.hasRemaining()) {//数据没有发送完毕
            if (copyRemainData) {//拷贝剩余数据，然后侦听写入事件，待可写入时写入。
                //拷贝剩余数据
                if (m_SendRemainBuffer == null) {
                    m_SendRemainBuffer = ByteBuffer.allocate(buffer.capacity());
                }
                m_SendRemainBuffer.clear();
                m_SendRemainBuffer.put(buffer);
                m_SendRemainBuffer.flip();
                m_InnerChannel.register(m_Selector, SelectionKey.OP_WRITE, this);//注册写事件
            }
            return false;
        } else {//发送完毕了
            return true;
        }
    }

    public void onWritable(SelectionKey key) {
        try {
            if (this.write(m_SendRemainBuffer, false)) {//如果剩余数据已经发送完毕
                key.cancel();//取消写事件。
                m_BrotherTunnel.beginReceive();//这边数据发送完毕，通知兄弟可以收数据了。
            }
        } catch (Exception e) {
            this.dispose();
        }
    }

    protected void beginReceive() throws Exception {
        if (m_InnerChannel.isBlocking()) {
            m_InnerChannel.configureBlocking(false);
        }
        m_InnerChannel.register(m_Selector, SelectionKey.OP_READ, this);//注册读事件
    }

    public void dispose() {
        disposeInternal(true);
    }

    void disposeInternal(boolean disposeBrother) {
        if (m_Disposed) {
            return;
        } else {
            try {
                m_InnerChannel.close();
            } catch (Exception e) {
            }

            if (m_BrotherTunnel != null && disposeBrother) {
                m_BrotherTunnel.disposeInternal(false);//把兄弟的资源也释放了。
            }

            m_InnerChannel = null;
            m_SendRemainBuffer = null;
            m_Selector = null;
            m_BrotherTunnel = null;
            m_Disposed = true;
            SessionCount--;

        }
    }

    public void onReadable(SelectionKey key) {
        try {
            ByteBuffer buffer = GL_BUFFER;
            buffer.clear();
            int bytesRead = m_InnerChannel.read(buffer);
            if (bytesRead > 0) {
                buffer.flip();
                if (buffer.hasRemaining()) {//将读到的数据，转发给兄弟。
                    if (!m_BrotherTunnel.write(buffer, true)) {
                        key.cancel();//兄弟吃不消，就取消读取事件。
                        if (ProxyConfig.IS_DEBUG)
                            System.out.printf("%s can not read more.\n", m_ServerEP);
                    }
                }
            } else if (bytesRead < 0) {
                this.dispose();//连接已关闭，释放资源。
            }
        } catch (Exception e) {
            e.printStackTrace();
            this.dispose();
        }
    }

    public DatagramChannel getChannel()
    {
        return m_InnerChannel;
    }
}


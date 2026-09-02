package de.skyengine.client.network;

import de.skyengine.server.network.NettyTransportConnection;
import de.skyengine.server.network.VarIntFrameDecoder;
import de.skyengine.shared.network.PacketRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.InetSocketAddress;
import java.util.Objects;

/** Remote TCP connector. Packet handling remains on the client update thread. */
public final class NettyClientTransport implements AutoCloseable {
    private final EventLoopGroup eventLoop = new NioEventLoopGroup(1);
    private final PacketRegistry registry;
    private final int maximumFrameBytes;
    private final int connectTimeoutMillis;
    private NettyTransportConnection connection;

    public NettyClientTransport(PacketRegistry registry, int maximumFrameBytes) {
        this(registry, maximumFrameBytes, 10_000);
    }

    public NettyClientTransport(PacketRegistry registry, int maximumFrameBytes, int connectTimeoutMillis) {
        this.registry = Objects.requireNonNull(registry);
        this.maximumFrameBytes = maximumFrameBytes;
        if (connectTimeoutMillis < 1) throw new IllegalArgumentException("connectTimeoutMillis must be positive");
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public synchronized NettyTransportConnection connect(InetSocketAddress address) throws InterruptedException {
        if (this.connection != null) throw new IllegalStateException("Client transport already connected");
        final NettyTransportConnection[] created = new NettyTransportConnection[1];
        Bootstrap bootstrap = new Bootstrap()
                .group(this.eventLoop)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, this.connectTimeoutMillis)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel channel) {
                        NettyTransportConnection.Handler handler = new NettyTransportConnection.Handler();
                        NettyTransportConnection transport = new NettyTransportConnection(channel, registry, false);
                        created[0] = transport;
                        handler.connection(transport);
                        channel.pipeline().addLast("frame", new VarIntFrameDecoder(maximumFrameBytes));
                        channel.pipeline().addLast("packet", handler);
                    }
                });
        Channel channel = bootstrap.connect(address).sync().channel();
        if (created[0] == null) {
            channel.close().syncUninterruptibly();
            throw new IllegalStateException("Client channel initialized without transport");
        }
        this.connection = created[0];
        return this.connection;
    }

    public synchronized NettyTransportConnection connection() { return this.connection; }

    @Override public synchronized void close() {
        if (this.connection != null) this.connection.close();
        this.connection = null;
        this.eventLoop.shutdownGracefully().syncUninterruptibly();
    }
}

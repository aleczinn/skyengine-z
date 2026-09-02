package de.skyengine.server.network;

import de.skyengine.shared.network.PacketRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import de.skyengine.shared.network.packets.CorePackets;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NettyTransportServer implements AutoCloseable {
    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup workers = new NioEventLoopGroup();
    private final PacketRegistry registry;
    private final Consumer<NettyTransportConnection> acceptor;
    private final int maximumFrameBytes;
    private final Executor batchEncoder;
    private final ExecutorService ownedBatchEncoder;
    private final Function<CorePackets.ServerStatusRequest, CorePackets.ServerStatusResponse> statusResponder;
    private Channel serverChannel;

    public NettyTransportServer(PacketRegistry registry, int maximumFrameBytes,
                                Consumer<NettyTransportConnection> acceptor) {
        this(registry, maximumFrameBytes, 0, acceptor, null);
    }

    public NettyTransportServer(PacketRegistry registry, int maximumFrameBytes, int encoderThreads,
                                Consumer<NettyTransportConnection> acceptor) {
        this(registry, maximumFrameBytes, encoderThreads, acceptor, null);
    }

    public NettyTransportServer(PacketRegistry registry, int maximumFrameBytes, int encoderThreads,
                                Consumer<NettyTransportConnection> acceptor,
                                Function<CorePackets.ServerStatusRequest,
                                        CorePackets.ServerStatusResponse> statusResponder) {
        this.registry = Objects.requireNonNull(registry);
        this.maximumFrameBytes = maximumFrameBytes;
        this.acceptor = Objects.requireNonNull(acceptor);
        this.statusResponder = statusResponder;
        if (encoderThreads < 0 || encoderThreads > 256) throw new IllegalArgumentException("Invalid encoder threads");
        if (encoderThreads == 0) {
            this.ownedBatchEncoder = null;
            this.batchEncoder = Runnable::run;
        } else {
            java.util.concurrent.atomic.AtomicInteger ids = new java.util.concurrent.atomic.AtomicInteger();
            this.ownedBatchEncoder = Executors.newFixedThreadPool(encoderThreads, runnable -> {
                Thread thread = new Thread(runnable, "Chunk Packet Encoder " + ids.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            this.batchEncoder = this.ownedBatchEncoder;
        }
    }

    public synchronized void bind(InetSocketAddress address) throws InterruptedException {
        if (this.serverChannel != null) throw new IllegalStateException("Server is already bound");
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(this.boss, this.workers)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new io.netty.channel.WriteBufferWaterMark(256 * 1024, 1024 * 1024))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel channel) {
                        NettyTransportConnection.Handler handler = new NettyTransportConnection.Handler();
                        NettyTransportConnection connection = new NettyTransportConnection(channel, registry, true,
                                batchEncoder, statusResponder);
                        handler.connection(connection);
                        channel.pipeline().addLast("frame", new VarIntFrameDecoder(maximumFrameBytes));
                        channel.pipeline().addLast("packet", handler);
                        acceptor.accept(connection);
                    }
                });
        this.serverChannel = bootstrap.bind(address).sync().channel();
    }

    public synchronized InetSocketAddress localAddress() {
        return this.serverChannel == null ? null : (InetSocketAddress) this.serverChannel.localAddress();
    }

    @Override public synchronized void close() {
        if (this.serverChannel != null) this.serverChannel.close().syncUninterruptibly();
        this.serverChannel = null;
        this.boss.shutdownGracefully().syncUninterruptibly();
        this.workers.shutdownGracefully().syncUninterruptibly();
        if (this.ownedBatchEncoder != null) this.ownedBatchEncoder.shutdownNow();
    }
}

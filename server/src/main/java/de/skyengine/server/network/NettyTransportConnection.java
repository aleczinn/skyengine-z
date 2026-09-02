package de.skyengine.server.network;

import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.ConnectionStateMachine;
import de.skyengine.shared.network.DecodedPacket;
import de.skyengine.shared.network.DeliveryClass;
import de.skyengine.shared.network.DisconnectReason;
import de.skyengine.shared.network.LogicalChannel;
import de.skyengine.shared.network.PacketDirection;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.PacketBuffer;
import de.skyengine.shared.network.PacketRegistry;
import de.skyengine.shared.network.PacketType;
import de.skyengine.shared.network.ProtocolException;
import de.skyengine.shared.network.ProtocolFraming;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.network.transport.TransportConnection;
import de.skyengine.shared.network.transport.CompressionTransport;
import de.skyengine.shared.network.transport.BatchTransport;
import de.skyengine.shared.network.transport.TransportStats;
import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.net.SocketAddress;
import java.util.EnumMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** Packet-level TCP connection. World mutation never occurs on its Netty callbacks. */
public final class NettyTransportConnection implements TransportConnection, CompressionTransport, BatchTransport {
    public record BatchEncodingStats(long batches, long packets, long nanoseconds) {}
    private static final int INBOUND_CAPACITY = 4096;
    private static final int OUTBOUND_CAPACITY = 4096;

    private record EncodedOutbound(LogicalChannel channel, DeliveryClass delivery,
                                   Class<?> packetClass, long sequence, byte[] framed) {}
    private record CompressionSettings(String algorithm, int threshold, int maximumBytes, int level) {}
    private record PendingBatch(ConnectionState state, CompressionSettings compression,
                                java.util.List<PacketEnvelope> packets) {}

    private final String id = "tcp-" + UUID.randomUUID();
    private final Channel channel;
    private final PacketRegistry registry;
    private final PacketDirection inboundDirection;
    private final PacketDirection outboundDirection;
    private final ConnectionStateMachine state = new ConnectionStateMachine();
    private final ArrayBlockingQueue<byte[]> inboundFrames = new ArrayBlockingQueue<>(INBOUND_CAPACITY);
    private final EnumMap<LogicalChannel, ArrayBlockingQueue<EncodedOutbound>> outbound =
            new EnumMap<>(LogicalChannel.class);
    private final AtomicInteger outboundCount = new AtomicInteger();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicLong receivedPackets = new AtomicLong();
    private final AtomicLong receivedBytes = new AtomicLong();
    private final AtomicLong sentPackets = new AtomicLong();
    private final AtomicLong sentBytes = new AtomicLong();
    private volatile long lastPacketReceivedNanos;
    private final Executor batchEncoder;
    private final Function<CorePackets.ServerStatusRequest, CorePackets.ServerStatusResponse> statusResponder;
    private final ConcurrentLinkedQueue<PendingBatch> pendingBatches = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingBatchPackets = new AtomicInteger();
    private final AtomicBoolean batchDrainScheduled = new AtomicBoolean();
    private final AtomicLong encodedBatches = new AtomicLong();
    private final AtomicLong encodedBatchPackets = new AtomicLong();
    private final AtomicLong batchEncodingNanos = new AtomicLong();
    private String compression = "none";
    private int compressionThreshold;
    private int maximumDecompressedBytes = de.skyengine.shared.network.ProtocolLimits.MAX_DECOMPRESSED_BYTES;
    private int compressionLevel = 1;

    public NettyTransportConnection(Channel channel, PacketRegistry registry, boolean serverSide) {
        this(channel, registry, serverSide, Runnable::run, null);
    }

    public NettyTransportConnection(Channel channel, PacketRegistry registry, boolean serverSide,
                                    Executor batchEncoder) {
        this(channel, registry, serverSide, batchEncoder, null);
    }

    public NettyTransportConnection(Channel channel, PacketRegistry registry, boolean serverSide,
                                    Executor batchEncoder,
                                    Function<CorePackets.ServerStatusRequest,
                                            CorePackets.ServerStatusResponse> statusResponder) {
        this.channel = Objects.requireNonNull(channel);
        this.registry = Objects.requireNonNull(registry);
        this.batchEncoder = Objects.requireNonNull(batchEncoder);
        this.statusResponder = statusResponder;
        this.inboundDirection = serverSide ? PacketDirection.CLIENT_TO_SERVER : PacketDirection.SERVER_TO_CLIENT;
        this.outboundDirection = serverSide ? PacketDirection.SERVER_TO_CLIENT : PacketDirection.CLIENT_TO_SERVER;
        for (LogicalChannel logicalChannel : LogicalChannel.values()) {
            this.outbound.put(logicalChannel, new ArrayBlockingQueue<>(OUTBOUND_CAPACITY));
        }
    }

    @Override public String id() { return this.id; }
    @Override public SocketAddress remoteAddress() { return this.channel.remoteAddress(); }
    @Override public ConnectionState state() { return this.state.state(); }
    @Override public void transitionState(ConnectionState expected, ConnectionState next) {
        this.state.transition(expected, next);
    }

    @Override
    public boolean send(PacketEnvelope envelope) {
        if (!open()) return false;
        try {
            return enqueueEncoded(encode(envelope, state(), compressionSettings()));
        } catch (ProtocolException e) {
            disconnect(DisconnectReason.INTERNAL_ERROR, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean sendBatch(java.util.List<PacketEnvelope> packets) {
        if (!open() || packets.isEmpty() || packets.size() > 64) return false;
        java.util.List<PacketEnvelope> immutable = java.util.List.copyOf(packets);
        ConnectionState capturedState = state();
        try {
            for (PacketEnvelope envelope : immutable) {
                PacketType<?> type = this.registry.type(envelope.packet());
                if (type.direction() != this.outboundDirection || !type.states().contains(capturedState)) {
                    return false;
                }
            }
        } catch (ProtocolException e) {
            return false;
        }
        int size = immutable.size();
        int pending = this.pendingBatchPackets.addAndGet(size);
        if (pending > OUTBOUND_CAPACITY) {
            this.pendingBatchPackets.addAndGet(-size);
            return false;
        }
        this.pendingBatches.add(new PendingBatch(capturedState, compressionSettings(), immutable));
        scheduleBatchDrain();
        return true;
    }

    public int flushOutbound(int byteBudget) {
        if (!open() || !this.channel.isWritable()) return 0;
        int written = 0;
        boolean progress;
        do {
            progress = false;
            for (LogicalChannel logicalChannel : LogicalChannel.values()) {
                EncodedOutbound encoded = this.outbound.get(logicalChannel).peek();
                if (encoded == null) continue;
                if (written > 0 && written + encoded.framed().length > byteBudget) continue;
                this.outbound.get(logicalChannel).poll();
                this.outboundCount.decrementAndGet();
                this.channel.write(Unpooled.wrappedBuffer(encoded.framed()));
                this.sentPackets.incrementAndGet();
                this.sentBytes.addAndGet(encoded.framed().length);
                written += encoded.framed().length;
                progress = true;
                if (written >= byteBudget) break;
            }
        } while (progress && written < byteBudget && this.channel.isWritable());
        if (written > 0) this.channel.flush();
        return written;
    }

    void receive(ByteBuf frame) {
        byte[] body = new byte[frame.readableBytes()];
        frame.readBytes(body);
        this.lastPacketReceivedNanos = System.nanoTime();
        this.receivedPackets.incrementAndGet();
        this.receivedBytes.addAndGet(body.length);
        if (tryAnswerStatus(body)) return;
        if (!this.inboundFrames.offer(body)) {
            disconnect(DisconnectReason.INVALID_PACKET, "Inbound queue overflow");
        }
    }

    /**
     * Read-only server-list requests bypass the 20-TPS owner queue. They neither create a
     * player nor touch world state, so answering on Netty's event loop gives an actual network
     * RTT instead of adding an arbitrary 0..50 ms tick wait.
     */
    private boolean tryAnswerStatus(byte[] body) {
        if (this.statusResponder == null || this.inboundDirection != PacketDirection.CLIENT_TO_SERVER
                || state() != ConnectionState.HANDSHAKE || !this.compression.equals("none")) return false;
        try {
            DecodedPacket decoded = this.registry.decode(this.inboundDirection, ConnectionState.HANDSHAKE, body);
            if (!(decoded.packet() instanceof CorePackets.ServerStatusRequest request)) return false;
            CorePackets.ServerStatusResponse response = Objects.requireNonNull(this.statusResponder.apply(request));
            byte[] encoded = this.registry.encode(this.outboundDirection, ConnectionState.HANDSHAKE,
                    new PacketEnvelope(response));
            byte[] framed = ProtocolFraming.frame(encoded);
            this.channel.writeAndFlush(Unpooled.wrappedBuffer(framed));
            this.sentPackets.incrementAndGet();
            this.sentBytes.addAndGet(framed.length);
            return true;
        } catch (ProtocolException | RuntimeException invalid) {
            disconnect(DisconnectReason.INVALID_PACKET,
                    invalid.getMessage() == null ? "Invalid status request" : invalid.getMessage());
            return true;
        }
    }

    @Override public PacketEnvelope pollInbound() {
        byte[] body = this.inboundFrames.poll();
        if (body == null) return null;
        try {
            DecodedPacket decoded = this.registry.decode(this.inboundDirection, state(), decodeTransportBody(body));
            return new PacketEnvelope(decoded.packet(), decoded.sequence());
        } catch (ProtocolException | RuntimeException e) {
            disconnect(DisconnectReason.INVALID_PACKET, e.getMessage() == null ? "Malformed packet" : e.getMessage());
            return null;
        }
    }
    @Override public int inboundSize() { return this.inboundFrames.size(); }
    @Override public int outboundSize() { return this.outboundCount.get() + this.pendingBatchPackets.get(); }
    /** Event-loop receive timestamp, so status RTT excludes owner-thread polling delay. */
    public long lastPacketReceivedNanos() { return this.lastPacketReceivedNanos; }
    @Override public boolean open() { return this.open.get() && this.channel.isOpen(); }
    @Override public TransportStats stats() {
        return new TransportStats(this.receivedPackets.get(), this.receivedBytes.get(),
                this.sentPackets.get(), this.sentBytes.get(), inboundSize(), outboundSize());
    }
    public BatchEncodingStats batchEncodingStats() {
        return new BatchEncodingStats(this.encodedBatches.get(), this.encodedBatchPackets.get(),
                this.batchEncodingNanos.get());
    }

    @Override
    public void disconnect(DisconnectReason reason, String message) {
        if (!this.open.compareAndSet(true, false)) return;
        ConnectionState current = state();
        if (current != ConnectionState.CLOSED && current != ConnectionState.DISCONNECTING) {
            try {
                byte[] body = this.registry.encode(this.outboundDirection, current,
                        new PacketEnvelope(new CorePackets.Disconnect(reason, safeMessage(message))));
                this.channel.writeAndFlush(Unpooled.wrappedBuffer(ProtocolFraming.frame(encodeTransportBody(body))))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            } catch (Exception ignored) { }
        }
        this.channel.close();
        this.state.close();
    }

    @Override public void close() { disconnect(DisconnectReason.CLIENT_QUIT, "Connection closed"); }

    @Override
    public void enableCompression(String algorithm, int threshold, int maximumDecompressedBytes, int level) {
        if (!algorithm.equals("zstd")) throw new IllegalArgumentException("Unsupported compression " + algorithm);
        if (!this.compression.equals("none")) throw new IllegalStateException("Compression already enabled");
        this.compression = algorithm;
        this.compressionThreshold = threshold;
        this.maximumDecompressedBytes = maximumDecompressedBytes;
        this.compressionLevel = level;
    }

    void channelClosed() {
        this.open.set(false);
        this.state.close();
        this.inboundFrames.clear();
        this.pendingBatches.clear();
        this.pendingBatchPackets.set(0);
        for (ArrayBlockingQueue<EncodedOutbound> queue : this.outbound.values()) queue.clear();
        this.outboundCount.set(0);
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "Connection closed";
        byte[] bytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= 4096) return message;
        return new String(bytes, 0, 4096, java.nio.charset.StandardCharsets.UTF_8);
    }

    private EncodedOutbound encode(PacketEnvelope envelope, ConnectionState state,
                                   CompressionSettings compression) throws ProtocolException {
        PacketType<?> type = this.registry.type(envelope.packet());
        byte[] body = this.registry.encode(this.outboundDirection, state, envelope);
        return new EncodedOutbound(type.channel(), type.delivery(), type.packetClass(), envelope.sequence(),
                ProtocolFraming.frame(encodeTransportBody(body, compression)));
    }

    private boolean enqueueEncoded(EncodedOutbound encoded) {
        ArrayBlockingQueue<EncodedOutbound> queue = this.outbound.get(encoded.channel());
        if (encoded.delivery() == DeliveryClass.UNRELIABLE_SEQUENCED
                && encoded.packetClass() != CorePackets.PlayerInput.class) {
            int before = queue.size();
            queue.removeIf(old -> old.packetClass() == encoded.packetClass()
                    && old.sequence() < encoded.sequence());
            this.outboundCount.addAndGet(queue.size() - before);
        }
        if (!queue.offer(encoded)) {
            if (encoded.delivery() == DeliveryClass.UNRELIABLE
                    || encoded.delivery() == DeliveryClass.UNRELIABLE_SEQUENCED) return false;
            disconnect(DisconnectReason.INTERNAL_ERROR, "Outbound queue overflow");
            return false;
        }
        this.outboundCount.incrementAndGet();
        return true;
    }

    private CompressionSettings compressionSettings() {
        return new CompressionSettings(this.compression, this.compressionThreshold,
                this.maximumDecompressedBytes, this.compressionLevel);
    }

    private void scheduleBatchDrain() {
        if (!this.batchDrainScheduled.compareAndSet(false, true)) return;
        try { this.batchEncoder.execute(this::drainBatches); }
        catch (RuntimeException e) {
            this.batchDrainScheduled.set(false);
            this.pendingBatches.clear();
            this.pendingBatchPackets.set(0);
            disconnect(DisconnectReason.INTERNAL_ERROR, "Chunk encoder unavailable");
        }
    }

    private void drainBatches() {
        try {
            PendingBatch batch;
            while (open() && (batch = this.pendingBatches.poll()) != null) {
                long started = System.nanoTime();
                try {
                    for (PacketEnvelope envelope : batch.packets()) {
                        if (envelope.packet() instanceof CorePackets.ChunkColumnData data) {
                            if (!enqueueChunkFragments(data, batch.state(), batch.compression())) return;
                        } else if (!enqueueEncoded(encode(envelope, batch.state(), batch.compression()))) return;
                    }
                } catch (ProtocolException | RuntimeException e) {
                    disconnect(DisconnectReason.INTERNAL_ERROR,
                            e.getMessage() == null ? "Chunk encoding failed" : e.getMessage());
                    return;
                } finally {
                    int size = batch.packets().size();
                    this.pendingBatchPackets.updateAndGet(value -> Math.max(0, value - size));
                    this.encodedBatches.incrementAndGet();
                    this.encodedBatchPackets.addAndGet(size);
                    this.batchEncodingNanos.addAndGet(System.nanoTime() - started);
                }
            }
        } finally {
            this.batchDrainScheduled.set(false);
            if (!this.pendingBatches.isEmpty() && open()) scheduleBatchDrain();
        }
    }

    private boolean enqueueChunkFragments(CorePackets.ChunkColumnData data, ConnectionState state,
                                          CompressionSettings compression) throws ProtocolException {
        byte[] payload = de.skyengine.shared.network.CoreProtocol.encodeChunkSnapshot(data.chunk());
        int fragmentBytes = CorePackets.ChunkColumnFragment.MAX_FRAGMENT_BYTES;
        int count = (payload.length + fragmentBytes - 1) / fragmentBytes;
        if (count < 1 || count > 256) throw new ProtocolException("Chunk requires too many fragments");
        for (int index = 0, offset = 0; index < count; index++) {
            int length = Math.min(fragmentBytes, payload.length - offset);
            byte[] part = java.util.Arrays.copyOfRange(payload, offset, offset + length);
            CorePackets.ChunkColumnFragment fragment = new CorePackets.ChunkColumnFragment(
                    data.batchId(), index, count, payload.length, part);
            if (!enqueueEncoded(encode(new PacketEnvelope(fragment), state, compression))) return false;
            offset += length;
        }
        return true;
    }

    private byte[] encodeTransportBody(byte[] body) throws ProtocolException {
        return encodeTransportBody(body, compressionSettings());
    }

    private static byte[] encodeTransportBody(byte[] body, CompressionSettings settings) throws ProtocolException {
        if (!settings.algorithm().equals("zstd")) return body;
        PacketBuffer output = new PacketBuffer(body.length + 16);
        if (body.length < settings.threshold()) {
            output.writeVarInt(0);
            output.writeRawBytes(body);
            return output.toByteArray();
        }
        byte[] compressed = Zstd.compress(body, settings.level());
        if (compressed.length >= body.length) {
            output.writeVarInt(0);
            output.writeRawBytes(body);
        } else {
            output.writeVarInt(body.length);
            output.writeRawBytes(compressed);
        }
        return output.toByteArray();
    }

    private byte[] decodeTransportBody(byte[] transportBody) throws ProtocolException {
        if (!this.compression.equals("zstd")) return transportBody;
        PacketBuffer input = PacketBuffer.wrap(transportBody);
        int decompressedLength = input.readVarInt();
        if (decompressedLength == 0) return input.readRawBytes(input.readableBytes());
        if (decompressedLength < this.compressionThreshold || decompressedLength > this.maximumDecompressedBytes) {
            throw new ProtocolException("Invalid compressed payload length " + decompressedLength);
        }
        byte[] compressed = input.readRawBytes(input.readableBytes());
        byte[] result = new byte[decompressedLength];
        long actual = Zstd.decompress(result, compressed);
        if (Zstd.isError(actual) || actual != decompressedLength) {
            throw new ProtocolException("Invalid Zstd payload");
        }
        return result;
    }

    public static final class Handler extends SimpleChannelInboundHandler<ByteBuf> {
        private NettyTransportConnection connection;

        public void connection(NettyTransportConnection connection) { this.connection = connection; }
        @Override protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
            this.connection.receive(message);
        }
        @Override public void channelInactive(ChannelHandlerContext context) {
            if (this.connection != null) this.connection.channelClosed();
        }
        @Override public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            if (this.connection != null) this.connection.disconnect(DisconnectReason.INVALID_PACKET,
                    cause.getMessage() == null ? "Network error" : cause.getMessage());
            else context.close();
        }
    }
}

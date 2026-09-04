package de.skyengine.shared.network;

public interface PacketCodec<P extends Packet> {
    P decode(PacketBuffer input) throws ProtocolException;
    void encode(PacketBuffer output, P packet) throws ProtocolException;

    static <P extends Packet> PacketCodec<P> of(Encoder<P> encoder, Decoder<P> decoder) {
        return new PacketCodec<>() {
            @Override public P decode(PacketBuffer input) throws ProtocolException { return decoder.decode(input); }
            @Override public void encode(PacketBuffer output, P packet) throws ProtocolException { encoder.encode(output, packet); }
        };
    }

    @FunctionalInterface interface Encoder<P> { void encode(PacketBuffer output, P packet) throws ProtocolException; }
    @FunctionalInterface interface Decoder<P> { P decode(PacketBuffer input) throws ProtocolException; }
}

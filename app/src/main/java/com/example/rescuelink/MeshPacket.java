package com.example.rescuelink;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class MeshPacket {
    public static final int HEADER_SIZE = 19;
    public static final byte TRANSPORT_NEARBY  = 0x01;
    public static final byte TRANSPORT_HOTSPOT = 0x02;
    public static final byte TRANSPORT_BOTH    = 0x03;
    public static final String BROADCAST_ID    = "FFFF";

    private static final java.util.concurrent.atomic.AtomicLong sequenceCounter
            = new java.util.concurrent.atomic.AtomicLong(0);

    public final char tag;
    public final String originId;
    public final String targetId;
    public final long sequence;
    public int ttl;
    public byte transport;
    public final byte[] payload;

    public MeshPacket(char tag, String originId, String targetId, long sequence, int ttl, byte transport, byte[] payload) {
        this.tag = tag;
        this.originId = originId;
        this.targetId = targetId;
        this.sequence = sequence;
        this.ttl = ttl;
        this.transport = transport;
        this.payload = payload;
    }

    public MeshPacket(char tag, String originId, String targetId, byte[] payload) {
        this.tag = tag;
        this.originId = originId;
        this.targetId = targetId;
        this.sequence = sequenceCounter.incrementAndGet();
        this.ttl = 6;
        this.transport = TRANSPORT_BOTH;
        this.payload = payload;
    }

    public static MeshPacket parse(byte[] data) {
        if (data == null || data.length < HEADER_SIZE) return null;
        ByteBuffer bb = ByteBuffer.wrap(data);
        char tag         = (char) bb.get();
        byte[] orig      = new byte[4]; bb.get(orig);
        byte[] targ      = new byte[4]; bb.get(targ);
        long seq         = bb.getLong();
        int ttl          = bb.get() & 0xFF;
        byte transport   = bb.get();
        byte[] payload   = new byte[data.length - HEADER_SIZE];
        bb.get(payload);
        return new MeshPacket(tag,
                new String(orig, StandardCharsets.US_ASCII),
                new String(targ, StandardCharsets.US_ASCII),
                seq, ttl, transport, payload);
    }

    public byte[] toBytesWithDecrementedTtlAndPayload(byte[] newPayload) {
        ByteBuffer bb = ByteBuffer.allocate(HEADER_SIZE + newPayload.length);
        bb.put((byte) tag);
        bb.put(padId(originId));
        bb.put(padId(targetId));
        bb.putLong(sequence);
        bb.put((byte) Math.max(0, ttl - 1)); // decremented TTL
        bb.put(transport);
        bb.put(newPayload);
        return bb.array();
    }

    public byte[] toBytes() {
        ByteBuffer bb = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        bb.put((byte) tag);
        bb.put(padId(originId));
        bb.put(padId(targetId));
        bb.putLong(sequence);
        bb.put((byte) ttl);
        bb.put(transport);
        bb.put(payload);
        return bb.array();
    }

    public byte[] toBytesWithDecrementedTtl() {
        byte[] data = toBytes();
        data[17] = (byte) Math.max(0, ttl - 1);
        return data;
    }

    public String deduplicationKey() {
        return originId + ":" + sequence;
    }

    public boolean isBroadcast() {
        return BROADCAST_ID.equals(targetId);
    }

    private static byte[] padId(String id) {
        byte[] b = new byte[4];
        byte[] src = id.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, b, 0, Math.min(src.length, 4));
        return b;
    }
}

package com.example.rescuelink;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class TransportBroker {

    private static final String TAG = "TransportBroker";

    public interface IncomingPacketHandler {
        // [MODIFIED]: Added originalEncryptedPayload to pass through to relay
        void handle(MeshPacket packet, String sourceEndpointId, byte[] originalEncryptedPayload);
    }

    private final NearbyMeshManager nearbyManager;
    private final HotspotMeshManager hotspotManager;
    private final PacketDeduplicator deduplicator;
    private final IncomingPacketHandler incomingHandler;
    private final EncryptionManager encryption;

    // ==========================================
    //      THE TRAFFIC COP (Bandwidth Gating)
    // ==========================================
    private volatile boolean isAudioActive = false;

    public void setAudioActive(boolean active) {
        this.isAudioActive = active;
        Log.d(TAG, "Traffic Cop: Audio active state = " + active);
    }

    public TransportBroker(
            NearbyMeshManager nearby,
            HotspotMeshManager hotspot,
            PacketDeduplicator deduplicator,
            IncomingPacketHandler handler
    ) {
        this.nearbyManager   = nearby;
        this.hotspotManager  = hotspot;
        this.deduplicator    = deduplicator;
        this.incomingHandler = handler;
        this.encryption      = new EncryptionManager();

        hotspot.setPacketListener((data, sourceIp) -> onPacketReceived(data, sourceIp));
        nearby.setPacketListener((data, endpointId) -> onPacketReceived(data, endpointId));
    }

    // ─── Incoming ────────────────────────────────────────────────────────

    private void onPacketReceived(byte[] data, String sourceId) {
        MeshPacket packet = MeshPacket.parse(data);
        if (packet == null) return;

        // Dedup before decryption — relay nodes dedup without decrypting
        if (!deduplicator.checkAndMark(packet)) return;

        // Decrypt payload
        byte[] decryptedPayload = encryption.decrypt(packet.payload);
        if (decryptedPayload == null) {
            if (packet.tag != 'A') {
                Log.d(TAG, "Dropped — decrypt failed tag:" + packet.tag
                        + " from:" + packet.originId);
            }
            return;
        }

        // Rebuild with decrypted payload — preserves all header fields
        MeshPacket decryptedPacket = new MeshPacket(
                packet.tag,
                packet.originId,
                packet.targetId,
                packet.sequence,
                packet.ttl,
                packet.transport,
                decryptedPayload);

        // [MODIFIED]: Pass the original encrypted packet.payload through
        incomingHandler.handle(decryptedPacket, sourceId, packet.payload);
    }

    // ─── Outgoing ────────────────────────────────────────────────────────

    public void send(MeshPacket packet) {
        // Pre-gate check: If audio is active, don't even bother encrypting GPS/Status packets. Save CPU!
        if (isAudioActive && (packet.tag == 'S' || packet.tag == 'L')) {
            return;
        }

        byte[] encryptedPayload;
        try {
            if (packet.tag == 'A') {
                // FAST PATH: audio uses counter IV — no SecureRandom blocking
                encryptedPayload = encryption.encryptWithSequence(
                        packet.payload, packet.sequence);
            } else {
                // SAFE PATH: all other packet types use SecureRandom
                encryptedPayload = encryption.encrypt(packet.payload);
            }
        } catch (Exception e) {
            Log.e(TAG, "Encryption failed: " + e.getMessage());
            return;
        }

        // Build wire bytes ONCE — fixed double allocation bug
        byte[] data = buildWireBytes(packet, encryptedPayload, packet.ttl);

        switch (packet.tag) {
            case 'A': sendAudio(data);     break;
            case 'E': sendEmergency(data); break;
            case 'M': sendMessage(data);   break;
            case 'S': sendStatus(data);    break;
            case 'C': sendControl(data);   break;
            case 'L': sendLocation(data);  break;
            default:  sendMessage(data);
        }
    }

    // ─── Relay ───────────────────────────────────────────────────────────

    // [MODIFIED]: Added originalEncryptedData parameter and removed re-encryption try/catch block
    public void relay(MeshPacket decryptedPacket, String receivedFromId, boolean isForMe, byte[] originalEncryptedData) {
        if (decryptedPacket.ttl <= 0) return;

        // Build relay bytes with the original encrypted payload, just decrement TTL
        byte[] relayData = buildWireBytes(
                decryptedPacket,
                originalEncryptedData,
                decryptedPacket.ttl - 1);

        if (decryptedPacket.tag == 'A') {
            // [BUG-3 FIX]: Audio relay must mirror sendAudio() — prefer hotspot when connected;
            // only fall back to Nearby otherwise to prevent doubling bandwidth at 50 fps.
            boolean sent = hotspotManager.isConnected() && hotspotManager.send(relayData);
            if (!sent) nearbyManager.sendToAllExcept(receivedFromId, relayData);

        } else if (decryptedPacket.isBroadcast()
                || decryptedPacket.tag == 'C'
                || decryptedPacket.tag == 'L') {
            // Non-audio broadcasts still flood all transports (low rate — fine)
            nearbyManager.sendToAllExcept(receivedFromId, relayData);
            hotspotManager.send(relayData);

        } else if (!isForMe) {
            // Unicast: try routing table first
            String nextHop = nearbyManager.getNextHop(decryptedPacket.targetId);
            if (nextHop != null) {
                nearbyManager.sendTo(nextHop, relayData);
            } else {
                nearbyManager.sendToAllExcept(receivedFromId, relayData);
                hotspotManager.send(relayData);
            }
        }
    }

    // ─── Wire bytes builder ───────────────────────────────────────────────

    private byte[] buildWireBytes(MeshPacket packet, byte[] encryptedPayload, int ttl) {
        ByteBuffer bb = ByteBuffer.allocate(MeshPacket.HEADER_SIZE + encryptedPayload.length);
        bb.put((byte) packet.tag);
        bb.put(padId(packet.originId));
        bb.put(padId(packet.targetId));
        bb.putLong(packet.sequence);
        bb.put((byte) Math.max(0, ttl));
        bb.put(packet.transport);
        bb.put(encryptedPayload);
        return bb.array();
    }

    private byte[] padId(String id) {
        byte[] b   = new byte[4];
        byte[] src = id.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, b, 0, Math.min(src.length, 4));
        return b;
    }

    // ─── Send routing ─────────────────────────────────────────────────────

    private void sendAudio(byte[] data) {
        boolean sent = hotspotManager.isConnected() && hotspotManager.send(data);
        if (!sent) nearbyManager.sendToAll(data);
    }

    private void sendEmergency(byte[] data) {
        nearbyManager.sendToAll(data);
        hotspotManager.send(data);
    }

    private void sendMessage(byte[] data) {
        boolean sent = hotspotManager.isConnected() && hotspotManager.send(data);
        if (!sent) nearbyManager.sendToAll(data);
    }

    private void sendStatus(byte[] data) {
        if (isAudioActive) return;
        nearbyManager.sendToAll(data);
        if (hotspotManager.isConnected()) hotspotManager.send(data);
    }

    private void sendControl(byte[] data) {
        nearbyManager.sendToAll(data);
        if (hotspotManager.isConnected()) hotspotManager.send(data);
    }

    private void sendLocation(byte[] data) {
        if (isAudioActive) return;
        nearbyManager.sendToAll(data);
        if (hotspotManager.isConnected()) hotspotManager.send(data);
    }
}
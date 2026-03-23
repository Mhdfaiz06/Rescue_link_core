package com.example.rescuelink;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class TransportBroker {

    private static final String TAG = "TransportBroker";

    public interface IncomingPacketHandler {
        void handle(MeshPacket packet, String sourceEndpointId);
    }

    private final NearbyMeshManager nearbyManager;
    private final HotspotMeshManager hotspotManager;
    private final PacketDeduplicator deduplicator;
    private final IncomingPacketHandler incomingHandler;
    private final EncryptionManager encryption;

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
            // Only log for non-audio to avoid logcat flooding
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

        incomingHandler.handle(decryptedPacket, sourceId);
    }

    // ─── Outgoing ────────────────────────────────────────────────────────

    public void send(MeshPacket packet) {
        byte[] encryptedPayload;
        try {
            if (packet.tag == 'A') {
                // FAST PATH: audio uses counter IV — no SecureRandom blocking
                // sequence is unique per packet (AtomicLong in MeshPacket)
                encryptedPayload = encryption.encryptWithSequence(
                        packet.payload, packet.sequence);
            } else {
                // SAFE PATH: all other packet types use SecureRandom
                // Called at low frequency so blocking is not a problem
                encryptedPayload = encryption.encrypt(packet.payload);
            }
        } catch (Exception e) {
            Log.e(TAG, "Encryption failed: " + e.getMessage());
            return;
        }

        // Build wire bytes ONCE — fixed double allocation bug from previous version
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

    public void relay(MeshPacket decryptedPacket, String receivedFromId, boolean isForMe) {
        if (decryptedPacket.ttl <= 0) return;

        // Re-encrypt for relay hop
        byte[] reEncryptedPayload;
        try {
            if (decryptedPacket.tag == 'A') {
                // Fast path for audio — counter IV, no SecureRandom
                reEncryptedPayload = encryption.encryptWithSequence(
                        decryptedPacket.payload, decryptedPacket.sequence);
            } else {
                reEncryptedPayload = encryption.encrypt(decryptedPacket.payload);
            }
        } catch (Exception e) {
            Log.e(TAG, "Re-encrypt failed on relay: " + e.getMessage());
            return;
        }

        // Build relay bytes with decremented TTL — single allocation
        byte[] relayData = buildWireBytes(
                decryptedPacket, reEncryptedPayload, decryptedPacket.ttl - 1);

        if (decryptedPacket.isBroadcast()
                || decryptedPacket.tag == 'A'
                || decryptedPacket.tag == 'C'
                || decryptedPacket.tag == 'L') {
            nearbyManager.sendToAllExcept(receivedFromId, relayData);
            hotspotManager.send(relayData);
        } else if (!isForMe) {
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
    // Single method — no more double allocation
    // ttl passed explicitly so relay can decrement without mutating the packet

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
        nearbyManager.sendToAll(data);
        if (hotspotManager.isConnected()) hotspotManager.send(data);
    }

    private void sendControl(byte[] data) {
        nearbyManager.sendToAll(data);
        if (hotspotManager.isConnected()) hotspotManager.send(data);
    }

    private void sendLocation(byte[] data) {
        nearbyManager.sendToAll(data);
        if (hotspotManager.isConnected()) hotspotManager.send(data);
    }
}
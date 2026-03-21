package com.example.rescuelink;

public class TransportBroker {

    public interface IncomingPacketHandler {
        void handle(MeshPacket packet, String sourceEndpointId);
    }

    private final NearbyMeshManager nearbyManager;
    private final HotspotMeshManager hotspotManager;
    private final PacketDeduplicator deduplicator;
    private final IncomingPacketHandler incomingHandler;

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

        hotspot.setPacketListener((data, sourceIp) -> onPacketReceived(data, sourceIp));
        nearby.setPacketListener((data, endpointId) -> onPacketReceived(data, endpointId));
    }

    private void onPacketReceived(byte[] data, String sourceId) {
        MeshPacket packet = MeshPacket.parse(data);
        if (packet == null) return;
        if (!deduplicator.checkAndMark(packet)) return;
        incomingHandler.handle(packet, sourceId);
    }

    public void send(MeshPacket packet) {
        byte[] data = packet.toBytes();
        switch (packet.tag) {
            case 'A': sendAudio(data); break;
            case 'E': sendEmergency(data); break;
            case 'M': sendMessage(data); break;
            case 'S': sendStatus(data); break;
            default:  sendMessage(data);
        }
    }

    private void sendAudio(byte[] data) {
        boolean hotspotSent = hotspotManager.isConnected() && hotspotManager.send(data);
        if (!hotspotSent) nearbyManager.sendToAll(data);
    }

    private void sendEmergency(byte[] data) {
        nearbyManager.sendToAll(data);
        hotspotManager.send(data);
    }

    private void sendMessage(byte[] data) {
        boolean hotspotSent = hotspotManager.isConnected() && hotspotManager.send(data);
        if (!hotspotSent) nearbyManager.sendToAll(data);
    }

    private void sendStatus(byte[] data) {
        nearbyManager.sendToAll(data);
        if (hotspotManager.isConnected()) hotspotManager.send(data);
    }

    public void relay(MeshPacket packet, String receivedFromId, boolean isForMe) {
        if (packet.ttl <= 0) return;
        byte[] relayData = packet.toBytesWithDecrementedTtl();

        if (packet.isBroadcast() || packet.tag == 'A') {
            nearbyManager.sendToAllExcept(receivedFromId, relayData);
            hotspotManager.send(relayData);
        } else if (!isForMe) {
            String nextHop = nearbyManager.getNextHop(packet.targetId);
            if (nextHop != null) {
                nearbyManager.sendTo(nextHop, relayData);
            } else {
                nearbyManager.sendToAllExcept(receivedFromId, relayData);
                hotspotManager.send(relayData);
            }
        }
    }
}

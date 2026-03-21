package com.example.rescuelink;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class PacketDeduplicator {
    // LRU cache — auto-evicts oldest when it exceeds 500 entries
    private final Set<String> seen = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 500;
                }
            })
    );

    // Returns true if this is a NEW packet (not seen before)
    public boolean checkAndMark(String key) {
        synchronized (seen) {
            if (seen.contains(key)) return false;
            seen.add(key);
            return true;
        }
    }

    public boolean checkAndMark(MeshPacket packet) {
        return checkAndMark(packet.deduplicationKey());
    }
}

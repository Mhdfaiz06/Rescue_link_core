package com.example.rescuelink;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class PacketDeduplicator {

    // [CHANGE APPLIED]: General packet dedup — 500 entries is fine for text/GPS
    private final Set<String> seen = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 500;
                }
            })
    );

    // [CHANGE APPLIED]: Audio-specific dedup — 2000 entries (50fps × ~40s window)
    private final Set<String> audioSeen = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(2048, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 2000;
                }
            })
    );

    public boolean checkAndMark(MeshPacket packet) {
        String key = packet.deduplicationKey();

        if (packet.tag == 'A') {
            synchronized (audioSeen) {
                if (audioSeen.contains(key)) return false;
                audioSeen.add(key);
                return true;
            }
        }

        // Regular packets use the smaller cache
        synchronized (seen) {
            if (seen.contains(key)) return false;
            seen.add(key);
            return true;
        }
    }
}
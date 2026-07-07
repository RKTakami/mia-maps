package com.mia.aperture.map;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MapTileCache {
    private final LinkedHashMap<TileKey, MapTile> map;

    public MapTileCache(int capacity) {
        this.map = new LinkedHashMap<>(capacity * 4 / 3, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<TileKey, MapTile> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized MapTile get(TileKey key) {
        return this.map.get(key);
    }

    public synchronized void put(TileKey key, MapTile tile) {
        this.map.put(key, tile);
    }

    public synchronized void clear() {
        this.map.clear();
    }
}

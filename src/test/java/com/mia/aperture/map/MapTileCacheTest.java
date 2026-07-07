package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapTileCacheTest {

    private static TileKey key(int sx) {
        return new TileKey(0, sx, 0, 0, MapMode.RELIEF);
    }

    @Test
    void storesAndRetrieves() {
        MapTileCache cache = new MapTileCache(4);
        MapTile tile = new MapTile(new int[1024], new int[1024], 123L);
        cache.put(key(1), tile);
        assertSame(tile, cache.get(key(1)));
        assertNull(cache.get(key(2)));
    }

    @Test
    void evictsLeastRecentlyUsed() {
        MapTileCache cache = new MapTileCache(2);
        cache.put(key(1), new MapTile(new int[0], new int[0], 0));
        cache.put(key(2), new MapTile(new int[0], new int[0], 0));
        cache.get(key(1));
        cache.put(key(3), new MapTile(new int[0], new int[0], 0));
        assertNotNull(cache.get(key(1)));
        assertNull(cache.get(key(2)));
        assertNotNull(cache.get(key(3)));
    }

    @Test
    void clearEmptiesEverything() {
        MapTileCache cache = new MapTileCache(4);
        cache.put(key(1), new MapTile(new int[0], new int[0], 0));
        cache.clear();
        assertNull(cache.get(key(1)));
    }
}

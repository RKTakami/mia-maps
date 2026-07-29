package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapTileCacheTest {

    private static TileKey key(int sx) {
        return new TileKey(0, sx, 0, 0, MapMode.RELIEF, 0);
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

    @Test
    void tilesFromAReplacedEngineAreNotReused() {
        // Voxy shuts the world down when it looks idle and builds a fresh engine on the next
        // access — about 25 times in one evening. Everything derived from a section belongs to the
        // engine that produced it, so the generation is part of the key. Without it the camera has
        // not moved, the key matches, and the map keeps drawing terrain from an engine that is gone.
        MapTileCache cache = new MapTileCache(16);
        MapTile old = new MapTile(new int[1024], new int[1024], 1L);
        TileKey before = new TileKey(0, 5, 0, 0, MapMode.RELIEF, 0);
        TileKey after = new TileKey(0, 5, 0, 0, MapMode.RELIEF, 1);
        cache.put(before, old);
        assertSame(old, cache.get(before));
        assertNull(cache.get(after), "a tile from the previous engine must not satisfy the new one");
    }
}

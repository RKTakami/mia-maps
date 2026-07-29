package com.mia.aperture.map;

// `gen` is MapEngineSource.generation(): a tile belongs to the engine that produced it, so when the
// engine is replaced the old keys stop matching and the LRU evicts them. Cheaper and less racy than
// clearing the cache from whichever thread noticed the swap.
public record TileKey(int lvl, int sx, int sz, int bandKey, int refKey, MapMode mode, int gen) {
}

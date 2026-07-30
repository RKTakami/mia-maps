package com.mia.aperture.map;

// `gen` is MapEngineSource.generation(): a tile belongs to the engine that produced it, so when the
// engine is replaced the old keys stop matching and the LRU evicts them. Cheaper and less racy than
// clearing the cache from whichever thread noticed the swap.
// `fromStore` is part of the key because the two sources number their cells differently: store ids
// are interned block-state strings, Voxy ids come from its own mapper. A tile cached from one and
// read as the other miscolours everything while still looking like a map, so they must never share
// a cache entry.
public record TileKey(int lvl, int sx, int sz, int bandKey, int refKey, MapMode mode, int gen,
                      boolean fromStore) {
}

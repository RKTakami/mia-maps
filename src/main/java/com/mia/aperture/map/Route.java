package com.mia.aperture.map;

import java.util.List;

// A computed route in WORLD coords (shifted X/Y already un-shifted). Bridges empty in Phase 1.
public record Route(List<double[]> points, List<double[][]> bridges, Pathfinder.Status status) {
    public static final Route EMPTY = new Route(List.of(), List.of(), Pathfinder.Status.NO_ROUTE);
}

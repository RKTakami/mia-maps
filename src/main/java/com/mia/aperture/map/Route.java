package com.mia.aperture.map;

import java.util.List;

// A computed route in WORLD coords (shifted X/Y already un-shifted). Bridges empty in Phase 1.
// `dig` is non-null only when Plan B recommended a dig/tunnel leg for descent.
public record Route(List<double[]> points, List<double[][]> bridges,
                    DigPlan dig, Pathfinder.Status status) {
    // entry = shaft mouth (world center); cells = ordered blocks to mine (world centers).
    public record DigPlan(double[] entry, List<double[]> cells) {}

    public static final Route EMPTY =
        new Route(List.of(), List.of(), null, Pathfinder.Status.NO_ROUTE);
}

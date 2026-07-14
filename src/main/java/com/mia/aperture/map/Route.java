package com.mia.aperture.map;

import java.util.List;

// A computed route in WORLD coords (shifted X/Y already un-shifted). Bridges empty in Phase 1.
// `dig` is non-null only when Plan B recommended a dig/tunnel leg for descent.
public record Route(List<double[]> points, List<double[][]> bridges,
                    DigPlan dig, Pathfinder.Status status) {
    // entry = shaft mouth (world center); cells = ordered blocks to mine (world centers).
    public record DigPlan(double[] entry, List<double[]> cells) {}

    // Breadcrumbs from the point nearest (x,y,z) onward — the trail still ahead of the player.
    // Passed markers drop off as you advance; they reappear if you walk back (they lead to the goal).
    public List<double[]> ahead(double x, double y, double z) {
        if (points.isEmpty()) return points;
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i);
            double dx = p[0] - x, dy = p[1] - y, dz = p[2] - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= best) { best = d; nearest = i; }
        }
        return points.subList(nearest, points.size());
    }

    public static final Route EMPTY =
        new Route(List.of(), List.of(), null, Pathfinder.Status.NO_ROUTE);
}

package com.mia.aperture.map;

// Places RouteService's search box in the Abyss's shifted column: centred on the player, biased
// toward the goal, but always keeping the player `margin` blocks clear of the edge so a partial
// route still starts under their feet.
//
// Pure on purpose. These rules used to live inside RouteService.compute, which needs Voxy and
// Minecraft to run and therefore could not be tested at all — which is how it shipped taking its
// bias from a WORLD-space delta while feeding a SHIFTED-space grid.
public final class RouteBox {
    // Grid origin (shifted column) and extents, in cells at LOD 0 (1 cell = 1 block).
    public record Box(int originX, int originY, int originZ, int gx, int gy, int gz) {}

    private RouteBox() {}

    // px/py/pz: player, tx/ty/tz: target — both in the shifted column.
    public static Box place(double px, double py, double pz,
                            double tx, double ty, double tz,
                            int box, int vbox, int margin) {
        double dx = tx - px, dy = ty - py, dz = tz - pz;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double bias = Math.min(horiz * 0.5, box / 2.0 - margin);
        double ux = horiz > 1e-6 ? dx / horiz : 0, uz = horiz > 1e-6 ? dz / horiz : 0;
        double bcx = px + ux * bias, bcz = pz + uz * bias;
        double bcy = py + Math.max(-(vbox - margin), Math.min(vbox - margin, dy * 0.5));
        int gx = box, gy = 2 * vbox, gz = box;
        return new Box((int) Math.floor(bcx) - gx / 2,
                       (int) Math.floor(bcy) - gy / 2,
                       (int) Math.floor(bcz) - gz / 2,
                       gx, gy, gz);
    }
}

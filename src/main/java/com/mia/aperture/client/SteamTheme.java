package com.mia.aperture.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Victorian steam-era chrome: solid brass bezels with radiused corners, round domed rivets, slotted
 * screws and decorative gearing.
 *
 * <p>Defined once here rather than scattered through the screens, so the look stays consistent and a
 * change of palette is a change in one file. Drawn entirely from filled rectangles — no textures, so
 * there is no asset to ship and nothing for a resource pack to override.
 *
 * <p><b>Curves from rectangles.</b> Everything round here is built by span filling: for each row,
 * work out how far in the boundary sits and fill one rectangle. A radius-8 corner is eight
 * rectangles rather than 256 pixel tests, which matters because the minimap redraws every frame.
 *
 * <p><b>What makes it read as metal.</b> A single outline reads as a line; what reads as a raised
 * object is a lit one. Bezels are solid rings of brass built from concentric bands, graduated from
 * highlight to shadow, with one consistent light source at the top-left. Reversing the gradient reads
 * as sunken, which is what the recessed readouts use.
 *
 * <p><b>Legibility comes first.</b> Every panel lays a dark, mostly-opaque backing under its border
 * before anything else, because the status text on top has to stay readable over whatever the map is
 * drawing. Ornament that made the numbers harder to read would be a bad trade.
 */
public final class SteamTheme {
    private SteamTheme() {}

    // Aged brass, light to dark. Enough tones for a thick bezel to graduate across its width instead
    // of banding visibly.
    public static final int BRASS_HI = 0xFFFBEBBC;
    public static final int BRASS_LIGHT = 0xFFE8C87A;
    public static final int BRASS = 0xFFC49A48;
    public static final int BRASS_MID = 0xFF9C7734;
    public static final int BRASS_DARK = 0xFF6B4E20;
    public static final int BRASS_SHADOW = 0xFF3A2910;

    /**
     * Copper, for ornament set against the brass structure. Two metals is the Victorian idiom — the
     * case is brass and the foliage laid over it is copper — and it also does real work here: it
     * separates ornament from chrome, so a leaf reads as applied decoration rather than as part of
     * the frame it lies on. Kept at the same value steps as the brass so the two shade together.
     */
    public static final int COPPER_HI = 0xFFFFD9B0;
    public static final int COPPER_LIGHT = 0xFFE79A62;
    public static final int COPPER = 0xFFB86A34;
    public static final int COPPER_MID = 0xFF8E4C24;
    public static final int COPPER_DARK = 0xFF5E3016;
    public static final int COPPER_SHADOW = 0xFF33190B;

    /**
     * Drop shadow, cast by ornament onto whatever it lies over.
     *
     * <p>One offset for the whole interface, down and to the right, because the bevels are already lit
     * from the top left and a shadow that disagreed with them would read as two light sources. Two
     * strengths so the shadow has a soft edge rather than a hard black outline.
     */
    public static final int SHADOW = 0x66000000;
    public static final int SHADOW_SOFT = 0x30000000;
    public static final int SHADOW_DX = 3, SHADOW_DY = 3;

    /** The drop shadow for a round element, drawn before it. */
    public static void discShadow(GuiGraphics g, int cx, int cy, int r) {
        disc(g, cx + SHADOW_DX, cy + SHADOW_DY, r + 2, SHADOW_SOFT);
        disc(g, cx + SHADOW_DX, cy + SHADOW_DY, r, SHADOW);
    }

    /**
     * A cardinal-point stud: a copper lozenge with a lit face and a drop shadow, set into the bezel
     * behind a compass letter.
     */
    public static void cardinalStud(GuiGraphics g, int cx, int cy, int r, boolean north) {
        int face = north ? COPPER_LIGHT : COPPER;
        int hi = north ? COPPER_HI : COPPER_LIGHT;
        // Shadow first, as a lozenge matching the stud rather than a disc, so the cast shape agrees.
        lozenge(g, cx + SHADOW_DX, cy + SHADOW_DY, r + 1, SHADOW_SOFT);
        lozenge(g, cx + SHADOW_DX, cy + SHADOW_DY, r, SHADOW);
        lozenge(g, cx, cy, r + 1, COPPER_SHADOW);
        lozenge(g, cx, cy, r, COPPER_DARK);
        // The face, stepped toward the light so it domes instead of sitting flat.
        lozenge(g, cx, cy, r - 1, face);
        lozenge(g, cx - 1, cy - 1, r - 2, hi);
        g.fill(cx - 1, cy - r + 1, cx, cy - r + 2, COPPER_HI);
    }

    /** A filled diamond, drawn one row at a time. */
    private static void lozenge(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = r - Math.abs(dy);
            if (half < 0) continue;
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    /** Verdigris, for accents so the brass does not look freshly polished. */
    public static final int PATINA = 0xFF4E7A6A;
    public static final int PANEL = 0xEE1A1410;
    public static final int PANEL_INSET = 0xEE241C16;
    public static final int INK = 0xFFF2E2C0;
    public static final int INK_DIM = 0xFFB49A6E;

    /** Brass from outside in: highlight, body, shadow. Index into this to graduate a bezel. */
    private static final int[] BAND = {BRASS_DARK, BRASS_LIGHT, BRASS_HI, BRASS, BRASS_MID, BRASS_DARK};

    // ---- rounded geometry ----------------------------------------------------------------------

    /** How far in the boundary sits on a given row of a rounded rectangle. */
    private static int inset(int row, int h, int r) {
        if (r <= 0) return 0;
        double d;
        if (row < r) d = r - row - 0.5;
        else if (row >= h - r) d = row - (h - r) + 0.5;
        else return 0;
        return r - (int) Math.floor(Math.sqrt(Math.max(0, (double) r * r - d * d)));
    }

    /** A filled rectangle with radiused corners. */
    public static void roundRect(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        r = Math.min(r, Math.min(w, h) / 2);
        for (int row = 0; row < h; row++) {
            int in = inset(row, h, r);
            g.fill(x + in, y + row, x + w - in, y + row + 1, color);
        }
    }

    /**
     * A ring: the band between a rounded rectangle and the same rectangle inset by {@code thickness}.
     *
     * <p>Drawn as the difference between the two boundaries per row, which keeps the ring continuous
     * even where the corner inset jumps by more than one pixel — an outline drawn as single pixels
     * develops gaps exactly there.
     *
     * @param top colour for rows above the middle, {@code bottom} for rows below: that split is the
     *            light source, and it is what makes the band look like a moulding rather than a box
     */
    public static void roundRing(GuiGraphics g, int x, int y, int w, int h, int r, int thickness,
                                 int top, int bottom) {
        r = Math.min(r, Math.min(w, h) / 2);
        int ir = Math.max(0, r - thickness);
        for (int row = 0; row < h; row++) {
            int color = row < h / 2 ? top : bottom;
            int out = inset(row, h, r);
            boolean insideBand = row < thickness || row >= h - thickness;
            if (insideBand) {
                g.fill(x + out, y + row, x + w - out, y + row + 1, color);
                continue;
            }
            int in = thickness + inset(row - thickness, h - thickness * 2, ir);
            g.fill(x + out, y + row, x + in, y + row + 1, color);
            g.fill(x + w - in, y + row, x + w - out, y + row + 1, color);
        }
    }

    /**
     * A solid brass bezel with radiused corners, graduating from highlight to shadow across its
     * width. This is the real frame — a ring of metal, not an outline.
     */
    public static void bezel(GuiGraphics g, int x, int y, int w, int h, int thickness, int r) {
        for (int i = 0; i < thickness; i++) {
            int lit = BAND[Math.min(i, BAND.length - 1)];
            int shade = BAND[Math.min(BAND.length - 1, BAND.length - 1 - i)];
            roundRing(g, x + i, y + i, w - i * 2, h - i * 2, Math.max(0, r - i), 1, lit, shade);
        }
    }

    /** The reverse: a sunken rim, so whatever sits inside looks inlaid rather than laid on top. */
    public static void sunken(GuiGraphics g, int x, int y, int w, int h, int r, int thickness) {
        for (int i = 0; i < thickness; i++) {
            roundRing(g, x + i, y + i, w - i * 2, h - i * 2, Math.max(0, r - i), 1,
                    BRASS_SHADOW, BRASS_HI);
        }
    }

    /**
     * Fill the area outside one corner's radius, cutting a square corner back.
     *
     * <p>Needed as well as a rounded bezel, not instead of it: the bezel rounds the metal, this rounds
     * what the metal frames. Cutting only one of the two leaves a mismatch that reads as square.
     *
     * @param sx,sy which corner, as signs from the given point
     */
    public static void roundCorner(GuiGraphics g, int cx, int cy, int r, int sx, int sy, int color) {
        for (int i = 0; i < r; i++) {
            double d = r - i - 0.5;
            int keep = (int) Math.floor(Math.sqrt(Math.max(0, (double) r * r - d * d)));
            int cut = r - keep;
            if (cut <= 0) continue;
            int yy = sy > 0 ? cy + i : cy - i - 1;
            int x0 = sx > 0 ? cx : cx - cut;
            g.fill(x0, yy, x0 + cut, yy + 1, color);
        }
    }

    // ---- fasteners, actually round -------------------------------------------------------------

    /** A filled circle, span per row. */
    public static void disc(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy < r; dy++) {
            double yy = dy + 0.5;
            int half = (int) Math.floor(Math.sqrt(Math.max(0, (double) r * r - yy * yy)));
            if (half <= 0) continue;
            g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
        }
    }

    /**
     * A round domed rivet: shadow seat, brass dome, and a highlight offset up and left where the
     * light catches it. The offset is what makes it read as domed rather than as a flat disc.
     */
    public static void rivet(GuiGraphics g, int cx, int cy) {
        disc(g, cx, cy, 3, BRASS_SHADOW);
        disc(g, cx, cy, 2, BRASS_MID);
        disc(g, cx, cy, 2, BRASS);
        g.fill(cx - 1, cy - 1, cx + 1, cy + 1, BRASS_LIGHT);
        g.fill(cx - 1, cy - 1, cx, cy, BRASS_HI);
    }

    /** A round slotted screw. The slot is what distinguishes it from a rivet at this size. */
    public static void screw(GuiGraphics g, int cx, int cy) {
        disc(g, cx, cy, 4, BRASS_SHADOW);
        disc(g, cx, cy, 3, BRASS);
        g.fill(cx - 2, cy - 3, cx, cy + 2, BRASS_LIGHT);
        g.fill(cx - 3, cy, cx + 3, cy + 1, BRASS_SHADOW);
    }

    /**
     * A large brass screw head, shaded across its face.
     *
     * <p>Detailed rather than a flat disc with a line through it: concentric discs stepped toward the
     * light give the head a domed face, the slot is cut with its own shadow and lit lower lip so it
     * reads as a groove with depth, and a single specular point sits where a turned brass face would
     * catch a lamp. At this size a flat disc looks like a sticker.
     */
    public static void bigScrew(GuiGraphics g, int cx, int cy, int r) {
        disc(g, cx, cy, r + 1, BRASS_SHADOW);            // seat
        disc(g, cx, cy, r, BRASS_DARK);                  // rim
        // Dome: each smaller disc steps up-left, so the lit side crowds toward the light.
        int[] tones = {BRASS_MID, BRASS, BRASS_LIGHT, BRASS_HI};
        for (int i = 0; i < tones.length; i++) {
            int rr = r - 1 - i;
            if (rr < 1) break;
            disc(g, cx - i / 2, cy - i / 2, rr, tones[i]);
        }
        // Slot, with a shadowed floor and a lit lower lip.
        int sw = r - 1, sh = Math.max(2, r / 3);
        g.fill(cx - sw, cy - sh / 2, cx + sw, cy + sh / 2, BRASS_SHADOW);
        g.fill(cx - sw, cy + sh / 2 - 1, cx + sw, cy + sh / 2, BRASS_LIGHT);
        // Specular.
        g.fill(cx - r / 2, cy - r / 2 - 1, cx - r / 2 + 2, cy - r / 2 + 1, 0xFFFFF8E4);
    }

    /** Evenly spaced rivets along a run, inset from both ends. */
    public static void rivetRun(GuiGraphics g, int x0, int y0, int x1, int y1, int spacing) {
        int dx = x1 - x0, dy = y1 - y0;
        int len = Math.max(Math.abs(dx), Math.abs(dy));
        if (len < spacing * 2) return;
        int n = len / spacing;
        for (int i = 1; i < n; i++) rivet(g, x0 + dx * i / n, y0 + dy * i / n);
    }

    // ---- ornament ------------------------------------------------------------------------------

    /** An annulus, drawn one row at a time — the same span trick as {@link #disc}. */
    private static void ring(GuiGraphics g, int cx, int cy, int rOut, int rIn, int color) {
        // A band with no width, or an inner radius that has overtaken the outer one, draws nothing.
        // Without this the small end of the wagon wheel — where the felloe and tyre minimums eat the
        // whole radius — asks for inverted rectangles.
        if (rOut <= 0 || rIn >= rOut) return;
        for (int dy = -rOut; dy <= rOut; dy++) {
            int xo = (int) Math.round(Math.sqrt(Math.max(0, rOut * rOut - dy * dy)));
            int xi = Math.abs(dy) <= rIn
                    ? Math.min(xo, (int) Math.round(Math.sqrt(Math.max(0, rIn * rIn - dy * dy)))) : 0;
            if (xi == 0) {
                g.fill(cx - xo, cy + dy, cx + xo + 1, cy + dy + 1, color);
            } else {
                g.fill(cx - xo, cy + dy, cx - xi, cy + dy + 1, color);
                g.fill(cx + xi + 1, cy + dy, cx + xo + 1, cy + dy + 1, color);
            }
        }
    }

    /**
     * One gear tooth: a trapezoid standing off the rim, narrowing toward its tip.
     *
     * <p>Drawn as filled arc segments stepping outward rather than as a blob at the pitch radius. The
     * blob version reads as a serrated edge — it is the taper and the flank shading that make the eye
     * see separate teeth with gaps between them, which is what a gear actually is.
     */
    private static void tooth(GuiGraphics g, int cx, int cy, double a, double rIn, double rOut,
                              double halfWidth, boolean reverse) {
        double ca = Math.cos(a), sa = Math.sin(a);
        // Square stamps walking out along the tooth, each as wide as the tooth is at that radius. The
        // first version tested every pixel of the tooth's area — around seventy fills per tooth, which
        // at 28 teeth on two gears in two clusters was several thousand quads a frame just for
        // gearing. A stamp per two pixels of height covers the same shape in about five.
        int steps = Math.max(2, (int) Math.ceil((rOut - rIn) / 2.0));
        for (int i = 0; i <= steps; i++) {
            double f = i / (double) steps;
            double rr = rIn + (rOut - rIn) * f;
            int w = Math.max(1, (int) Math.round(halfWidth * (1 - 0.38 * f) * rr * 2));
            int px = cx + (int) Math.round(ca * rr) - w / 2;
            int py = cy + (int) Math.round(sa * rr) - w / 2;
            g.fill(px, py, px + w, py + w, f > 0.55 ? BRASS_LIGHT : BRASS);
            // A lit flank and a shaded one, swapping with the direction of travel, so a tooth has a
            // leading and a trailing edge rather than being a uniform stub.
            int e = Math.max(1, w / 3);
            g.fill(px, py, px + e, py + e, reverse ? BRASS_SHADOW : BRASS_HI);
            g.fill(px + w - e, py + w - e, px + w, py + w, reverse ? BRASS_HI : BRASS_SHADOW);
        }
    }

    /**
     * A decorative gear, turning slowly. Ornament rather than a status indicator — {@link SteamGear}
     * is the one that means "work is happening", and these must turn slowly enough not to be mistaken
     * for it.
     */
    public static void ornamentGear(GuiGraphics g, int cx, int cy, int r, boolean reverse) {
        discShadow(g, cx, cy, r + 2);
        double phase = (System.currentTimeMillis() % 24000) / 24000.0 * Math.PI * 2.0;
        if (reverse) phase = -phase;
        // Tooth count scales with circumference rather than radius, so a larger gear gains teeth
        // instead of just larger ones — which is what makes a big gear look machined and not inflated.
        // Capped: past about 28 the teeth are finer than the pixel grid can separate and the rim goes
        // back to reading as a serrated disc, which is the thing the taper is there to avoid.
        int teeth = Math.max(8, Math.min(28, (int) Math.round(r * 1.1)));
        double th = Math.max(2.0, r * 0.22);          // tooth height
        double gap = Math.PI / teeth;                 // half the pitch: tooth and gap are equal
        for (int i = 0; i < teeth; i++) {
            tooth(g, cx, cy, phase + i * (Math.PI * 2 / teeth), r - 1, r + th, gap * 0.55, reverse);
        }
        // The body, stepped toward the light so the plate domes rather than sitting flat.
        disc(g, cx, cy, r, BRASS_SHADOW);
        disc(g, cx, cy, r - 1, BRASS_MID);
        disc(g, cx - 1, cy - 1, r - 2, BRASS);
        disc(g, cx - 1, cy - 1, r - 4, BRASS_LIGHT);
        // Lightening holes, as a cast gear of any size has. They also carry the rotation, which a
        // plain plate does not.
        int holes = r >= 12 ? 6 : r >= 8 ? 4 : 3;
        int hr = Math.max(1, r / 6);
        double hd = r * 0.58;
        for (int i = 0; i < holes; i++) {
            double a = phase + i * (Math.PI * 2 / holes);
            int hx = cx + (int) Math.round(Math.cos(a) * hd);
            int hy = cy + (int) Math.round(Math.sin(a) * hd);
            disc(g, hx, hy, hr, BRASS_SHADOW);
            disc(g, hx, hy + 1, Math.max(1, hr - 1), BRASS_DARK);
        }
        // Hub and shaft.
        disc(g, cx, cy, Math.max(2, r / 3), BRASS_DARK);
        disc(g, cx - 1, cy - 1, Math.max(1, r / 3 - 1), BRASS_LIGHT);
        disc(g, cx, cy, Math.max(1, r / 6), BRASS_SHADOW);
    }

    /**
     * A spoked wagon wheel: an iron-tyred rim on straight spokes, turning with the gearing.
     *
     * <p>No teeth — it is a wheel, not a gear, and the contrast is the point of having one in the
     * cluster. Spokes are drawn with a lit and a shaded side so they read as round bar rather than as
     * lines scratched across a hole.
     */
    public static void wagonWheel(GuiGraphics g, int cx, int cy, int r, boolean reverse) {
        discShadow(g, cx, cy, r + 1);
        double phase = (System.currentTimeMillis() % 24000) / 24000.0 * Math.PI * 2.0;
        if (reverse) phase = -phase;
        // Radii worked out inward from the rim so each band keeps a real width at every size. Sizing
        // them from r independently lets the minimums collide on a small wheel and silently delete
        // whichever band lost.
        int tyre = Math.max(1, r / 9);                       // the iron band shrunk onto the rim
        int felloe = Math.max(2, r / 5);                     // the wooden rim under it
        int rTyre = r - 1, rFelloe = rTyre - tyre, rInner = Math.max(2, rFelloe - felloe);
        ring(g, cx, cy, r, rInner, BRASS_SHADOW);            // the whole rim's seat
        ring(g, cx, cy, rTyre, rFelloe, COPPER);             // the tyre, in the second metal
        ring(g, cx, cy, rFelloe, rInner, BRASS_LIGHT);
        ring(g, cx, cy, rFelloe - 1, rInner + 1, BRASS);

        int spokes = r >= 14 ? 10 : r >= 9 ? 8 : 6;
        int hub = Math.max(2, r / 4);
        for (int i = 0; i < spokes; i++) {
            double a = phase + i * (Math.PI * 2 / spokes);
            double ca = Math.cos(a), sa = Math.sin(a);
            // Every other pixel: the stamps are 2 wide, so they still overlap and the spoke stays
            // solid. See strokeSteps in SteamOrnament for the same reasoning applied to ornament.
            for (int d = hub - 1; d <= rInner + 1; d += 2) {
                int px = cx + (int) Math.round(ca * d);
                int py = cy + (int) Math.round(sa * d);
                g.fill(px, py, px + 2, py + 2, BRASS_DARK);
                g.fill(px, py, px + 1, py + 1, BRASS_LIGHT);
            }
        }
        disc(g, cx, cy, hub + 1, BRASS_SHADOW);
        disc(g, cx, cy, hub, BRASS_MID);
        disc(g, cx - 1, cy - 1, hub - 1, BRASS_LIGHT);
        disc(g, cx, cy, Math.max(1, hub / 3), BRASS_SHADOW);
    }

    /**
     * A train of three: a large gear, a small one meshing with it, and a wagon wheel on the same
     * shaft line.
     *
     * <p>Arranged as a triangle rather than a row, so a cluster of three takes about the footprint a
     * pair used to and callers do not have to be re-measured. The meshing pair sit exactly the sum of
     * their radii apart, which is where real gears sit — closer and the teeth would foul, further and
     * the train would visibly not drive.
     */
    public static void gearCluster(GuiGraphics g, int cx, int cy, int r) {
        int rb = Math.max(3, (int) Math.round(r * 0.62));
        int rc = Math.max(4, (int) Math.round(r * 0.80));
        double up = -0.70, down = 0.62;
        int bx = cx + (int) Math.round(Math.cos(up) * (r + rb));
        int by = cy + (int) Math.round(Math.sin(up) * (r + rb));
        int wx = cx + (int) Math.round(Math.cos(down) * (r + rc * 0.92));
        int wy = cy + (int) Math.round(Math.sin(down) * (r + rc * 0.92));
        wagonWheel(g, wx, wy, rc, false);
        ornamentGear(g, bx, by, rb, true);
        ornamentGear(g, cx, cy, r, false);
    }

    /** Corner brackets for large views, where a continuous bezel would eat the content's edges. */
    public static void corners(GuiGraphics g, int x, int y, int w, int h, int len) {
        int[][] cs = {{x, y, 1, 1}, {x + w, y, -1, 1}, {x, y + h, 1, -1}, {x + w, y + h, -1, -1}};
        for (int[] c : cs) {
            int cx = c[0], cy = c[1], sx = c[2], sy = c[3];
            int ax = sx > 0 ? cx : cx - len, ay = sy > 0 ? cy : cy - 3;
            roundRect(g, ax, ay, len, 3, 1, BRASS);
            int bx = sx > 0 ? cx : cx - 3, by = sy > 0 ? cy : cy - len;
            roundRect(g, bx, by, 3, len, 1, BRASS);
            screw(g, cx + (sx > 0 ? 6 : -7), cy + (sy > 0 ? 6 : -7));
        }
    }

    // ---- panels --------------------------------------------------------------------------------

    /** Radius used by every panel and readout, so they all match. */
    public static final int R = 5;

    /** A dark instrument panel in a solid brass case with radiused corners. */
    public static int panel(GuiGraphics g, int x, int y, int w, int h) {
        roundRect(g, x, y, w, h, R, PANEL);
        bezel(g, x - 2, y - 2, w + 4, h + 4, 3, R + 2);
        rivetRun(g, x + 10, y - 4, x + w - 10, y - 4, 34);
        rivetRun(g, x + 10, y + h + 3, x + w - 10, y + h + 3, 34);
        return x + 6;
    }

    /** A recessed reading, for numbers that should look set into the panel. */
    public static void inset(GuiGraphics g, int x, int y, int w, int h) {
        roundRect(g, x, y, w, h, 3, PANEL_INSET);
        sunken(g, x, y, w, h, 3, 1);
    }

    /** An engraved brass nameplate with radiused ends. */
    public static void nameplate(GuiGraphics g, net.minecraft.client.gui.Font font, String label,
                                 int cx, int y) {
        int w = font.width(label) + 34;
        int x = cx - w / 2;
        roundRect(g, x, y - 4, w, 17, 6, BRASS_MID);
        bezel(g, x, y - 4, w, 17, 2, 6);
        roundRect(g, x + 5, y - 1, w - 10, 11, 3, PANEL);
        screw(g, x + 9, y + 4);
        screw(g, x + w - 10, y + 4);
        int tx = cx - font.width(label) / 2;
        g.drawString(font, label, tx + 1, y + 2, 0xFF2A1E0C);
        g.drawString(font, label, tx, y + 1, BRASS_HI);
    }

    /** A status row in its own rounded housing. Returns the height consumed. */
    public static int readout(GuiGraphics g, net.minecraft.client.gui.Font font, String text,
                              int x, int y, int color) {
        int w = font.width(text) + 20;
        int h = 15;
        roundRect(g, x, y - 3, w, h, 4, PANEL);
        bezel(g, x, y - 3, w, h, 2, 4);
        // A brass terminal at the left end, like a labelled post on an instrument case.
        disc(g, x + 8, y + 4, 4, BRASS_MID);
        rivet(g, x + 8, y + 4);
        g.drawString(font, text, x + 15, y + 1, color);
        return h + 3;
    }
}

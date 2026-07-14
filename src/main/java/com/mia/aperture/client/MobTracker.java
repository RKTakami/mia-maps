package com.mia.aperture.client;

import com.mia.aperture.map.MapSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

// Collects nearby living entities the client knows about, classified + filtered by the settings,
// sorted nearest-first (horizontal). Client-only: the server only sends entities within tracking
// range, which suits a local minimap radius.
//
// MIA mobs are ModelEngine constructs, not vanilla living entities: each is an Interaction hitbox
// (what you attack) plus one or more item_display models and a floating text_display nameplate.
// The Interaction is the reliable per-mob signal for position; the nameplate ("Cyatoria 16/25")
// and the item model id (modelengine:vinebinder/stem3) are how we recover a real name.
public final class MobTracker {
    public enum Cat {
        HOSTILE(0xFFFF3344), PLAYER(0xFFFFFFFF), PASSIVE(0xFF33CC44);
        public final int color;
        Cat(int c) { this.color = c; }
    }
    public record Blip(double x, double y, double z, Cat cat, String name, double horizSq) {}

    private MobTracker() {}

    // Nameplates sit just above the mob; search a small horizontal radius and a mostly-upward band.
    private static final double NAMEPLATE_H2 = 2.5 * 2.5;
    private static final double NAMEPLATE_UP = 4.5;
    private static final double NAMEPLATE_DOWN = -1.0;
    private static final double MODEL_H2 = 2.0 * 2.0;
    private static final double MODEL_DY = 3.0;

    // horizRadius = blocks; band = +/- vertical blocks (<=0 disables the band).
    public static List<Blip> collect(Minecraft mc, double horizRadius, double band, MapSettings s) {
        List<Blip> out = new ArrayList<>();
        if (mc.level == null || mc.player == null) return out;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        double r2 = horizRadius * horizRadius;

        List<Entity> mobs = new ArrayList<>();
        List<Display.TextDisplay> texts = new ArrayList<>();
        List<Display.ItemDisplay> items = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            if (e instanceof Display.TextDisplay td) { texts.add(td); continue; }
            if (e instanceof Display.ItemDisplay id) { items.add(id); continue; }
            boolean interaction = e instanceof net.minecraft.world.entity.Interaction;
            boolean livingMob = e instanceof LivingEntity
                    && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand);
            if (!interaction && !livingMob) continue;
            double dx = e.getX() - px, dz = e.getZ() - pz;
            if (dx * dx + dz * dz > r2) continue;
            if (band > 0 && Math.abs(e.getY() - py) > band) continue;
            mobs.add(e);
        }

        for (Entity e : mobs) {
            double ex = e.getX(), ey = e.getY(), ez = e.getZ();
            double dx = ex - px, dz = ez - pz;
            double h2 = dx * dx + dz * dz;
            Cat cat = e instanceof Player ? Cat.PLAYER
                    : e instanceof net.minecraft.world.entity.animal.Animal ? Cat.PASSIVE
                    : Cat.HOSTILE;
            if (cat == Cat.HOSTILE && !s.trackHostiles) continue;
            if (cat == Cat.PLAYER && !s.trackPlayers) continue;
            if (cat == Cat.PASSIVE && !s.trackPassive) continue;
            out.add(new Blip(ex, ey, ez, cat, resolveName(e, cat, texts, items), h2));
        }
        out.sort((a, b) -> Double.compare(a.horizSq(), b.horizSq()));
        return out;
    }

    // Recover a display name for one mob: player name, then custom name, then the floating
    // nameplate text, then the ModelEngine model id, else a generic "Mob".
    private static String resolveName(Entity mob, Cat cat,
            List<Display.TextDisplay> texts, List<Display.ItemDisplay> items) {
        if (cat == Cat.PLAYER) return mob.getName().getString();
        var cn = mob.getCustomName();
        if (cn != null) {
            String t = cleanName(cn.getString());
            if (!t.isEmpty()) return t;
        }
        double mx = mob.getX(), my = mob.getY(), mz = mob.getZ();

        Display.TextDisplay bestText = null;
        double bestTextD = NAMEPLATE_H2;
        for (Display.TextDisplay td : texts) {
            double dy = td.getY() - my;
            if (dy < NAMEPLATE_DOWN || dy > NAMEPLATE_UP) continue;
            double dx = td.getX() - mx, dz = td.getZ() - mz;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestTextD) { bestTextD = d2; bestText = td; }
        }
        if (bestText != null) {
            var rs = bestText.textRenderState();
            if (rs != null && rs.text() != null) {
                String t = cleanName(rs.text().getString());
                if (!t.isEmpty()) return t;
            }
        }

        Display.ItemDisplay bestItem = null;
        double bestItemD = MODEL_H2;
        for (Display.ItemDisplay id : items) {
            if (Math.abs(id.getY() - my) > MODEL_DY) continue;
            double dx = id.getX() - mx, dz = id.getZ() - mz;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestItemD) { bestItemD = d2; bestItem = id; }
        }
        if (bestItem != null) {
            String m = modelName(bestItem);
            if (m != null) return m;
        }

        if (cat == Cat.PASSIVE) return mob.getName().getString();
        return "Mob";
    }

    // "Cyatoria 16/25" / "Cyatoria\n16/25" -> "Cyatoria"; drops a trailing health readout.
    private static String cleanName(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\n', ' ').trim();
        s = s.replaceAll("\\s*\\d+\\s*/\\s*\\d+.*$", "").trim();
        return s;
    }

    // "modelengine:vinebinder/stem3" -> "Vinebinder" (first path segment, title-cased).
    private static String modelName(Display.ItemDisplay id) {
        ItemStack st = id.getSlot(0).get();
        if (st.isEmpty()) return null;
        var model = st.get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
        if (model == null) return null;
        String full = model.toString();
        int colon = full.indexOf(':');
        String path = colon >= 0 ? full.substring(colon + 1) : full;
        int slash = path.indexOf('/');
        if (slash > 0) path = path.substring(0, slash);
        return titleCase(path.replace('_', ' '));
    }

    private static String titleCase(String s) {
        StringBuilder b = new StringBuilder();
        for (String p : s.split(" ")) {
            if (p.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return b.toString();
    }

    // TEMP DEBUG: list the nearest tracked mobs with their resolved names, to confirm the
    // nameplate/model name recovery works in-world. Strip before release.
    public static String debug(Minecraft mc) {
        if (mc.level == null || mc.player == null) return "no level";
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        List<Entity> mobs = new ArrayList<>();
        List<Display.TextDisplay> texts = new ArrayList<>();
        List<Display.ItemDisplay> items = new ArrayList<>();
        int inter = 0;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            if (e instanceof Display.TextDisplay td) { texts.add(td); continue; }
            if (e instanceof Display.ItemDisplay id) { items.add(id); continue; }
            boolean interaction = e instanceof net.minecraft.world.entity.Interaction;
            if (interaction) inter++;
            boolean livingMob = e instanceof LivingEntity
                    && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand);
            if (!interaction && !livingMob) continue;
            if (e.distanceToSqr(px, py, pz) < 96 * 96) mobs.add(e);
        }
        mobs.sort((a, b) -> Double.compare(a.distanceToSqr(px, py, pz), b.distanceToSqr(px, py, pz)));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, mobs.size()); i++) {
            Entity e = mobs.get(i);
            Cat cat = e instanceof Player ? Cat.PLAYER
                    : e instanceof net.minecraft.world.entity.animal.Animal ? Cat.PASSIVE : Cat.HOSTILE;
            int d = (int) Math.sqrt(e.distanceToSqr(px, py, pz));
            sb.append("'").append(resolveName(e, cat, texts, items)).append("'").append(d).append("m ");
        }
        return "mobs=" + mobs.size() + " inter=" + inter + " text=" + texts.size()
                + " item=" + items.size() + " | " + sb;
    }
}

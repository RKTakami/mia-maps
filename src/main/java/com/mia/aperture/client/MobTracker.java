package com.mia.aperture.client;

import com.mia.aperture.map.MapSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

// Collects nearby living entities the client knows about, classified + filtered by the settings,
// sorted nearest-first (horizontal). Client-only: the server only sends entities within tracking
// range, which suits a local minimap radius.
public final class MobTracker {
    public enum Cat {
        HOSTILE(0xFFFF3344), PLAYER(0xFFFFFFFF), PASSIVE(0xFF33CC44);
        public final int color;
        Cat(int c) { this.color = c; }
    }
    public record Blip(double x, double y, double z, Cat cat, String name, double horizSq) {}

    private MobTracker() {}

    // horizRadius = blocks; band = +/- vertical blocks (<=0 disables the band).
    public static List<Blip> collect(Minecraft mc, double horizRadius, double band, MapSettings s) {
        List<Blip> out = new ArrayList<>();
        if (mc.level == null || mc.player == null) return out;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        double r2 = horizRadius * horizRadius;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            // MIA mobs are ModelEngine-style: an Interaction hitbox (what you attack) + item_display
            // model, sometimes an armor-stand anchor. The Interaction is the reliable per-mob signal,
            // so track those + real living mobs, but SKIP armor stands (decor / duplicate anchors).
            boolean interaction = e instanceof net.minecraft.world.entity.Interaction;
            boolean livingMob = e instanceof LivingEntity
                    && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand);
            if (!interaction && !livingMob) continue;
            double ex = e.getX(), ey = e.getY(), ez = e.getZ();
            double dx = ex - px, dz = ez - pz;
            double h2 = dx * dx + dz * dz;
            if (h2 > r2) continue;
            if (band > 0 && Math.abs(ey - py) > band) continue;
            // Vanilla passive animals green; players white; everything else (mobs) red.
            Cat cat = e instanceof Player ? Cat.PLAYER
                    : e instanceof net.minecraft.world.entity.animal.Animal ? Cat.PASSIVE
                    : Cat.HOSTILE;
            if (cat == Cat.HOSTILE && !s.trackHostiles) continue;
            if (cat == Cat.PLAYER && !s.trackPlayers) continue;
            if (cat == Cat.PASSIVE && !s.trackPassive) continue;
            var cn = e.getCustomName();
            String name = cn != null ? cn.getString()
                    : (e instanceof Player || e instanceof net.minecraft.world.entity.animal.Animal)
                        ? e.getName().getString() : "Mob";
            out.add(new Blip(ex, ey, ez, cat, name, h2));
        }
        out.sort((a, b) -> Double.compare(a.horizSq(), b.horizSq()));
        return out;
    }

    // TEMP DEBUG: what entities does the client actually see near the player, and how do they
    // classify? Reveals whether MIA mobs are vanilla Enemy/LivingEntity or something else.
    public static String debug(Minecraft mc) {
        if (mc.level == null || mc.player == null) return "no level";
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        int raw = 0, live = 0, enemy = 0, animal = 0, inter = 0;
        List<Entity> near = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            raw++;
            if (e == mc.player) continue;
            if (e instanceof LivingEntity) live++;
            if (e instanceof Enemy) enemy++;
            if (e instanceof net.minecraft.world.entity.animal.Animal) animal++;
            if (e instanceof net.minecraft.world.entity.Interaction) inter++;
            String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (id.contains("area_effect_cloud")) continue; // ambient Forest particles — skip
            if (e.distanceToSqr(px, py, pz) < 96 * 96) near.add(e);
        }
        near.sort((a, b) -> Double.compare(a.distanceToSqr(px, py, pz), b.distanceToSqr(px, py, pz)));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, near.size()); i++) {
            Entity e = near.get(i);
            String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            String flag = e instanceof Enemy ? "[H]" : e instanceof Player ? "[P]"
                    : e instanceof net.minecraft.world.entity.animal.Animal ? "[A]"
                    : e instanceof LivingEntity ? "[L]" : "[-]";
            int d = (int) Math.sqrt(e.distanceToSqr(px, py, pz));
            sb.append(id.replace("minecraft:", "")).append(flag).append(d).append("m ");
        }
        return "raw=" + raw + " live=" + live + " enemy=" + enemy + " animal=" + animal + " inter=" + inter + " | " + sb;
    }
}

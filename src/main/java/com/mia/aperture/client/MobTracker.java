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
            if (!(e instanceof LivingEntity) || e == mc.player) continue;
            double ex = e.getX(), ey = e.getY(), ez = e.getZ();
            double dx = ex - px, dz = ez - pz;
            double h2 = dx * dx + dz * dz;
            if (h2 > r2) continue;
            if (band > 0 && Math.abs(ey - py) > band) continue;
            Cat cat = e instanceof Player ? Cat.PLAYER
                    : e instanceof Enemy ? Cat.HOSTILE : Cat.PASSIVE;
            if (cat == Cat.HOSTILE && !s.trackHostiles) continue;
            if (cat == Cat.PLAYER && !s.trackPlayers) continue;
            if (cat == Cat.PASSIVE && !s.trackPassive) continue;
            out.add(new Blip(ex, ey, ez, cat, e.getName().getString(), h2));
        }
        out.sort((a, b) -> Double.compare(a.horizSq(), b.horizSq()));
        return out;
    }
}

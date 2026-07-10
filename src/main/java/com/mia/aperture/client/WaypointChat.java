package com.mia.aperture.client;

import com.mia.aperture.map.Waypoint;
import com.mia.aperture.map.WaypointCodec;
import com.mia.aperture.map.WaypointConfig;
import com.mia.aperture.map.WaypointStore;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class WaypointChat {
    private static final Map<Integer, Waypoint> PENDING = new HashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private WaypointChat() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(literal("miawp")
                .then(literal("accept").then(argument("id", IntegerArgumentType.integer())
                    .executes(ctx -> { accept(IntegerArgumentType.getInteger(ctx, "id")); return 1; })))
                .then(literal("reject").then(argument("id", IntegerArgumentType.integer())
                    .executes(ctx -> { reject(IntegerArgumentType.getInteger(ctx, "id")); return 1; })))));

        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, timestamp) -> {
            Optional<Waypoint> wp = WaypointCodec.decode(message.getString());
            if (wp.isEmpty()) return true; // ordinary chat -> show as-is
            Minecraft mc = Minecraft.getInstance();
            boolean self = sender != null && mc.player != null
                    && sender.id().equals(mc.player.getGameProfile().id());
            int id = NEXT_ID.incrementAndGet();
            PENDING.put(id, wp.get());
            String who = self ? "You" : (sender != null ? sender.name() : "Someone");
            mc.gui.getChat().addMessage(prompt(who, wp.get(), id));
            return false; // replace the raw line with our augmented one
        });
    }

    public static void share(Waypoint w) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.connection.sendChat(WaypointCodec.encode(w));
    }

    private static Component prompt(String who, Waypoint w, int id) {
        MutableComponent line = Component.literal("[MIA Maps] " + who + " shared \"" + w.name + "\" ("
                + w.x + " " + w.y + " " + w.z + ")  ").withStyle(Style.EMPTY.withColor(0xAAAAAA));
        MutableComponent accept = Component.literal("[✓ Add]").withStyle(Style.EMPTY
                .withColor(0x55FF55)
                .withClickEvent(new ClickEvent.RunCommand("/miawp accept " + id)));
        MutableComponent reject = Component.literal("  [✗ Reject]").withStyle(Style.EMPTY
                .withColor(0xFF5555)
                .withClickEvent(new ClickEvent.RunCommand("/miawp reject " + id)));
        return line.append(accept).append(reject);
    }

    private static void accept(int id) {
        Minecraft mc = Minecraft.getInstance();
        Waypoint w = PENDING.remove(id);
        if (w == null) { info("That waypoint was already handled."); return; }
        String key = WaypointStore.currentServerKey(mc);
        MiaApertureModClient.waypoints.add(key, w);
        WaypointConfig.save(MiaApertureModClient.waypointConfigPath(), MiaApertureModClient.waypoints);
        info("Added waypoint \"" + w.name + "\".");
    }

    private static void reject(int id) {
        PENDING.remove(id);
        info("Dismissed shared waypoint.");
    }

    private static void info(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[MIA Maps] " + msg), false);
    }
}

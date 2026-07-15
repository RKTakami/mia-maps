package com.mia.aperture.client;

import com.mia.aperture.map.HelpContent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

public class HelpScreen extends Screen {
    private final Screen parent;
    // Rendered manually (like MapSettingsScreen) so we never call super.render()/renderBackground(),
    // which would trip the modpack blur mod's "Can only blur once per frame".
    private final List<Button> widgets = new ArrayList<>();
    private HelpContent.Tab active = HelpContent.Tab.OVERVIEW;
    private double scrollOffset;
    private int maxScroll;
    private int contentTop, contentBottom;

    private static final int LINE_H = 11;
    private static final int HEADING_GAP = 8;
    private static final int LEFT_PAD = 16;
    private static final int KEY_COL_W = 132;
    private static final int KEY_COLOR = 0xFF66CCFF;
    private static final int HEAD_COLOR = 0xFFFFFFFF;
    private static final int BODY_COLOR = 0xFFC8C8C8;

    // Live keys for the rebindable global binds; unknown actions fall back to their own name.
    private static final HelpContent.KeyResolver KEYS = action -> {
        net.minecraft.client.KeyMapping km = switch (action) {
            case "open_map" -> MiaApertureModClient.mapKeyBind;
            case "mark_waypoint" -> MiaApertureModClient.markWaypointKeyBind;
            case "toggle_beacons" -> MiaApertureModClient.toggleBeaconsKeyBind;
            case "cave_mode" -> MiaApertureModClient.caveKeyBind;
            case "toggle_cull" -> MiaApertureModClient.toggleCullKeyBind;
            case "reset_view" -> MiaApertureModClient.resetKeyBind;
            default -> null;
        };
        return km == null ? action : km.getTranslatedKeyMessage().getString();
    };

    public HelpScreen(Screen parent) {
        super(Component.literal("MIA Maps - Help"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        widgets.clear();
        contentTop = 58;
        contentBottom = this.height - 40;

        HelpContent.Tab[] tabs = HelpContent.Tab.values();
        int gap = 6;
        int[] w = new int[tabs.length];
        int totalW = 0;
        for (int i = 0; i < tabs.length; i++) {
            w[i] = this.font.width(tabs[i].title) + 12;
            totalW += w[i] + (i > 0 ? gap : 0);
        }
        int x = Math.max(6, (this.width - totalW) / 2);
        for (int i = 0; i < tabs.length; i++) {
            final HelpContent.Tab tab = tabs[i];
            Button b = Button.builder(Component.literal(tab.title), btn -> selectTab(tab))
                    .bounds(x, 32, w[i], 18).build();
            b.active = (tab != active); // active tab shown disabled as the selection cue
            widgets.add(this.addRenderableWidget(b)); // addRenderableWidget -> clickable; rendered manually below
            x += w[i] + gap;
        }

        widgets.add(this.addRenderableWidget(Button.builder(Component.literal("Done"),
                btn -> this.minecraft.setScreen(parent))
                .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build()));
    }

    private void selectTab(HelpContent.Tab t) {
        this.active = t;
        this.scrollOffset = 0;
        this.rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Plain dark fill instead of renderBackground()'s blur (the modpack already blurs once).
        g.fill(0, 0, this.width, this.height, 0xE0101018);
        g.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
        for (Button b : widgets) b.render(g, mouseX, mouseY, partial); // manual: no super.render()

        List<HelpContent.Line> lines = HelpContent.lines(active, KEYS);
        int total = layout(g, lines, false);
        maxScroll = Math.max(0, total - (contentBottom - contentTop));
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;

        g.enableScissor(0, contentTop, this.width, contentBottom);
        layout(g, lines, true);
        g.disableScissor();
    }

    // Measures (draw=false) or draws (draw=true) the active tab's content; returns its total height.
    private int layout(GuiGraphics g, List<HelpContent.Line> lines, boolean draw) {
        int contentRight = this.width - 12;
        int y = contentTop - (int) scrollOffset;
        int startY = y;
        for (HelpContent.Line ln : lines) {
            if (ln.heading()) {
                y += HEADING_GAP;
                if (draw) g.drawString(this.font, ln.text(), LEFT_PAD - 6, y, HEAD_COLOR);
                y += LINE_H + 2;
            } else if (ln.key() != null) {
                if (draw) g.drawString(this.font, ln.key(), LEFT_PAD, y, KEY_COLOR);
                int tx = LEFT_PAD + KEY_COL_W;
                List<FormattedCharSequence> wrapped = this.font.split(Component.literal(ln.text()), contentRight - tx);
                for (FormattedCharSequence seq : wrapped) {
                    if (draw) g.drawString(this.font, seq, tx, y, BODY_COLOR);
                    y += LINE_H;
                }
                if (wrapped.isEmpty()) y += LINE_H;
            } else {
                List<FormattedCharSequence> wrapped = this.font.split(Component.literal(ln.text()), contentRight - LEFT_PAD);
                for (FormattedCharSequence seq : wrapped) {
                    if (draw) g.drawString(this.font, seq, LEFT_PAD, y, BODY_COLOR);
                    y += LINE_H;
                }
                if (wrapped.isEmpty()) y += LINE_H;
            }
        }
        return y - startY;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - dy * 16));
        return true;
    }

    @Override
    public void onClose() { this.minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}

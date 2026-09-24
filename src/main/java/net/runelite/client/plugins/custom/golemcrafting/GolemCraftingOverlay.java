package net.runelite.client.plugins.custom.golemcrafting;

import net.runelite.client.plugins.custom.golemcrafting.actions.GolemContext;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.time.Duration;
import java.time.Instant;

public class GolemCraftingOverlay extends OverlayPanel {

    private final GolemCraftingPlugin plugin;

    @Inject
    GolemCraftingOverlay(GolemCraftingPlugin plugin) {
        super(plugin);
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            GolemContext ctx = plugin.getContext();
            panelComponent.setPreferredSize(new Dimension(220, 200));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Golem Crafting - v" + GolemCraftingPlugin.version)
                    .color(Color.GREEN)
                    .build());

            if (ctx == null) {
                return super.render(graphics);
            }

            Duration runtime = Duration.between(ctx.getStartTime(), Instant.now());
            addLine("Runtime", formatDuration(runtime));
            addLine("Phase", String.valueOf(ctx.getPhase()));
            addLine("Mode", String.valueOf(ctx.getCraftingMode()));
            addLine("Status", ctx.getStatus());
            addLine("Golems made", String.valueOf(ctx.getGolemsCompleted()));
            addLine("Gems looted", String.valueOf(ctx.getGemsLooted()));
            addLine("Furs left (est.)", ctx.getFurRemaining() < 0 ? "?" : String.valueOf(ctx.getFurRemaining()));

            long hours = Math.max(1, runtime.toSeconds());
            long perHour = ctx.getGolemsCompleted() * 3600L / hours;
            addLine("Golems/hr", String.valueOf(perHour));
        } catch (Exception ignored) {
            // Overlay must never throw during render.
        }
        return super.render(graphics);
    }

    private void addLine(String left, String right) {
        panelComponent.getChildren().add(LineComponent.builder()
                .left(left)
                .right(right)
                .leftColor(Color.WHITE)
                .rightColor(Color.WHITE)
                .build());
    }

    // Kept to avoid pulling in an extra util just for formatting.
    private String formatDuration(Duration d) {
        long s = d.getSeconds();
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}

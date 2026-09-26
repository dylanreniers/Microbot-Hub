package net.runelite.client.plugins.custom.dashingkebbit;

import net.runelite.client.plugins.microbot.util.misc.TimeUtils;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.time.Instant;

public class DashingKebbitOverlay extends OverlayPanel {

    private final DashingKebbitPlugin plugin;

    @Inject
    public DashingKebbitOverlay(DashingKebbitPlugin plugin) {
        super(plugin);
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            DashingKebbitScript script = plugin.getScript();
            panelComponent.setPreferredSize(new Dimension(215, 0));

            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Dashing Kebbit")
                    .color(Color.GREEN)
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Runtime:")
                    .right(getRuntime())
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Status:")
                    .right(script == null ? "-" : script.getStatus())
                    .rightColor(Color.YELLOW)
                    .build());

            int caught = script == null ? 0 : script.getCatches();
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Caught:")
                    .right(caught + " (" + perHour(caught) + "/hr)")
                    .rightColor(Color.YELLOW)
                    .build());
        } catch (Exception ex) {
            // never let the overlay throw
        }
        return super.render(graphics);
    }

    private String getRuntime() {
        return plugin.getStartTime() == null ? "-"
                : TimeUtils.getFormattedDurationBetween(plugin.getStartTime(), Instant.now());
    }

    private int perHour(int amount) {
        if (plugin.getStartTime() == null) {
            return 0;
        }
        double hours = (Instant.now().toEpochMilli() - plugin.getStartTime().toEpochMilli()) / 3_600_000.0;
        return hours > 0 ? (int) (amount / hours) : 0;
    }
}

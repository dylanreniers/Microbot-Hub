package net.runelite.client.plugins.custom.barronitemining;

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

public class BarroniteMiningOverlay extends OverlayPanel {

    private final BarroniteMiningPlugin plugin;

    @Inject
    public BarroniteMiningOverlay(BarroniteMiningPlugin plugin) {
        super(plugin);
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            BarroniteMiningScript script = plugin.getScript();
            panelComponent.setPreferredSize(new Dimension(215, 0));

            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Barronite Mining")
                    .color(Color.GREEN)
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Runtime:")
                    .right(plugin.getStartTime() == null ? "-"
                            : TimeUtils.getFormattedDurationBetween(plugin.getStartTime(), Instant.now()))
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Status:")
                    .right(script == null ? "-" : script.getStatus())
                    .rightColor(Color.YELLOW)
                    .build());
        } catch (Exception ex) {
            // never let the overlay throw
        }
        return super.render(graphics);
    }
}

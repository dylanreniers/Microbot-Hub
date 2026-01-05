package net.runelite.client.plugins.microbot.custom.barrows;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class BarrowsOverlay extends OverlayPanel {

    private final BarrowsPlugin barrowsPlugin;

    @Inject
    public BarrowsOverlay(BarrowsPlugin plugin) {
        super(plugin);
        this.barrowsPlugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.setPreferredSize(new Dimension(200, 300));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Barrows V"+ BarrowsPlugin.version)
                    .color(Color.GREEN)
                    .build());

            panelComponent.getChildren().add(LineComponent.builder().build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left(Microbot.status)
                    .build());

            // Add chests count
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Chests looted:")
                    .right(Integer.toString(barrowsPlugin.getBarrowsScript().getChestsOpened()))
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Tunnel:")
                    .right(barrowsPlugin.getBarrowsScript().getBrotherInTunnel().split(" ")[0])
                    .build());
        } catch(Exception ex) {
            System.out.println(ex.getMessage());
        }
        return super.render(graphics);
    }
}

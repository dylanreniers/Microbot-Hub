package net.runelite.client.plugins.custom.customgauntlet;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class CustomGauntletOverlay extends OverlayPanel {

    private final CustomGauntletPlugin plugin;
    private final CustomGauntletConfig config;
    private final CustomGauntletScript script;

    @Inject
    CustomGauntletOverlay(CustomGauntletPlugin plugin, CustomGauntletConfig config, CustomGauntletScript script) {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        this.script = script;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics) {

        if (!config.enableOverlay()) {
            panelComponent.getChildren().clear();
            return null; // returning null tells RuneLite not to render anything
        }

            panelComponent.setPreferredSize(new Dimension(250, 500));
            panelComponent.getChildren().clear(); // Always clear before rendering

            try {
                // === Header ===
                panelComponent.getChildren().add(
                        TitleComponent.builder()
                                .text("Gauntlet Prayer Helper")
                                .color(new Color(0x00FF88))
                                .build()
                );

                panelComponent.getChildren().add(LineComponent.builder().build());

                // === Next Prayer ===
                String nextPrayerText = (plugin != null && script.getNextPrayer() != null)
                        ? script.getNextPrayer().toString()
                        : "None";

                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Next Prayer:")
                        .right(nextPrayerText)
                        .leftColor(Color.WHITE)
                        .rightColor(Color.CYAN)
                        .build());

                String stateText = (plugin != null && CustomGauntletScript.ghState != null)
                        ? CustomGauntletScript.ghState.toString()
                        : "None";

                panelComponent.getChildren().add(LineComponent.builder()
                        .left("State:")
                        .right(stateText)
                        .leftColor(Color.WHITE)
                        .rightColor(Color.CYAN)
                        .build());


                // === General Microbot Status ===
                if (Microbot.status != null && !Microbot.status.isEmpty()) {
                    panelComponent.getChildren().add(LineComponent.builder()
                            .left("Status:")
                            .right(Microbot.status)
                            .leftColor(Color.WHITE)
                            .rightColor(Color.GREEN)
                            .build());
                }

            } catch (Exception ex) {
                Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
            }

        return super.render(graphics);
    }
}

package net.runelite.client.plugins.custom.driftnetmaker;

import net.runelite.client.plugins.custom.blastoisefurnace.BlastoiseFurnacePlugin;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class DriftNetMakerOverlay extends OverlayPanel {

    @Inject
    DriftNetMakerOverlay(DriftNetMakerPlugin plugin) {
        super(plugin);
        this.setPosition(OverlayPosition.TOP_LEFT);
        this.setNaughty();
    }

    public Dimension render(Graphics2D graphics) {
        try {
            this.panelComponent.setPreferredSize(new Dimension(200, 300));
            this.panelComponent.getChildren().add(TitleComponent.builder().text("Donder's Drift net maker v" + DriftNetMakerPlugin.VERSION).color(Color.GREEN).build());
            this.panelComponent.getChildren().add(LineComponent.builder().build());
            this.panelComponent.getChildren().add(LineComponent.builder().left(Microbot.status).build());
        } catch (Exception ex) {
            Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
        }

        return super.render(graphics);
    }
}

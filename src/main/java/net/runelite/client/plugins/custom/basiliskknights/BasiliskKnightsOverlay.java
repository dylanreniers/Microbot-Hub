package net.runelite.client.plugins.custom.basiliskknights;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

public class BasiliskKnightsOverlay extends OverlayPanel
{
    @Inject
    BasiliskKnightsOverlay(BasiliskKnightsPlugin plugin)
    {
        super(plugin);
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        try
        {
            panelComponent.setPreferredSize(new Dimension(220, 100));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Basilisk Knights (Custom) v" + BasiliskKnightsPlugin.version)
                    .color(Color.CYAN)
                    .build());
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Status: " + BasiliskKnightsPlugin.status)
                    .color(Color.GREEN)
                    .build());
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Kills: " + BasiliskKnightsScript.killCount)
                    .color(Color.YELLOW)
                    .build());
        }
        catch (Exception ex)
        {
            System.out.println(ex.getMessage());
        }
        return super.render(graphics);
    }
}

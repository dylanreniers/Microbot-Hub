package net.runelite.client.plugins.custom.tormenteddemons;


import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class CustomTormentedDemonOverlay extends OverlayPanel {

    @Inject
    CustomTormentedDemonOverlay(CustomTormentedDemonPlugin plugin) {
        super(plugin);
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }


    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.setPreferredSize(new Dimension(200, 300));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("00 TormentedDemon (Custom) - Version: " + CustomTormentedDemonPlugin.version)
                    .color(Color.RED)
                    .build());
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Status: " + CustomTormentedDemonScript.BOT_STATUS)
                    .color(Color.GREEN)
                    .build());
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Kill Count: " + CustomTormentedDemonScript.killCount)  // Add kill count display
                    .color(Color.YELLOW)
                    .build());

        } catch(Exception ex) {
            System.out.println(ex.getMessage());
        }
        return super.render(graphics);
    }
}

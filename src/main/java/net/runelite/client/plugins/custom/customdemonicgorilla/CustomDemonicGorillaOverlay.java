package net.runelite.client.plugins.custom.customdemonicgorilla;


import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class CustomDemonicGorillaOverlay extends OverlayPanel {

    private final CustomDemonicGorillaPlugin plugin;

    @Inject
    CustomDemonicGorillaOverlay(CustomDemonicGorillaPlugin plugin) {
        super(plugin);
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }


    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.setPreferredSize(new Dimension(200, 300));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Demonic Gorilla (Custom) - v" + plugin.version)
                    .color(Color.GREEN)
                    .build());
            panelComponent.getChildren().add(LineComponent.builder().build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Running: " + plugin.getTimeRunning())
                    .leftColor(Color.WHITE)
                    .build());
            GorillaContext ctx = plugin.getContext();
            var state = ctx.getBotStatus() == GorillaContext.State.TRAVEL_TO_GORILLAS ? ctx.getTravelStep() : ctx.getBotStatus();
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Script status: " + state)
                    .leftColor(Color.WHITE)
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Microbot status: " + Microbot.status)
                    .leftColor(Color.WHITE)
                    .build());
            var lootRecord = Microbot.getAggregateLootRecords("Demonic Gorilla");
            var killValue = lootRecord == null ? "0" : String.valueOf(lootRecord.getKills());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Kill Count: " + ctx.getKillCount() + " / " + killValue)
                    .leftColor(Color.WHITE)
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Current trip kill count: " + ctx.getCurrentTripKillCount())
                    .leftColor(Color.WHITE)
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Total loot: " + Microbot.getAggregateLootRecordsTotalGevalue("Demonic Gorilla"))
                    .leftColor(Color.WHITE)
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Current defensive prayer: " + ctx.getCurrentDefensivePrayer())
                    .leftColor(Color.WHITE)
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Current gear: " + ctx.getCurrentGear())
                    .leftColor(Color.WHITE)
                    .build());

        } catch (Exception ex) {
            System.out.println(ex.getMessage());
        }
        return super.render(graphics);
    }
}

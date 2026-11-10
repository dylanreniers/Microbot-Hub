package net.runelite.client.plugins.custom.custommixology;

import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

import javax.inject.Inject;
import java.awt.*;
import java.time.Duration;

public class CustomMixologyOverlay extends OverlayPanel {
    private final CustomMixologyPlugin plugin;
    private final ModelOutlineRenderer modelOutlineRenderer;

    @Inject
    CustomMixologyOverlay(CustomMixologyPlugin plugin, ModelOutlineRenderer modelOutlineRenderer) {
        this.plugin = plugin;
        this.modelOutlineRenderer = modelOutlineRenderer;
        this.setPosition(OverlayPosition.DYNAMIC);
        this.setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public Dimension render(Graphics2D graphics) {

        panelComponent.setPreferredLocation(new Point(200, 20));
        panelComponent.setPreferredSize(new Dimension(300, 300));
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("Custom Mixology V" + CustomMixologyPlugin.version)
                .color(Color.GREEN)
                .build());

        panelComponent.getChildren().add(LineComponent.builder().build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Mixology state")
                .right(CustomMixologyScript.mixologyState.toString())
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Mox/Aga/Lye paste")
                .right(String.valueOf(CustomMixologyScript.moxPasteAmount) + "/" + String.valueOf(CustomMixologyScript.agaPasteAmount) + "/" + String.valueOf(CustomMixologyScript.lyePasteAmount))
                .build());

        Duration runtime = plugin.mixologyScript.getRunTime();

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Mox/Aga/Lye points per hour")
                .rightColor(Color.YELLOW)
                .right(calculatePointsPerHour(runtime.getSeconds()))
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Runtime")
                .rightColor(Color.GREEN)
                .right(String.format("%02d:%02d:%02d", runtime.toHours(), runtime.toMinutesPart(), runtime.toSecondsPart()))
                .build());

        for (CustomMixologyPlugin.HighlightedObject highlightedObject : this.plugin.highlightedObjects().values()) {
            this.modelOutlineRenderer.drawOutline(highlightedObject.object(), highlightedObject.outlineWidth(), highlightedObject.color(), highlightedObject.feather());
        }

        return super.render(graphics);
    }

    private String calculatePointsPerHour(long seconds) {
        // int elapsedTimeInSeconds = 3600; // Time elapsed (e.g., 1 hour = 3600 seconds)

        // Convert time to hours
        double elapsedTimeInHours = seconds / 3600.0;

        int gainedMoxPoints = CustomMixologyScript.currentMoxPoints - CustomMixologyScript.startMoxPoints;
        int gainedLyePoints = CustomMixologyScript.currentLyePoints - CustomMixologyScript.startLyePoints;
        int gainedAgaPoints = CustomMixologyScript.currentAgaPoints - CustomMixologyScript.startAgaPoints;

        // Calculate experience per hour
        int moxPointsPerHour = (int) (gainedMoxPoints / elapsedTimeInHours);
        int lyePointsPerHour = (int) (gainedLyePoints / elapsedTimeInHours);
        int agaPointsPerHour = (int) (gainedAgaPoints / elapsedTimeInHours);

        if (moxPointsPerHour < 0)
            moxPointsPerHour = 0;
        if (lyePointsPerHour < 0)
            lyePointsPerHour = 0;
        if (agaPointsPerHour < 0)
            agaPointsPerHour = 0;

        return "" + moxPointsPerHour + "/" + agaPointsPerHour + "/" + lyePointsPerHour;
    }
}

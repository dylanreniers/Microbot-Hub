package net.runelite.client.plugins.custom.gotr;

import net.runelite.client.plugins.custom.gotr.services.TimerService;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.ProgressPieComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;


public class GotrOverlay extends OverlayPanel {

    public static Color PUBLIC_TIMER_COLOR = Color.YELLOW;
    public static int TIMER_OVERLAY_DIAMETER = 20;
    private final GotrPlugin plugin;
    private final GotrScript gotrScript;
    private final TimerService timerService;
    private final ProgressPieComponent progressPieComponent = new ProgressPieComponent();

    int sleepingCounter;

    @Inject
    GotrOverlay(GotrPlugin plugin, GotrScript gotrScript, TimerService timerService) {
        super(plugin);
        this.plugin = plugin;
        this.gotrScript = gotrScript;
        this.timerService = timerService;
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.setPreferredSize(new Dimension(200, 300));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Micro Guardians of the rift V" + GotrPlugin.version)
                    .color(Color.GREEN)
                    .build());

            panelComponent.getChildren().add(LineComponent.builder().build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("STATE: " + (gotrScript.getState() != null ? gotrScript.getState() : "N/A"))
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Elemental points: " + gotrScript.getElementalRewardPoints())
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Catalytic points: " + gotrScript.getCatalyticRewardPoints())
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Time since portal: " + timerService.getTimeSincePortal())
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Total time script loop: " + (gotrScript.getTotalTime() != null ? gotrScript.getTotalTime() : 0) + "ms")
                    .build());

        } catch (Exception ex) {
            Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
        }
        return super.render(graphics);
    }
}

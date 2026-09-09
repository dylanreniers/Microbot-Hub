package net.runelite.client.plugins.custom.madangel;

import net.runelite.client.plugins.custom.madangel.actions.MadAngelContext;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

/** Small debug overlay: shows the last boss animation and the reaction currently in flight. */
public class MadAngelOverlay extends OverlayPanel {

    private final MadAngelPlugin plugin;

    @Inject
    MadAngelOverlay(MadAngelPlugin plugin) {
        super(plugin);
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.setPreferredSize(new Dimension(220, 140));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Mad Angel (Custom) - v" + MadAngelPlugin.version)
                    .color(Color.GREEN)
                    .build());
            MadAngelContext ctx = plugin.getContext();
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Target").right(ctx.getCurrentTarget() != null ? "yes" : "none")
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Last anim").right(String.valueOf(ctx.getLastAnimation()))
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Last reaction").right(ctx.getLastReaction())
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Sweep").right(ctx.isSweepPending() ? String.valueOf(ctx.getSweepSide()) : "-")
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Blast").right(ctx.isBlastActive() ? "active" : "-")
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Smite").right(ctx.isSmiteActive() ? (ctx.isSmitePrayerOn() ? "PRAY" : "charging") : "-")
                    .build());
        } catch (Exception ignored) {
        }
        return super.render(graphics);
    }
}

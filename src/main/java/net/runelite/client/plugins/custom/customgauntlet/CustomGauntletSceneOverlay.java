package net.runelite.client.plugins.custom.customgauntlet;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;

/**
 * Draws a marker on the tile the tornado-dodge is currently trying to reach, so the intended destination
 * can be eyeballed in-game (like the Mad Angel scene markers, but just the one safe target). The tile is
 * reported by {@link CustomGauntletScript#getDodgeRenderTarget()} in scene coords.
 */
public class CustomGauntletSceneOverlay extends Overlay {

    private static final Color FILL = new Color(0, 255, 0, 80);
    private static final Color OUTLINE = new Color(0, 255, 0, 230);
    private static final Color UNSAFE_FILL = new Color(255, 0, 0, 55);
    private static final Color UNSAFE_OUTLINE = new Color(255, 0, 0, 120);

    private final CustomGauntletConfig config;
    private final CustomGauntletScript script;

    @Inject
    CustomGauntletSceneOverlay(CustomGauntletPlugin plugin, CustomGauntletConfig config, CustomGauntletScript script) {
        super(plugin);
        this.config = config;
        this.script = script;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            Client client = Microbot.getClient();
            graphics.setStroke(new BasicStroke(2f));

            // Unsafe tiles the dodge is avoiding (damaging tiles, boss footprint, ...) — translucent red.
            int[] unsafe = CustomGauntletScript.getUnsafeTiles();
            for (int packed : unsafe) {
                LocalPoint ulp = LocalPoint.fromScene(packed >> 8, packed & 0xFF);
                if (ulp == null) continue;
                Polygon upoly = Perspective.getCanvasTilePoly(client, ulp);
                if (upoly == null) continue;
                graphics.setColor(UNSAFE_FILL);
                graphics.fill(upoly);
                graphics.setColor(UNSAFE_OUTLINE);
                graphics.draw(upoly);
            }

            // The tile we're dodging to — green, drawn on top.
            int[] target = CustomGauntletScript.getDodgeRenderTarget();
            if (target != null) {
                LocalPoint lp = LocalPoint.fromScene(target[0], target[1]);
                Polygon poly = lp == null ? null : Perspective.getCanvasTilePoly(client, lp);
                if (poly != null) {
                    graphics.setColor(FILL);
                    graphics.fill(poly);
                    graphics.setColor(OUTLINE);
                    graphics.draw(poly);
                    net.runelite.api.Point txt = Perspective.getCanvasTextLocation(client, graphics, lp, "dodge", 0);
                    if (txt != null) {
                        graphics.setColor(Color.WHITE);
                        graphics.drawString("dodge", txt.getX(), txt.getY());
                    }
                }
            }
        } catch (Exception ignored) {
            // Overlay must never throw into the render loop.
        }
        return null;
    }
}

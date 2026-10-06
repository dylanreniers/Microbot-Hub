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

    private static final Color FILL = new Color(0, 255, 0, 80);        // final safe target (green)
    private static final Color OUTLINE = new Color(0, 255, 0, 230);
    private static final Color PATH_FILL = new Color(0, 110, 255, 70);  // regular path tiles (blue)
    private static final Color PATH_OUTLINE = new Color(0, 110, 255, 150);
    private static final Color HOP_FILL = new Color(255, 215, 0, 90);   // intermediate hop waypoints (yellow)
    private static final Color HOP_OUTLINE = new Color(255, 215, 0, 220);

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

            // Planned run path (blue) — every tile along the safe route to the target.
            for (int packed : CustomGauntletScript.getPathTiles()) {
                drawTile(client, graphics, packed, PATH_FILL, PATH_OUTLINE);
            }

            // Intermediate hop waypoints we'd click on the way (yellow), drawn over the blue path.
            for (int packed : CustomGauntletScript.getHopTiles()) {
                drawTile(client, graphics, packed, HOP_FILL, HOP_OUTLINE);
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

    /** Fill + outline the scene tile packed as (sceneX<<8|sceneY). No-op if off-screen. */
    private void drawTile(Client client, Graphics2D graphics, int packed, Color fill, Color outline) {
        LocalPoint lp = LocalPoint.fromScene(packed >> 8, packed & 0xFF);
        if (lp == null) return;
        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly == null) return;
        graphics.setColor(fill);
        graphics.fill(poly);
        graphics.setColor(outline);
        graphics.draw(poly);
    }
}

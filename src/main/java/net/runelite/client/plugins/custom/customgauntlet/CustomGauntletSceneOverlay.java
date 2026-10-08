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
import java.util.List;

/**
 * Draws the 12-tile safe shuttle lane, endpoints, and current target on screen at all times during combat,
 * so the safe lane and pathing can be visually verified in real time before and during tornado phases.
 */
public class CustomGauntletSceneOverlay extends Overlay {

    private static final Color LANE_FILL = new Color(0, 255, 255, 30);      // 12-tile corridor (translucent cyan)
    private static final Color LANE_OUTLINE = new Color(0, 255, 255, 100);
    private static final Color SAFE_A_FILL = new Color(0, 200, 255, 70);     // Endpoint A (cyan)
    private static final Color SAFE_A_OUTLINE = new Color(0, 200, 255, 240);
    private static final Color SAFE_B_FILL = new Color(0, 200, 255, 70);     // Endpoint B (cyan)
    private static final Color SAFE_B_OUTLINE = new Color(0, 200, 255, 240);
    private static final Color TARGET_FILL = new Color(0, 255, 0, 100);       // Current destination (green)
    private static final Color TARGET_OUTLINE = new Color(0, 255, 0, 255);

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

            // 1. Draw all 12 tiles of the current safe lane corridor
            List<int[]> lane = CustomGauntletScript.getActiveLaneTiles();
            if (lane != null) {
                for (int[] t : lane) {
                    drawTile(client, graphics, (t[0] << 8) | t[1], LANE_FILL, LANE_OUTLINE);
                }
            }

            // 2. Draw Safe Endpoint A
            int[] safeA = CustomGauntletScript.getSafeLaneTileA();
            if (safeA != null) {
                drawTileWithText(client, graphics, safeA, SAFE_A_FILL, SAFE_A_OUTLINE, "Safe A");
            }

            // 3. Draw Safe Endpoint B
            int[] safeB = CustomGauntletScript.getSafeLaneTileB();
            if (safeB != null) {
                drawTileWithText(client, graphics, safeB, SAFE_B_FILL, SAFE_B_OUTLINE, "Safe B");
            }

            // 4. Highlight the active target in bright green
            int[] target = CustomGauntletScript.getCurrentShuttleTarget();
            if (target != null) {
                drawTileWithText(client, graphics, target, TARGET_FILL, TARGET_OUTLINE, "Target");
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

    private void drawTileWithText(Client client, Graphics2D graphics, int[] sceneTile, Color fill, Color outline, String text) {
        LocalPoint lp = LocalPoint.fromScene(sceneTile[0], sceneTile[1]);
        if (lp == null) return;
        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly == null) return;
        graphics.setColor(fill);
        graphics.fill(poly);
        graphics.setColor(outline);
        graphics.draw(poly);
        if (text != null && !text.isEmpty()) {
            net.runelite.api.Point txt = Perspective.getCanvasTextLocation(client, graphics, lp, text, 0);
            if (txt != null) {
                graphics.setColor(Color.WHITE);
                graphics.drawString(text, txt.getX(), txt.getY());
            }
        }
    }
}

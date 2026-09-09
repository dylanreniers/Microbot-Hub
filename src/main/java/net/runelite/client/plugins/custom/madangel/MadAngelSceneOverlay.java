package net.runelite.client.plugins.custom.madangel;

import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.madangel.actions.MadAngelContext;
import net.runelite.client.plugins.custom.madangel.actions.MadAngelHelpers;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
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
 * Draws the computed sweep-dodge tiles on the game scene so the geometry can be eyeballed. It renders the
 * exact same {@link MadAngelHelpers.DodgeSpots} the plan log reports: the four side-centres of the 3x3
 * boss (faint), the player-relative LEFT (blue) and RIGHT (orange) tiles, and — while a sweep is in
 * progress — a bright green highlight on the tile the current cleave's animation says is safe. If these
 * tiles don't sit where you'd expect around the boss, the world-coordinate assumption is wrong (rotated
 * instance) and we switch to local coords before wiring any movement.
 */
public class MadAngelSceneOverlay extends Overlay {

    private static final Color SIDE_FAINT = new Color(255, 255, 255, 35);
    private static final Color LEFT_COLOR = new Color(40, 140, 255, 90);
    private static final Color RIGHT_COLOR = new Color(255, 140, 0, 90);
    private static final Color GO_COLOR = new Color(0, 255, 0, 150);

    private final MadAngelPlugin plugin;

    @Inject
    MadAngelSceneOverlay(MadAngelPlugin plugin) {
        super(plugin);
        this.plugin = plugin;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            Client client = Microbot.getClient();
            MadAngelContext ctx = plugin.getContext();
            Player local = client.getLocalPlayer();
            if (local == null) {
                return null;
            }
            // Prefer the locked target, but fall back to the nearest visible Mad Angel so the tiles show
            // as soon as the boss is on screen (before the fight starts) — useful for eyeballing geometry.
            Rs2NpcModel target = ctx.getCurrentTarget();
            NPC angel = target != null ? target.getNpc() : null;
            if (angel == null) {
                Rs2NpcModel nearest = Microbot.getRs2NpcCache().query()
                        .withName(MadAngelHelpers.ANGEL_NAME).nearest();
                angel = nearest != null ? nearest.getNpc() : null;
            }
            if (angel == null) {
                return null;
            }
            MadAngelHelpers.DodgeSpots spots = MadAngelHelpers.computeDodgeSpots(client, angel);
            if (spots == null) {
                return null;
            }

            // Draw each of the four side-centres exactly once. The two that are currently to the player's
            // LEFT/RIGHT are coloured and their label gets the role appended, so no tile is double-drawn.
            drawRole(client, graphics, spots.frontLp, "front", spots);
            drawRole(client, graphics, spots.backLp, "back", spots);
            drawRole(client, graphics, spots.leftLp, "boss-left", spots);
            drawRole(client, graphics, spots.rightLp, "boss-right", spots);

            // While a sweep is active, highlight the SAFE tile for the CURRENT cleave: the safe side
            // alternates each cleave, tracked by the current cleave animation. The sword side is the danger
            // side, so we dodge to the OPPOSITE side of the boss.
            if (ctx.isSweepActive() || ctx.isSweepPlanActive()) {
                MadAngelContext.Side sword = MadAngelHelpers.sweepSideFor(angel.getAnimation());
                if (sword == MadAngelContext.Side.LEFT) {
                    drawTile(client, graphics, spots.dodgeRightLp, GO_COLOR, "GO"); // sword left -> go right
                } else if (sword == MadAngelContext.Side.RIGHT) {
                    drawTile(client, graphics, spots.dodgeLeftLp, GO_COLOR, "GO");  // sword right -> go left
                }
            }
        } catch (Exception ignored) {
            // Overlay must never throw into the render loop.
        }
        return null;
    }

    /** Draws one side-centre tile, colouring/labelling it by whether it's the player's current LEFT/RIGHT. */
    private void drawRole(Client client, Graphics2D g, LocalPoint lp, String compass,
                          MadAngelHelpers.DodgeSpots spots) {
        if (lp == null) {
            return;
        }
        Color color = SIDE_FAINT;
        String label = compass;
        if (samePoint(lp, spots.dodgeLeftLp)) {
            color = LEFT_COLOR;
            label = compass + " = LEFT";
        } else if (samePoint(lp, spots.dodgeRightLp)) {
            color = RIGHT_COLOR;
            label = compass + " = RIGHT";
        }
        drawTile(client, g, lp, color, label);
    }

    private static boolean samePoint(LocalPoint a, LocalPoint b) {
        return a != null && b != null && a.getX() == b.getX() && a.getY() == b.getY();
    }

    /** Fills+outlines a scene tile and labels it. No-op if it's off-screen. */
    private void drawTile(Client client, Graphics2D g, LocalPoint lp, Color color, String label) {
        if (lp == null) {
            return;
        }
        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly == null) {
            return;
        }
        g.setColor(color);
        g.fill(poly);
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 230));
        g.setStroke(new BasicStroke(2f));
        g.draw(poly);
        if (label != null) {
            net.runelite.api.Point txt = Perspective.getCanvasTextLocation(client, g, lp, label, 0);
            if (txt != null) {
                g.setColor(Color.WHITE);
                g.drawString(label, txt.getX(), txt.getY());
            }
        }
    }
}

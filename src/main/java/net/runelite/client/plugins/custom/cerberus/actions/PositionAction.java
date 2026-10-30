package net.runelite.client.plugins.custom.cerberus.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.cerberus.constants.CerberusConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.function.Supplier;

/**
 * Before each kill (after arriving in the room, and after looting once she has respawned), stand directly
 * east or west of Cerberus — just outside her 5x5 body — so the summoned souls behind her are in view.
 * The side is picked once per room (the nearer walkable one) and reused for every kill there.
 */
@Slf4j
public class PositionAction implements CerberusAction {

    @Override
    public int order() {
        return 450;
    }

    @Override
    public String key() {
        return "position";
    }

    @Override
    public boolean needsExecution(CerberusState state) {
        CerberusContext ctx = state.context();
        return ctx.getPhase() == CerberusContext.Phase.FIGHT && !ctx.isFightActive() && !Rs2Player.isMoving();
    }

    @Override
    public Object execute(CerberusState state) {
        CerberusContext ctx = state.context();
        WorldPoint center = bossCenter();
        WorldPoint player = Rs2Player.getWorldLocation();
        if (center == null || player == null) {
            return null; // not respawned yet
        }
        if (ctx.getSideOffset() == null) {
            ctx.setSideOffset(pickSide(center, player));
            log.info("[cerberus] standing {} of Cerberus this room", ctx.getSideOffset() > 0 ? "east" : "west");
        }
        WorldPoint spot = center.dx(ctx.getSideOffset());
        if (player.equals(spot) || !state.claimClick()) {
            return spot;
        }
        log.info("[cerberus] moving beside Cerberus to {}", spot);
        Rs2Walker.walkFastCanvas(spot);
        return spot;
    }

    /** The nearer of the east/west spots, preferring one that is walkable. */
    private int pickSide(WorldPoint center, WorldPoint player) {
        int offset = CerberusConstants.SIDE_SPOT_OFFSET;
        WorldPoint east = center.dx(offset);
        WorldPoint west = center.dx(-offset);
        boolean eastOk = Rs2Tile.isWalkable(east);
        boolean westOk = Rs2Tile.isWalkable(west);
        if (eastOk != westOk) {
            return eastOk ? offset : -offset;
        }
        return player.distanceTo(east) <= player.distanceTo(west) ? offset : -offset;
    }

    /** Her centre tile: the local location of a 5x5 NPC is its middle (getWorldLocation is the SW corner). */
    private WorldPoint bossCenter() {
        return Microbot.getClientThread().invoke((Supplier<WorldPoint>) () -> {
            Rs2NpcModel boss = Microbot.getRs2NpcCache().query()
                    .withIds(CerberusConstants.CERBERUS_IDS).nearest(CerberusConstants.IN_LAIR_RADIUS);
            LocalPoint lp = boss == null ? null : boss.getLocalLocation();
            return lp == null ? null : WorldPoint.fromLocal(Microbot.getClient(), lp);
        });
    }
}

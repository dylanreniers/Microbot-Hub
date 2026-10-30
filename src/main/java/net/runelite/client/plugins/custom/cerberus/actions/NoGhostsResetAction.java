package net.runelite.client.plugins.custom.cerberus.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.cerberus.constants.CerberusConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.function.Supplier;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * No-ghosts reset: she dropped below 400 HP before her 14th attack, so walk back out over the entrance
 * flames (to where the winch dropped us). With nobody in the chamber she goes idle and heals to full; then
 * back to FIGHT, where {@link PositionAction} walks us beside her for a fresh, synced fight. Blocking, like
 * the restock trip. The protection prayers keep running from the plugin's handlers while we walk out.
 */
@Slf4j
public class NoGhostsResetAction implements CerberusAction {

    private static final int WALK_TIMEOUT_MS = 30_000;
    private static final int IDLE_TIMEOUT_MS = 20_000;
    /** Max tiles per minimap click (well inside the minimap's radius). */
    private static final int MINIMAP_STEP_TILES = 10;

    @Override
    public int order() {
        return 430;
    }

    @Override
    public String key() {
        return "no-ghosts-reset";
    }

    @Override
    public boolean needsExecution(CerberusState state) {
        return state.context().getPhase() == CerberusContext.Phase.RESETTING;
    }

    @Override
    public Object execute(CerberusState state) {
        CerberusContext ctx = state.context();
        WorldPoint exit = ctx.getLairEntrance() != null ? ctx.getLairEntrance() : estimatedEntrance();
        if (exit == null) {
            log.warn("[cerberus] no-ghosts reset: don't know where the entrance is — fighting on instead");
            ctx.setPhase(CerberusContext.Phase.FIGHT);
            return "no-entrance";
        }
        log.info("[cerberus] no-ghosts reset: walking out over the flames to {}", exit);
        // Minimap clicks in short steps, NOT Rs2Walker.walkTo: the web walker can't path through the flame
        // wall and picked the house tablet's "Outside" teleport instead, taking us out of the lair.
        long deadline = System.currentTimeMillis() + WALK_TIMEOUT_MS;
        while (ctx.isFightActive() && Rs2Player.getWorldLocation().distanceTo(exit) > 2
                && System.currentTimeMillis() < deadline) {
            WorldPoint here = Rs2Player.getWorldLocation();
            WorldPoint step = new WorldPoint(
                    here.getX() + clamp(exit.getX() - here.getX()),
                    here.getY() + clamp(exit.getY() - here.getY()),
                    here.getPlane());
            Rs2Walker.walkMiniMap(step);
            sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(step) <= 1 || !ctx.isFightActive(), 6000);
            sleep(300);
        }
        boolean reset = sleepUntil(() -> !ctx.isFightActive(), IDLE_TIMEOUT_MS);
        log.info("[cerberus] no-ghosts reset: {}", reset ? "she reset — heading back in" : "she didn't reset in time");
        ctx.setPhase(CerberusContext.Phase.FIGHT);
        return reset;
    }

    private static int clamp(int delta) {
        return Math.max(-MINIMAP_STEP_TILES, Math.min(MINIMAP_STEP_TILES, delta));
    }

    /** Plugin started in the room (no recorded winch drop): the entrance is ~25 tiles south of her. */
    private WorldPoint estimatedEntrance() {
        WorldPoint center = Microbot.getClientThread().invoke((Supplier<WorldPoint>) () -> {
            Rs2NpcModel boss = Microbot.getRs2NpcCache().query()
                    .withIds(CerberusConstants.CERBERUS_IDS).nearest(CerberusConstants.IN_LAIR_RADIUS);
            LocalPoint lp = boss == null ? null : boss.getLocalLocation();
            return lp == null ? null : WorldPoint.fromLocal(Microbot.getClient(), lp);
        });
        return center == null ? null : center.dy(-CerberusConstants.LAIR_ENTRANCE_TO_BOSS_TILES);
    }
}

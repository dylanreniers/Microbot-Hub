package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.abyssalsire.constants.SireConstants;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.Set;

/**
 * Phase 3: the Sire is below 50% HP. Melee it under Protect from Missiles (handled by
 * {@link PrayerAction}) and dodge the miasma / explosion:
 *
 * <ul>
 *   <li><b>Explosion knockback</b> (player animation 1816, latched as {@code exploding}): spam-click
 *       to the escape tile {@link SireConstants#PHASE3_ESCAPE_A} — or {@link SireConstants#PHASE3_ESCAPE_B}
 *       if a pool is on A — and hold fire until the Sire recovers (id 5908 clears {@code exploding}).</li>
 *   <li><b>Miasma pool</b> otherwise: toggle between {@link SireConstants#PHASE3_TILE_A} and
 *       {@link SireConstants#PHASE3_TILE_B} (if on A move to B, else move to A), then attack.</li>
 *   <li>No hazard: attack the Sire.</li>
 * </ul>
 * Ends when the Sire dies (death animation -> DEAD -> LootAction).
 */
@Slf4j
public class Phase3Action implements SireAction {

    @Override
    public int order() {
        return 620;
    }

    @Override
    public String key() {
        return "phase3";
    }

    @Override
    public boolean needsExecution(SireState state) {
        return state.context().getPhase() == SirePhase.PHASE3;
    }

    @Override
    public Object execute(SireState state) {
        SireContext ctx = state.context();
        Set<WorldPoint> pools = SireHelpers.miasmaPools(ctx);

        // 1) Explosion knockback: run to the escape tile and hold fire until the Sire recovers.
        if (ctx.isExploding()) {
            log.info("[sire] explosion dodge activated");
            WorldPoint escape = SireHelpers.miasmaOn(pools, SireConstants.PHASE3_ESCAPE_A)
                    ? SireConstants.PHASE3_ESCAPE_B
                    : SireConstants.PHASE3_ESCAPE_A;
            boolean arrived = spamWalkTo(escape);
            ctx.setAttackAfterMove(true); // re-attack once we recover; we've moved off the Sire
            return arrived ? "explosion-wait-recover" : "explosion-run";
        }

        // Hold combat until melee gear is on.
        if (!SireHelpers.gearReady(ctx)) {
            return "gear-wait";
        }

        // 2) Miasma dance: ONLY move when a pool spawns underneath us — then step to the other dance
        // tile (from A -> B, or from B / anywhere else -> A, i.e. back to the original dance tile). We
        // don't move for a pool on a tile we're not on, so we never walk into one.
        if (SireHelpers.miasmaOn(pools, SireHelpers.playerLocation())) {
            WorldPoint pos = SireHelpers.playerLocation();
            WorldPoint dodge = pos != null && pos.equals(SireConstants.PHASE3_TILE_A)
                    ? SireConstants.PHASE3_TILE_B
                    : SireConstants.PHASE3_TILE_A;
            if (!SireHelpers.atTile(dodge)) {
                SireHelpers.walkTo(dodge);
                ctx.setAttackAfterMove(true); // re-attack once we arrive; the walk broke the interaction
                return "miasma-dodge";
            }
        }

        // 3) Ate/drank this tick: don't also click attack (menu actions collide, and the consume broke
        // the interaction). Hold this tick and re-attack next tick.
        if (SireHelpers.consumedThisTick(state)) {
            ctx.setAttackAfterMove(true);
            return "consumed-hold";
        }

        // 4) Just arrived from a dodge/explosion (or a consume last tick): force a fresh attack
        // (getInteracting() lingers stale).
        if (ctx.isAttackAfterMove()) {
            log.info("[sire] forcing attack after explosion");
            ctx.setAttackAfterMove(false);
            SireHelpers.forceAttackSire();
            return "attack-after-move";
        }

        // 5) No hazard: attack.
        SireHelpers.attackSire();
        return "attack";
    }

    /** Click toward {@code tile} every tick even while already moving — "spam click" from the explosion.
     *  Returns true once we're standing on it. */
    private boolean spamWalkTo(WorldPoint tile) {
        if (SireHelpers.atTile(tile)) {
            return true;
        }
        Rs2Walker.walkFastCanvas(tile, true);
        return false;
    }
}

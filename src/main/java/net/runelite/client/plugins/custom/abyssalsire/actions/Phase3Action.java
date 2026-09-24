package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.abyssalsire.constants.SireConstants;

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
        // Stand down while a restock trip is armed/running (emergency escape or between-kills).
        return state.context().getPhase() == SirePhase.PHASE3 && !state.context().isPrepPending();
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
            boolean arrived = SireHelpers.spamWalkTo(escape);
            ctx.setAttackAfterMove(true); // re-attack once we recover; we've moved off the Sire
            return arrived ? "explosion-wait-recover" : "explosion-run";
        }

        // 2) Miasma dance (checked BEFORE the gear gate — a dodge must never wait on a gear swap): move
        // only when a pool spawns underneath us, and spam-walk to a CLEAR dance tile so we never step into
        // another pool (pools spawn frequently in phase 3). Prefer the opposite tile; fall back to
        // whichever of A/B is clear. Spam-walking (even while moving) reacts as fast as possible.
        if (SireHelpers.miasmaOn(pools, SireHelpers.playerLocation())) {
            SireHelpers.spamWalkTo(safeDanceTile(pools));
            ctx.setAttackAfterMove(true); // re-attack once we arrive; the walk broke the interaction
            return "miasma-dodge";
        }

        // Hold combat until melee gear is on.
        if (!SireHelpers.gearReady(ctx)) {
            return "gear-wait";
        }

        // 3) Keep attacking without stalling on eats: eating doesn't break an existing auto-attack, so if
        // we're already locked onto the Sire we keep going even on a tick we ate. Only hold (a tick) when
        // we still need a fresh attack CLICK and also consumed this tick — two menu actions would collide.
        if (ctx.isAttackAfterMove()) {
            if (SireHelpers.consumedThisTick(state)) {
                return "consumed-hold";
            }
            ctx.setAttackAfterMove(false);
            SireHelpers.forceAttackSire();
            return "attack-after-move";
        }
        if (SireHelpers.isAttackingSire()) {
            return "attack";
        }
        if (SireHelpers.consumedThisTick(state)) {
            ctx.setAttackAfterMove(true);
            return "consumed-hold";
        }
        SireHelpers.forceAttackSire();
        return "attack";
    }

    /**
     * Pick a dance tile to flee to when a pool is under us: the tile OPPOSITE our current one if it's
     * clear, otherwise whichever of A/B is clear. If both are somehow pooled, fall back to the opposite
     * tile so we at least move off the pool we're standing in.
     */
    private WorldPoint safeDanceTile(Set<WorldPoint> pools) {
        WorldPoint pos = SireHelpers.playerLocation();
        boolean onA = pos != null && pos.equals(SireConstants.PHASE3_TILE_A);
        WorldPoint opposite = onA ? SireConstants.PHASE3_TILE_B : SireConstants.PHASE3_TILE_A;
        if (!SireHelpers.miasmaOn(pools, opposite)) {
            return opposite;
        }
        if (!SireHelpers.miasmaOn(pools, SireConstants.PHASE3_TILE_A)) {
            return SireConstants.PHASE3_TILE_A;
        }
        if (!SireHelpers.miasmaOn(pools, SireConstants.PHASE3_TILE_B)) {
            return SireConstants.PHASE3_TILE_B;
        }
        return opposite; // both pooled — still move off our current pool
    }
}

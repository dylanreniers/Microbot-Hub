package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.abyssalsire.constants.SireConstants;

import java.util.Set;

/**
 * Phase 2: melee the standing Sire under Protect from Melee (handled by {@link PrayerAction}) from the
 * original spot. When a miasma pool lands on the original spot, step two tiles east; return to the
 * original spot only once that pool has DESPAWNED (tracked via the graphics-object lifecycle, so it
 * won't flip back while the pool is still down). Being back on the original spot keeps the Sire's
 * pathing clean when it starts moving to prep phase 3. Ends when the Sire drops to 50% HP (-> phase 3).
 */
@Slf4j
public class Phase2Action implements SireAction {

    @Override
    public int order() {
        return 610;
    }

    @Override
    public String key() {
        return "phase2";
    }

    @Override
    public boolean needsExecution(SireState state) {
        return state.context().getPhase() == SirePhase.PHASE2;
    }

    @Override
    public Object execute(SireState state) {
        if (!SireHelpers.gearReady(state.context())) {
            return "gear-wait";
        }

        SireContext ctx = state.context();
        Set<WorldPoint> pools = SireHelpers.miasmaPools(ctx);

        // Dodge two tiles east while a pool sits on the original spot; return to the original spot only
        // once that pool has DESPAWNED. The pools are event-tracked (spawn -> despawn), so this no
        // longer flips back to the original spot while the pool is still down. Being on the original
        // spot keeps the Sire's pathing clean when it starts moving to prep phase 3.
        WorldPoint desired = SireHelpers.miasmaOn(pools, SireConstants.ORIGINAL_POSITION)
                ? SireConstants.PHASE2_MIASMA_DODGE
                : SireConstants.ORIGINAL_POSITION;

        // Accept being exactly on the tile OR one tile south of it (same X): the Sire sometimes nudges
        // us a tile south, which is still a fine spot, so we don't want to keep repositioning for it.
        if (!atTileOrSouth(desired)) {
            SireHelpers.walkTo(desired);
            ctx.setAttackAfterMove(true); // re-attack once we arrive; the walk broke the interaction
            return "reposition";
        }

        // Ate/drank this tick: don't also click attack (menu actions collide, and the consume broke the
        // interaction). Hold this tick and re-attack next tick.
        if (SireHelpers.consumedThisTick(state)) {
            ctx.setAttackAfterMove(true);
            return "consumed-hold";
        }

        // Just arrived from a dodge/reposition (or a consume last tick): force a fresh attack
        // (getInteracting() lingers stale).
        if (ctx.isAttackAfterMove()) {
            ctx.setAttackAfterMove(false);
            SireHelpers.forceAttackSire();
            return "attack-after-move";
        }

        // On our tile — attack. From the original spot the (large) Sire is in reach, so this doesn't
        // drag us off it.
        SireHelpers.attackSire();
        return "attack";
    }

    /** True if we're exactly on {@code tile} or one tile south of it (same X, same plane). */
    private boolean atTileOrSouth(WorldPoint tile) {
        WorldPoint pos = SireHelpers.playerLocation();
        return pos != null
                && pos.getPlane() == tile.getPlane()
                && pos.getX() == tile.getX()
                && (pos.getY() == tile.getY() || pos.getY() == tile.getY() - 1);
    }
}

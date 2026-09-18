package net.runelite.client.plugins.custom.abyssalsire.actions;

import net.runelite.api.Actor;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.abyssalsire.constants.SireConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Stateless combat/query helpers shared by more than one Abyssal Sire action. Single-use helpers
 *  live as private methods in the action that needs them. */
final class SireHelpers {

    private SireHelpers() {
    }

    // ---- Gear ----

    /** The setup the current phase wants: range for phase 1 (barrage + respiratory), melee otherwise. */
    static Rs2InventorySetup desiredSetup(SireContext ctx) {
        switch (ctx.getPhase()) {
            case PHASE1:
                return ctx.getRangeSetup();
            case PHASE2:
            case PHASE3:
                return ctx.getMeleeSetup();
            default:
                return null;
        }
    }

    /** True when the current phase's gear is on (or there's no phase/setup); combat holds until then. */
    static boolean gearReady(SireContext ctx) {
        Rs2InventorySetup setup = desiredSetup(ctx);
        return setup == null || setup.doesEquipmentMatch();
    }

    // ---- Sire ----

    /** Attack the Sire if we aren't already interacting with it. Uses the invoke+query+interact pattern. */
    static void attackSire() {
        Actor interacting = Rs2Player.getInteracting();
        if (interacting != null && SireConstants.SIRE_NAME.equalsIgnoreCase(interacting.getName())) {
            return;
        }
        forceAttackSire();
    }

    /** Click Attack on the Sire unconditionally — used right after a reposition, where the interaction
     *  was broken by the walk but {@link Rs2Player#getInteracting()} can still read stale as the Sire. */
    static void forceAttackSire() {
        Microbot.getClientThread().invoke(
                () -> Microbot.getRs2NpcCache().query().withName(SireConstants.SIRE_NAME).interact("Attack"));
    }

    /** True if a consuming action (eat / prayer potion / antidote) fired its click this tick. Attacking
     *  on the same tick would clobber that menu action, and the consume also breaks the interaction —
     *  so callers should hold their attack and re-issue it next tick. */
    static boolean consumedThisTick(SireState state) {
        return state.executed("eat") || state.executed("drink-prayer") || state.executed("anti-poison");
    }

    // ---- Miasma pools ----

    /**
     * Tiles that currently have an active miasma pool, from the event-tracked map on the context. Each
     * pool is pruned once its GraphicsObject finishes or leaves the live scene, so a tile stays
     * "pooled" from spawn until the pool actually despawns (no per-tick flicker). Pruned here on the
     * tick thread since {@code finished()} must be read on the client thread.
     */
    static Set<WorldPoint> miasmaPools(SireContext ctx) {
        Set<WorldPoint> pools = Microbot.getClientThread().invoke(() -> {
            // finished() is set once when the spot-anim is done (despawns next frame) — the clean
            // despawn signal. Region changes that could orphan an entry are covered by context.reset().
            ctx.getMiasmaPools().keySet().removeIf(go -> go == null || go.finished());
            return new HashSet<>(ctx.getMiasmaPools().values());
        });
        return pools == null ? Collections.emptySet() : pools;
    }

    static boolean miasmaOn(Set<WorldPoint> pools, WorldPoint tile) {
        return tile != null && pools.contains(tile);
    }

    // ---- Movement ----

    static WorldPoint playerLocation() {
        return Rs2Player.getWorldLocation();
    }

    static boolean atTile(WorldPoint tile) {
        WorldPoint pos = playerLocation();
        return pos != null && pos.equals(tile);
    }

    /** Walk toward {@code tile} if we're not on it and not already moving. Returns true if a step was issued. */
    static boolean walkTo(WorldPoint tile) {
        if (atTile(tile)) {
            return false;
        }
        if (!Rs2Player.isMoving()) {
            Rs2Walker.walkFastCanvas(tile, true);
            return true;
        }
        return false;
    }
}

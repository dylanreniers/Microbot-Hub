package net.runelite.client.plugins.custom.abyssalsire.actions;

import net.runelite.api.Actor;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.abyssalsire.constants.SireConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.poh.PohTeleports;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/** Stateless combat/query helpers shared by more than one Abyssal Sire action. Single-use helpers
 *  live as private methods in the action that needs them. */
final class SireHelpers {

    private static final String HOUSE_TABLET = "Teleport to House";
    private static final int TELEPORT_TIMEOUT_MS = 20_000;

    private SireHelpers() {
    }

    /** Break a "Teleport to House" tablet and wait until we're inside the POH. True if we made it (or
     *  were already there); false if there's no tablet or the teleport didn't fire. Shared by the
     *  between-kills restock and the out-of-food emergency escape. */
    static boolean teleportToHouse() {
        if (PohTeleports.isInHouse()) {
            return true;
        }
        if (!Rs2Inventory.hasItem(HOUSE_TABLET)) {
            return false;
        }
        Rs2Inventory.interact(HOUSE_TABLET, "Break");
        if (!sleepUntil(PohTeleports::isInHouse, TELEPORT_TIMEOUT_MS)) {
            return false;
        }
        sleep(600, 1000);
        return true;
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

    /** True if we're currently interacting with (attacking) the Sire. NOTE: lingers stale for a tick or
     *  two after a walk, so callers that just moved should force a fresh attack instead of trusting this. */
    static boolean isAttackingSire() {
        Actor interacting = Rs2Player.getInteracting();
        return interacting != null && SireConstants.SIRE_NAME.equalsIgnoreCase(interacting.getName());
    }

    /** Attack the Sire if we aren't already interacting with it. Uses the invoke+query+interact pattern. */
    static void attackSire() {
        if (isAttackingSire()) {
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

    /** True if a consuming action ACTUALLY consumed something this tick (food/prayer/antidote). We check
     *  each action's RESULT, not merely that it ran: {@code executed("eat")} is true whenever HP is below
     *  the eat threshold, but food is on a 3-tick cooldown, so most of those ticks no bite happens. Using
     *  the result means we only hold the attack on real consume ticks — otherwise, with minions keeping
     *  HP low in phase 3, every tick looked like a consume and we never hit the boss. Attacking on the
     *  same tick as a real consume would clobber the click, so callers hold just that one tick. */
    static boolean consumedThisTick(SireState state) {
        return Boolean.TRUE.equals(state.result("eat"))
                || Boolean.TRUE.equals(state.result("drink-prayer"))
                || Boolean.TRUE.equals(state.result("anti-poison"));
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

    /** True if a miasma pool is on the player's current tile (only meaningful in phases 2/3). Used to
     *  make the dodge preempt everything else — including eating — so we vacate the pool immediately. */
    static boolean standingInMiasma(SireContext ctx) {
        if (ctx.getPhase() != SirePhase.PHASE2 && ctx.getPhase() != SirePhase.PHASE3) {
            return false;
        }
        WorldPoint me = playerLocation();
        return me != null && miasmaPools(ctx).contains(me);
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

    /** Walk toward {@code tile} every tick, even while already moving — a "spam click" for urgent dodges
     *  (miasma / explosion) where we must react as fast as possible. Returns true once we're on it. */
    static boolean spamWalkTo(WorldPoint tile) {
        if (atTile(tile)) {
            return true;
        }
        Rs2Walker.walkFastCanvas(tile, true);
        return false;
    }
}

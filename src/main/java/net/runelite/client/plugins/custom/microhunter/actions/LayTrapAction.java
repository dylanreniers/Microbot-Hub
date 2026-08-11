package net.runelite.client.plugins.custom.microhunter.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lays a fresh box trap on an empty pattern tile — and this is where the old drift lived. The fix:
 * we NEVER lay based on "walk, then drop wherever we ended up". Instead, each tick we
 *
 * <ol>
 *   <li>pick the nearest empty pattern tile,</li>
 *   <li>if the player is standing EXACTLY on it, lay the trap,</li>
 *   <li>otherwise take one walk step toward it and stop — no lay this tick.</li>
 * </ol>
 *
 * A slow or blocked walk is now harmless: we simply don't lay and retry next tick. The trap can only
 * ever land on a frozen pattern tile, so the square/X can never degrade over time.
 *
 * <p>A just-laid tile is skipped for a short grace period ({@link #LAID_GRACE_MS}) because the trap
 * object takes a tick or two to appear in the cache; without this we could lay a second trap onto
 * the same tile before the first registers.
 */
@Slf4j
public class LayTrapAction implements HunterAction {

    private static final long LAID_GRACE_MS = 1800L;

    @Override
    public int order() {
        return 400;
    }

    @Override
    public String key() {
        return "lay-trap";
    }

    @Override
    public boolean needsExecution(HunterState state) {
        if (state.busy() || state.breakImminent()) {
            return false;
        }
        if (!Rs2Inventory.contains("Box trap")) {
            return false;
        }
        return !layableTiles(state).isEmpty();
    }

    @Override
    public Object execute(HunterState state) {
        WorldPoint self = Rs2Player.getWorldLocation();
        WorldPoint target = layableTiles(state).stream()
                .min(Comparator.comparingInt(t -> self == null ? 0 : self.distanceTo(t)))
                .orElse(null);
        if (target == null) {
            return "none";
        }

        // Only lay while standing exactly on the target tile — a box trap is always placed on the
        // player's current tile, so this is what guarantees on-pattern placement.
        if (target.equals(self)) {
            boolean laid = Rs2Inventory.interact("Box trap", "Lay");
            if (laid) {
                state.context().markLaid(target);
                log.info("Laying box trap on pattern tile {}.", target);
            }
            return laid ? "lay" : "lay-failed";
        }

        // Not there yet: take a step toward it (only if we're not already walking) and try again next
        // tick. Crucially, we do NOT lay from here.
        if (!Rs2Player.isMoving()) {
            log.info("Walking to empty pattern tile {} (currently at {}).", target, self);
            Rs2Walker.walkFastCanvas(target, true);
        }
        return "walking";
    }

    /** Empty pattern tiles we haven't just laid on (whose fresh trap may not be in the cache yet). */
    private List<WorldPoint> layableTiles(HunterState state) {
        return state.tiles().stream()
                .filter(t -> t.status() == TrapTile.Status.EMPTY)
                .map(TrapTile::tile)
                .filter(tile -> !state.context().justLaid(tile, LAID_GRACE_MS))
                .collect(Collectors.toList());
    }
}

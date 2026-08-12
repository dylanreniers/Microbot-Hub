package net.runelite.client.plugins.custom.microhunter.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * The 2-tick "knife &amp; logs" catch method, ported from the original (working) script. Unlike the
 * rest of the pipeline this action is deliberately blocking: the whole point is precise sub-tick
 * timing, so it runs the sequence with short sleeps. That is safe here because the pipeline dispatches
 * each tick to a worker thread with overlap protection ({@code AbstractScript.gameTick}), so the game
 * ticks that fire while this runs are simply skipped — we never stack or run on the client thread.
 *
 * <p>Sequence for a batch of caught traps (matches the old {@code handleTickManipulationState}):
 * <ol>
 *   <li>Walk onto the caught trap's tile.</li>
 *   <li>{@code check} it — collects the catch and empties the tile.</li>
 *   <li>For the FIRST trap of the batch only: knife-on-logs to burn the reset tick.</li>
 *   <li>Lay a fresh box trap on the same tile.</li>
 * </ol>
 * Every lay is guarded on actually standing on the tile first, so a missed walk can't place a trap
 * off-pattern — if we didn't arrive we skip and let the normal {@link LayTrapAction} recover it.
 */
@Slf4j
public class TickManipulationAction implements HunterAction {

    private static final int ARRIVE_TIMEOUT_MS = 3000;
    private static final int LAID_TIMEOUT_MS = 3000;

    @Override
    public int order() {
        return 250; // ahead of RestoreTrapAction/LayTrapAction so it owns caught traps when enabled
    }

    @Override
    public String key() {
        return "tick-manip";
    }

    @Override
    public boolean needsExecution(HunterState state) {
        if (state.busy() || state.breakImminent()) {
            return false;
        }
        return state.tickManipulation()
                && hasKnifeAndLogs()
                && !state.tilesWith(TrapTile.Status.CAUGHT).isEmpty();
    }

    @Override
    public Object execute(HunterState state) {
        WorldPoint self = Rs2Player.getWorldLocation();
        List<TrapTile> caught = new ArrayList<>(state.tilesWith(TrapTile.Status.CAUGHT));
        caught.sort(Comparator.comparingInt(t -> self == null ? 0 : self.distanceTo(t.tile())));

        int handled = 0;
        boolean firstOfBatch = true;
        for (TrapTile trap : caught) {
            final WorldPoint tile = trap.tile();

            Rs2Walker.walkFastCanvas(tile, true);
            boolean arrived = sleepUntil(() -> tile.equals(Rs2Player.getWorldLocation()), ARRIVE_TIMEOUT_MS);
            if (!arrived) {
                // Didn't make it onto the tile — don't collect/lay from the wrong spot (that's how the
                // old script drifted). Leave the trap for the next tick / LayTrapAction to recover.
                log.info("[tick] couldn't reach caught trap at {}; skipping this tick.", tile);
                continue;
            }

            // Collect the catch. This empties the tile and returns the box trap to the inventory.
            Rs2TileObjectModel box = trap.object();
            if (box != null && box.click("check")) {
                Rs2Inventory.waitForInventoryChanges(3000);
            }

            // Tick-manipulation trick: knife on logs to compress the reset. Only once per batch — the
            // fletch carries the timing so the following catches lay a tick faster too.
            if (firstOfBatch) {
                Rs2Inventory.interact("Knife", "Use");
                sleep(50, 150);
                Rs2Inventory.interact("Teak logs");
                sleepUntil(Rs2Player::isAnimating, 3000);
                firstOfBatch = false;
            }

            // Lay a fresh trap on this exact tile (we've confirmed we're standing on it). The box trap
            // leaving the inventory confirms the lay landed.
            if (Rs2Inventory.interact("Box trap", "Lay")) {
                state.context().markLaid(tile);
                Rs2Inventory.waitForInventoryChanges(LAID_TIMEOUT_MS);
                handled++;
            }
        }
        return "tick-manip:" + handled + "/" + caught.size();
    }

    private boolean hasKnifeAndLogs() {
        return Rs2Inventory.contains("Knife") && Rs2Inventory.contains("Teak logs");
    }
}

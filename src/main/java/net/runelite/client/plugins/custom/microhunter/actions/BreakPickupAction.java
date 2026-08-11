package net.runelite.client.plugins.custom.microhunter.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ahead of a BreakHandler break, pick up all of our traps so nothing is left shaking on the ground
 * while we're logged out / idle. Dismantles one trap per tick (game-driven interaction on the trap's
 * tile) until none of our pattern tiles hold a trap, then latches {@code pickedUpForBreak} so we
 * don't keep trying for the rest of the break window. The latch is cleared by the script once the
 * break window passes, so the next break picks up again.
 */
@Slf4j
public class BreakPickupAction implements HunterAction {

    /** Tiles that still hold one of our traps (anything but empty / a foreign object). */
    private static final Set<TrapTile.Status> OURS =
            EnumSet.of(TrapTile.Status.ARMED, TrapTile.Status.CAUGHT, TrapTile.Status.FALLEN);

    @Override
    public int order() {
        return 200; // ahead of restore/lay so we tear down instead of re-laying before a break
    }

    @Override
    public String key() {
        return "break-pickup";
    }

    @Override
    public boolean needsExecution(HunterState state) {
        if (!state.breakImminent() || state.context().isPickedUpForBreak()) {
            return false;
        }
        if (Rs2Player.isMoving()) {
            return false; // finish walking to the current trap first
        }
        return true;
    }

    @Override
    public Object execute(HunterState state) {
        WorldPoint self = Rs2Player.getWorldLocation();
        List<TrapTile> remaining = state.tiles().stream()
                .filter(t -> OURS.contains(t.status()))
                .collect(Collectors.toList());

        if (remaining.isEmpty()) {
            state.context().setPickedUpForBreak(true);
            log.info("All traps picked up; ready for break.");
            return "done";
        }

        TrapTile target = remaining.stream()
                .min(Comparator.comparingInt(t -> self == null ? 0 : self.distanceTo(t.tile())))
                .orElse(null);
        if (target == null || target.object() == null) {
            // A fallen ground item with no object to dismantle — grab it off the floor.
            if (target != null && target.item() != null) {
                target.item().pickup();
                return "pickup-item";
            }
            return "nothing";
        }

        Rs2TileObjectModel box = target.object();
        boolean acted = box.click("Dismantle") || box.click("Release") || box.click();
        if (acted) {
            log.info("Dismantling trap at {} before break.", target.tile());
        }
        return acted ? "dismantle" : "dismantle-failed";
    }
}

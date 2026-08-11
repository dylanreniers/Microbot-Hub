package net.runelite.client.plugins.custom.microhunter.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.Comparator;
import java.util.List;

/**
 * Restores traps that have caught something or collapsed. Both cases are handled by a single
 * game-driven interaction on the trap's OWN tile — the client walks us there and performs the reset
 * in place, so unlike the old lay-from-inventory path this can never drift: we act on the object
 * where it already is, never on "wherever the player ended up".
 *
 * <ul>
 *   <li>CAUGHT (shaking box): collect the catch; the game re-sets the trap on the same tile.</li>
 *   <li>FALLEN (collapsed trap object or box-trap ground item): re-lay it in place.</li>
 * </ul>
 *
 * One interaction per tick: if we're already walking to a trap we let that finish rather than
 * issuing a second click.
 */
@Slf4j
public class RestoreTrapAction implements HunterAction {

    @Override
    public int order() {
        return 300;
    }

    @Override
    public String key() {
        return "restore-trap";
    }

    @Override
    public boolean needsExecution(HunterState state) {
        if (state.busy() || state.breakImminent()) {
            return false;
        }
        // Let a game-driven walk to a trap complete before issuing another interaction.
        if (Rs2Player.isMoving()) {
            return false;
        }
        return !restorable(state).isEmpty();
    }

    @Override
    public Object execute(HunterState state) {
        WorldPoint self = Rs2Player.getWorldLocation();
        TrapTile target = restorable(state).stream()
                .min(Comparator.comparingInt(t -> self == null ? 0 : self.distanceTo(t.tile())))
                .orElse(null);
        if (target == null) {
            return "none";
        }

        if (target.status() == TrapTile.Status.CAUGHT) {
            return collect(target);
        }
        return relay(target);
    }

    /** Caught traps and collapsed traps (object or ground item) — everything that isn't armed/empty. */
    private List<TrapTile> restorable(HunterState state) {
        return state.tiles().stream()
                .filter(t -> t.status() == TrapTile.Status.CAUGHT
                        || t.status() == TrapTile.Status.FALLEN)
                .collect(java.util.stream.Collectors.toList());
    }

    private Object collect(TrapTile target) {
        Rs2TileObjectModel box = target.object();
        if (box == null) {
            return "no-object";
        }
        // "Reset" collects the catch and re-arms the trap in one action; fall back to the default
        // left-click if the option isn't present on this variant.
        boolean acted = box.click("Reset") || box.click();
        if (acted) {
            log.info("Collecting caught trap at {}.", target.tile());
        }
        return acted ? "collect" : "collect-failed";
    }

    private Object relay(TrapTile target) {
        Rs2TileObjectModel obj = target.object();
        if (obj != null) {
            // A collapsed trap object still on the tile — reset/lay it back.
            boolean acted = obj.click("Lay") || obj.click("Reset") || obj.click();
            if (acted) {
                log.info("Re-laying collapsed trap object at {}.", target.tile());
            }
            return acted ? "relay-object" : "relay-object-failed";
        }

        Rs2TileItemModel item = target.item();
        if (item != null) {
            // Collapsed box trap on the floor — "Lay" sets it back down on the same tile.
            boolean acted = item.click("Lay") || item.click();
            if (acted) {
                log.info("Re-laying fallen box trap at {}.", target.tile());
            }
            return acted ? "relay-item" : "relay-item-failed";
        }
        return "nothing-to-relay";
    }
}

package net.runelite.client.plugins.custom.microhunter.scripts;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.api.ObjectID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.actions.Action;
import net.runelite.client.plugins.custom.actions.ActionScript;
import net.runelite.client.plugins.custom.microhunter.ChinHunterConfig;
import net.runelite.client.plugins.custom.microhunter.actions.HunterAction;
import net.runelite.client.plugins.custom.microhunter.actions.HunterContext;
import net.runelite.client.plugins.custom.microhunter.actions.HunterState;
import net.runelite.client.plugins.custom.microhunter.actions.TrapTile;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.breakhandler.BreakHandlerScript;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * Action-driven box-trap hunter. The generic pipeline (discovery, ordered per-tick run) lives in
 * {@link ActionScript}; here we only build the per-tick {@link HunterState} — a snapshot of what sits
 * on each fixed pattern tile, resolved from the object/item caches — and hold the durable
 * {@link HunterContext} across ticks.
 *
 * <p>This replaces the previous sleep-driven state machine. The drift it suffered from is gone
 * because (a) traps are only ever laid while standing on a frozen pattern tile (see
 * {@code LayTrapAction}), and (b) we read state by querying OUR pattern tiles from the cache each
 * tick rather than from cross-thread event lists that also captured other players' boxes.
 */
@Slf4j
public class AutoChinScript extends ActionScript<HunterState> {

    /** The armed, waiting box trap. */
    private static final int ARMED_ID = ObjectID.BOX_TRAP_9380;

    /** Shaking boxes — a trap that has caught something. */
    private static final int[] CAUGHT_IDS = {
            ObjectID.SHAKING_BOX,
            ObjectID.SHAKING_BOX_9382,
            ObjectID.SHAKING_BOX_9383,
            ObjectID.SHAKING_BOX_9384
    };

    /** A collapsed/failed trap that is still an object on the tile. */
    private static final int FALLEN_OBJ_ID = ObjectID.BOX_TRAP_9385;

    /** Every trap object id we query the cache for each tick. */
    private static final int[] TRAP_OBJECT_IDS = {
            ARMED_ID,
            FALLEN_OBJ_ID,
            ObjectID.SHAKING_BOX,
            ObjectID.SHAKING_BOX_9382,
            ObjectID.SHAKING_BOX_9383,
            ObjectID.SHAKING_BOX_9384
    };

    @Inject
    private ChinHunterConfig config;

    private HunterContext context;

    @Override
    protected Class<? extends Action<HunterState>> actionType() {
        return HunterAction.class;
    }

    @Override
    protected void onInitialize() {
        context = new HunterContext();
    }

    @Override
    protected HunterState createState() {
        if (context == null) {
            return null;
        }

        boolean breakImminent = isBreakImminent();
        if (!breakImminent) {
            // Break window has passed — re-arm pickup for the next break.
            context.setPickedUpForBreak(false);
        }

        List<TrapTile> tiles = new ArrayList<>();
        if (context.isPatternInitialized()) {
            // Two client-thread hops per tick: all trap objects and all box-trap ground items in view,
            // matched back to our fixed tiles in plain Java. We only ever look at OUR pattern tiles, so
            // other players' traps are ignored by construction.
            List<Rs2TileObjectModel> objects = rs2TileObjectCache.query()
                    .withIds(TRAP_OBJECT_IDS)
                    .toListOnClientThread();
            List<Rs2TileItemModel> items = rs2TileItemCache.query()
                    .withId(ItemID.BOX_TRAP)
                    .toListOnClientThread();

            for (WorldPoint tile : context.getPatternTiles()) {
                Rs2TileObjectModel object = objects.stream()
                        .filter(o -> tile.equals(o.getWorldLocation()))
                        .findFirst().orElse(null);
                Rs2TileItemModel item = items.stream()
                        .filter(i -> tile.equals(i.getWorldLocation()))
                        .findFirst().orElse(null);
                tiles.add(new TrapTile(tile, classify(object, item), object, item));
            }
        }

        return new HunterState(context, tiles, breakImminent, config.tickManipulation());
    }

    private static TrapTile.Status classify(Rs2TileObjectModel object, Rs2TileItemModel item) {
        if (object != null) {
            int id = object.getId();
            if (id == ARMED_ID) {
                return TrapTile.Status.ARMED;
            }
            for (int caught : CAUGHT_IDS) {
                if (id == caught) {
                    return TrapTile.Status.CAUGHT;
                }
            }
            if (id == FALLEN_OBJ_ID) {
                return TrapTile.Status.FALLEN;
            }
            return TrapTile.Status.FOREIGN;
        }
        if (item != null) {
            return TrapTile.Status.FALLEN;
        }
        return TrapTile.Status.EMPTY;
    }

    /** True when a BreakHandler break is within a minute, so we tear down instead of tending. */
    private boolean isBreakImminent() {
        int secondsUntilBreak = BreakHandlerScript.breakIn;
        return secondsUntilBreak > 0 && secondsUntilBreak <= 60;
    }

    @Override
    public void onShutdown() {
        // Best-effort, NON-blocking teardown. onShutdown() runs on the client thread (plugin
        // shutDown), so we must not sleep/loop here or we'd freeze the client. We fire a single
        // dismantle pass for any traps still on our tiles; the breaks path (BreakPickupAction) is the
        // reliable, tick-driven pickup. A manual stop mid-session may leave a trap or two to collect.
        if (context != null && Microbot.isLoggedIn() && context.isPatternInitialized()) {
            rs2TileObjectCache.query()
                    .withIds(TRAP_OBJECT_IDS)
                    .where(o -> context.getPatternTiles().contains(o.getWorldLocation()))
                    .toListOnClientThread()
                    .forEach(trap -> {
                        if (!trap.click("Dismantle")) {
                            trap.click("Release");
                        }
                    });
        }
        if (context != null) {
            context.reset();
        }
    }
}

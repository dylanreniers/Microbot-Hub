package net.runelite.client.plugins.custom.microhunter.actions;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;

/**
 * A single pattern tile and what is currently sitting on it, resolved from the object/item caches
 * once per tick. The tile itself is fixed for the whole session (see {@link HunterContext}); only
 * the {@link Status} changes as traps catch, collapse and are re-laid. Actions read this snapshot
 * and interact with the exact {@link #object()} / {@link #item()} model when present.
 */
public class TrapTile {

    /** What occupies one of our pattern tiles right now. */
    public enum Status {
        /** Nothing there — a fresh box trap needs laying (only if it's one of ours to lay). */
        EMPTY,
        /** A set, waiting box trap. Nothing to do. */
        ARMED,
        /** A trap that caught something (shaking). Collect it in place; the game re-sets it. */
        CAUGHT,
        /** A collapsed/failed trap — an object or a ground item. Re-lay it in place. */
        FALLEN,
        /** A non-trap object (or another player's trap) blocking the tile. Leave it alone. */
        FOREIGN
    }

    private final WorldPoint tile;
    private final Status status;
    private final Rs2TileObjectModel object;
    private final Rs2TileItemModel item;

    public TrapTile(WorldPoint tile, Status status, Rs2TileObjectModel object, Rs2TileItemModel item) {
        this.tile = tile;
        this.status = status;
        this.object = object;
        this.item = item;
    }

    public WorldPoint tile() {
        return tile;
    }

    public Status status() {
        return status;
    }

    /** The trap object on this tile, or null if the tile is empty / only holds a ground item. */
    public Rs2TileObjectModel object() {
        return object;
    }

    /** The collapsed box-trap ground item on this tile, or null if none. */
    public Rs2TileItemModel item() {
        return item;
    }
}

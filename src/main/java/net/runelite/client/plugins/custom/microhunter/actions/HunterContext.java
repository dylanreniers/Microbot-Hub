package net.runelite.client.plugins.custom.microhunter.actions;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.coords.WorldPoint;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Durable, cross-tick state for the box-trap hunter. The core of the drift fix lives here: the
 * {@link #patternTiles} are computed ONCE from the player's starting position and never move, so
 * every lay/restore is anchored to a fixed grid rather than to the (possibly stale/offset) location
 * of a trap object. A missed walk therefore can't accumulate — the tile is still the same fixed
 * tile next tick.
 *
 * <p>Fields touched only from the tick thread are plain; the collections are concurrent as a guard
 * in case a future event path writes to them.
 */
@Getter
@Setter
public class HunterContext {

    /** The fixed set of tiles our traps belong on. Set once via {@link #initPattern(List)}. */
    private final List<WorldPoint> patternTiles = new CopyOnWriteArrayList<>();

    /** Wall-clock of the last lay on each tile, so we don't try to lay a second trap before the
     *  freshly-laid object has registered in the cache (which lags a tick or two). */
    private final Map<WorldPoint, Long> recentlyLaid = new ConcurrentHashMap<>();

    /** True once the pattern has been established from the player's start position. */
    private volatile boolean patternInitialized;

    /** True once the traps have been dismantled ahead of an imminent break. Reset when the break
     *  window passes so the next break picks up again. */
    private volatile boolean pickedUpForBreak;

    public void initPattern(List<WorldPoint> tiles) {
        patternTiles.clear();
        patternTiles.addAll(tiles);
        recentlyLaid.clear();
        patternInitialized = true;
    }

    /** Records that we just laid a trap on {@code tile} (suppresses re-laying it for a short grace). */
    public void markLaid(WorldPoint tile) {
        recentlyLaid.put(tile, System.currentTimeMillis());
    }

    /** True if we laid on this tile within {@code windowMs}, i.e. the object may not have spawned yet. */
    public boolean justLaid(WorldPoint tile, long windowMs) {
        Long at = recentlyLaid.get(tile);
        return at != null && System.currentTimeMillis() - at < windowMs;
    }

    /** Clears everything so a fresh start rebuilds the pattern from the new position. */
    public void reset() {
        patternTiles.clear();
        recentlyLaid.clear();
        patternInitialized = false;
        pickedUpForBreak = false;
    }
}

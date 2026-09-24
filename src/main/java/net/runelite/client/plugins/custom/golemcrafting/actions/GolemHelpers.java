package net.runelite.client.plugins.custom.golemcrafting.actions;

import net.runelite.api.GameObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.golemcrafting.constants.GolemConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.Comparator;

/** Cross-action helpers for the golem-crafting pipeline (inventory maths, plinth discovery, capacity). */
final class GolemHelpers {

    private GolemHelpers() {
    }

    static int sunstoneCount() {
        return Rs2Inventory.count(GolemConstants.SUNSTONE);
    }

    static int coreCount() {
        return Rs2Inventory.count(GolemConstants.SUNSTONE_CORE);
    }

    /**
     * Drops any scroll box(es) in the inventory. They arrive as a random reward while mining and eat a
     * slot, which stops us reaching the 25-sunstone batch and leaves the miner stuck. Returns true if one
     * was dropped.
     */
    static boolean dropScrollBoxes() {
        return Rs2Inventory.dropAll(i -> i.getName() != null
                && i.getName().toLowerCase().contains("scroll box"));
    }

    static boolean hasGemBag() {
        return Rs2Inventory.hasItem(GolemConstants.GEM_BAG) || Rs2Inventory.hasItem(GolemConstants.GEM_BAG_OPEN);
    }

    static boolean hasFurPouch() {
        return Rs2Inventory.hasItem(GolemConstants.FUR_POUCH_OPEN) || Rs2Inventory.hasItem(GolemConstants.FUR_POUCH_CLOSED);
    }

    /** The player is mid-shape (carving animation) or walking — don't issue another interaction. */
    static boolean isBusy() {
        return Rs2Player.isAnimating() || Rs2Player.isMoving();
    }

    /**
     * Max golems whose sunstone fits the inventory this trip: 28 slots minus the always-carried tools
     * (chisel, hammer, fur pouch) and, if present, the gem bag. Each golem needs 5 sunstone slots at
     * the mining peak (4 bodies + 1 that becomes a core, a 1:1 slot swap when chiselled).
     */
    static int maxGolemsThatFit() {
        int reserved = 3 + (hasGemBag() ? 1 : 0); // chisel + hammer + pouch (+ gem bag)
        int freeForSunstone = 28 - reserved;
        return Math.max(1, freeForSunstone / GolemConstants.SUNSTONE_TOTAL_PER_GOLEM);
    }

    /**
     * The nearest plinth (to {@link GolemConstants#PLINTH_AREA}) whose live, imposter-resolved menu
     * offers {@code action}. Plinth base ids vary per slot and morph by state, so we match on the
     * action rather than the id. Runs on the client thread.
     */
    static GameObject nearestPlinthWithAction(String action) {
        return nearestPlinthWithAction(action, null);
    }

    /**
     * Nearest plinth offering {@code action}, preferring one whose tile isn't {@code exclude} (the plinth
     * we just finished) so consecutive golems alternate plinths instead of waiting for one to reset.
     * Falls back to the excluded plinth if it's the only option.
     */
    static GameObject nearestPlinthWithAction(String action, WorldPoint exclude) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            var matches = Rs2GameObject.getGameObjects().stream()
                    .filter(o -> Rs2GameObject.hasAction(o, action, false))
                    .sorted(Comparator.comparingInt(o -> o.getWorldLocation().distanceTo(GolemConstants.PLINTH_AREA)))
                    .collect(java.util.stream.Collectors.toList());
            if (matches.isEmpty()) {
                return null;
            }
            if (exclude != null) {
                for (GameObject o : matches) {
                    if (!exclude.equals(o.getWorldLocation())) {
                        return o;
                    }
                }
            }
            return matches.get(0);
        }).orElse(null);
    }

    // Cardinal offsets in counter-clockwise order: N, W, S, E.
    private static final int[][] CCW = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};

    /**
     * The four tiles to stand on to carve a golem, in the optimal order: carve counter-clockwise and
     * END on the golem's front (the side facing the crafting-area centre, {@code PLINTH_AREA}), so we're
     * already positioned to Insert-core. Derived from the two plinths the user verified:
     * (2596,2257) → E,N,W,S and (2595,2253) → W,S,E,N.
     */
    static WorldPoint[] carveOrder(WorldPoint plinth) {
        WorldPoint c = GolemConstants.PLINTH_AREA;
        int dx = c.getX() - plinth.getX();
        int dy = c.getY() - plinth.getY();
        // Front = cardinal pointing from the plinth toward the centre.
        int front;
        if (Math.abs(dy) >= Math.abs(dx)) {
            front = dy > 0 ? 0 : 2; // N : S
        } else {
            front = dx > 0 ? 3 : 1; // E : W
        }
        int start = (front + 1) % 4; // so the 4th (start+3) lands on front
        WorldPoint[] order = new WorldPoint[4];
        for (int i = 0; i < 4; i++) {
            int card = (start + i) % 4;
            order[i] = new WorldPoint(plinth.getX() + CCW[card][0], plinth.getY() + CCW[card][1], plinth.getPlane());
        }
        return order;
    }

    /** The sunstone rock object at {@code tile} (either minable variant), or null if none is there. */
    static net.runelite.api.TileObject rockAt(WorldPoint tile) {
        return Microbot.getClientThread().runOnClientThreadOptional(() ->
                Rs2GameObject.getGameObjects(o -> java.util.Arrays.stream(GolemConstants.SUNSTONE_ROCKS)
                                .anyMatch(id -> id == o.getId())).stream()
                        .filter(o -> tile.equals(o.getWorldLocation()))
                        .findFirst()
                        .map(o -> (net.runelite.api.TileObject) o)
                        .orElse(null)
        ).orElse(null);
    }

    /** True if the object at {@code tile} currently offers {@code action} (imposter-resolved). */
    static boolean plinthAtHasAction(WorldPoint tile, String action) {
        if (tile == null) {
            return false;
        }
        GameObject obj = Rs2GameObject.getGameObjects().stream()
                .filter(o -> tile.equals(o.getWorldLocation()))
                .findFirst()
                .orElse(null);
        return obj != null && Rs2GameObject.hasAction(obj, action, false);
    }
}

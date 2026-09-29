package net.runelite.client.plugins.custom.golemcrafting.actions;

import net.runelite.api.GameObject;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.golemcrafting.constants.GolemConstants;
import net.runelite.client.plugins.grounditems.GroundItem;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
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
     * Drops junk that piles up while mining and eats inventory slots. Scroll boxes are always dropped;
     * loose uncut gems are dropped only when NOT using a gem bag ({@code keepGems == false}) — with a gem
     * bag we keep every gem. A full inventory would otherwise stop us reaching the 25-sunstone batch.
     */
    static boolean dropMiningJunk(boolean keepGems) {
        return Rs2Inventory.dropAll(i -> {
            if (i.getName() == null) {
                return false;
            }
            String n = i.getName().toLowerCase();
            if (n.contains("scroll box")) {
                return true;
            }
            return !keepGems && n.contains("uncut");
        });
    }

    /** True if a hammer is usable: a regular hammer in the inventory, or an imcando hammer worn/held. */
    static boolean hasHammer() {
        return Rs2Inventory.hasItem(GolemConstants.HAMMER)
                || Rs2Inventory.hasItem(GolemConstants.IMCANDO_HAMMER)
                || Rs2Equipment.isWearing(GolemConstants.IMCANDO_HAMMER, GolemConstants.IMCANDO_HAMMER_OFFHAND);
    }

    /** Empties the gem bag into the open bank (deposits every stored gem). Handles open/closed bag ids. */
    static boolean emptyGemBag() {
        return gemBagAction(GolemConstants.ACTION_EMPTY);
    }

    /** Fills the gem bag with any loose uncut gems in the inventory (moves them into the bag). */
    static boolean fillGemBag() {
        return gemBagAction(GolemConstants.ACTION_FILL);
    }

    private static boolean gemBagAction(String action) {
        if (Rs2Inventory.hasItem(GolemConstants.GEM_BAG_OPEN)) {
            return Rs2Inventory.interact(GolemConstants.GEM_BAG_OPEN, action);
        }
        if (Rs2Inventory.hasItem(GolemConstants.GEM_BAG)) {
            return Rs2Inventory.interact(GolemConstants.GEM_BAG, action);
        }
        return false;
    }

    /** True if there's at least one loose uncut gem in the inventory (not yet stashed in the gem bag). */
    static boolean hasLooseUncut() {
        return Rs2Inventory.count(i -> i.getName() != null && i.getName().toLowerCase().contains("uncut")) > 0;
    }

    /** Only our own drops count — a personal reward (owned by us / private), never another player's. */
    private static boolean isOwn(GroundItem gi) {
        return gi.getOwnership() == TileItem.OWNERSHIP_SELF || gi.isPrivate();
    }

    /** True if {@code gi} is a drop we want: a Jeweller's chisel, or an uncut gem when {@code includeGems}. */
    private static boolean isLootTarget(GroundItem gi, boolean includeGems) {
        if (gi == null || gi.getName() == null) {
            return false;
        }
        String n = gi.getName();
        if (n.equalsIgnoreCase(GolemConstants.JEWELLERS_CHISEL_NAME)) {
            return true;
        }
        return includeGems && n.toLowerCase().startsWith("uncut ");
    }

    /**
     * True if one of OUR finished-golem drops is on the ground within loot range: a Jeweller's chisel
     * (always), or an uncut gem when {@code includeGems} (the gem-bag feature is on). Other players' drops
     * are ignored.
     */
    static boolean groundLootPresent(boolean includeGems) {
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me == null) {
            return false;
        }
        for (GroundItem gi : Rs2GroundItem.getGroundItems().values()) {
            if (isLootTarget(gi, includeGems) && isOwn(gi)
                    && gi.getLocation() != null && gi.getLocation().distanceTo(me) <= GolemConstants.LOOT_RANGE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Loots ONE of our own ground drops within range (a Jeweller's chisel first, else an uncut gem when
     * {@code includeGems}), and returns its name — or null if there was nothing to take or the inventory
     * is full (guarded so we never spam the "not enough inventory space" pickup).
     */
    static String lootOwnedOne(boolean includeGems) {
        if (Rs2Inventory.isFull()) {
            return null;
        }
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me == null) {
            return null;
        }
        GroundItem chisel = null;
        GroundItem gem = null;
        for (GroundItem gi : Rs2GroundItem.getGroundItems().values()) {
            if (!isOwn(gi) || gi.getLocation() == null || gi.getLocation().distanceTo(me) > GolemConstants.LOOT_RANGE) {
                continue;
            }
            String n = gi.getName();
            if (n == null) {
                continue;
            }
            if (chisel == null && n.equalsIgnoreCase(GolemConstants.JEWELLERS_CHISEL_NAME)) {
                chisel = gi;
            } else if (gem == null && includeGems && n.toLowerCase().startsWith("uncut ")) {
                gem = gi;
            }
        }
        GroundItem target = chisel != null ? chisel : gem;
        if (target != null && Rs2GroundItem.interact(target)) {
            return target.getName();
        }
        return null;
    }

    /** True if a chisel — regular OR jeweller's — is in the inventory. */
    static boolean hasChisel() {
        return Rs2Inventory.hasItem(GolemConstants.CHISEL) || Rs2Inventory.hasItem(GolemConstants.JEWELLERS_CHISEL);
    }

    /** The chisel to chisel with, preferring the (faster) jeweller's chisel when present. */
    static int chiselItemId() {
        return Rs2Inventory.hasItem(GolemConstants.JEWELLERS_CHISEL)
                ? GolemConstants.JEWELLERS_CHISEL : GolemConstants.CHISEL;
    }

    /** True when both chisels are held — the regular one is then redundant and should be banked. */
    static boolean hasRedundantChisel() {
        return Rs2Inventory.hasItem(GolemConstants.JEWELLERS_CHISEL) && Rs2Inventory.hasItem(GolemConstants.CHISEL);
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
        // Slots we keep out of the batch: chisel + fur pouch always; a hammer only if it's in the
        // inventory (an equipped imcando hammer frees that slot); the gem bag if carried.
        int reserved = 2; // chisel + fur pouch
        if (Rs2Inventory.hasItem(GolemConstants.HAMMER) || Rs2Inventory.hasItem(GolemConstants.IMCANDO_HAMMER)) {
            reserved += 1;
        }
        if (hasGemBag()) {
            reserved += 1;
        }
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

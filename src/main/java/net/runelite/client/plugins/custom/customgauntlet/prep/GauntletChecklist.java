package net.runelite.client.plugins.custom.customgauntlet.prep;

import lombok.Getter;
import lombok.Setter;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

/**
 * Tracks items collected, items remaining, total shards accumulated, and calculates
 * the progression score of unexplored/scouted rooms.
 */
@Getter
@Setter
public class GauntletChecklist {

    // Target counts
    public static final int TARGET_ORE = 7;
    public static final int TARGET_BARK = 7;
    public static final int TARGET_LINUM = 7;
    public static final int TARGET_LEAF = 3;
    public static final int TARGET_FISH_PHASE_1 = 16;       // required before Phase 2 can start
    public static final int TARGET_FISH_PHASE_1_SOFT = 12;  // soft cap: don't hog inventory with fish early...
    public static final int TARGET_FISH_TOTAL = 24;
    public static final int TARGET_TOTAL_SHARDS = 550;

    // Item names (matched exactly). Covers both Corrupted and Crystalline variants where they differ; the
    // plant/wood/fish resources share a name across both gauntlets. Exact matching matters: "Paddlefish"
    // (cooked) is a substring of "Raw paddlefish", so a non-exact match would conflate them.
    public static final String[] ORE_NAMES = {"Corrupted ore", "Crystal ore"};
    public static final String[] BARK_NAMES = {"Phren bark"};
    public static final String[] LINUM_NAMES = {"Linum tirinum"};
    public static final String[] LEAF_NAMES = {"Grym leaf"};
    public static final String[] RAW_FISH_NAMES = {"Raw paddlefish"};
    public static final String[] COOKED_FISH_NAMES = {"Paddlefish"};
    public static final String[] SHARD_NAMES = {"Corrupted shards", "Crystal shards"};
    public static final String[] WEAPON_FRAME_NAMES = {"Weapon frame"};
    public static final String[] ORB_NAMES = {"Corrupted orb", "Crystal orb"};
    public static final String[] BOWSTRING_NAMES = {"Corrupted bowstring", "Crystalline bowstring"};

    // Tracking
    private int shardsSpent = 0;
    private int bearsKilled = 0;
    private boolean killedDragon = false;
    private boolean killedDarkBeast = false;

    private int droppedFishAtBase = 0;

    /**
     * Summed quantity of inventory items matching any of {@code names} (exact). itemQuantity sums stack
     * quantity, which we need because ore/bark/shards stack into a single slot - a slot count would read 1.
     */
    private static int quantityOf(String... names) {
        int total = 0;
        for (String name : names) {
            total += Rs2Inventory.itemQuantity(name, true);
        }
        return total;
    }

    /** True if the inventory holds any item exactly matching one of {@code names}. */
    private static boolean hasAny(String... names) {
        for (String name : names) {
            if (Rs2Inventory.hasItem(name, true)) {
                return true;
            }
        }
        return false;
    }

    public int getOreCount() {
        return quantityOf(ORE_NAMES);
    }

    public int getBarkCount() {
        return quantityOf(BARK_NAMES);
    }

    public int getLinumCount() {
        return quantityOf(LINUM_NAMES);
    }

    public int getLeafCount() {
        return quantityOf(LEAF_NAMES);
    }

    public int getRawFishCount() {
        return quantityOf(RAW_FISH_NAMES);
    }

    public int getCookedFishCount() {
        return quantityOf(COOKED_FISH_NAMES);
    }

    public int getTotalFishCount() {
        return getRawFishCount() + getCookedFishCount() + droppedFishAtBase;
    }

    public int getWeaponFrameCount() {
        return quantityOf(WEAPON_FRAME_NAMES);
    }

    public int getInventoryShards() {
        return quantityOf(SHARD_NAMES);
    }

    public int getTotalShardsEarned() {
        return shardsSpent + getInventoryShards();
    }

    public boolean hasOrb() {
        return hasAny(ORB_NAMES);
    }

    public boolean hasBowstring() {
        return hasAny(BOWSTRING_NAMES);
    }

    public boolean hasBothWeaponComponents() {
        return hasOrb() && hasBowstring();
    }

    public int oreRemaining() {
        return Math.max(0, TARGET_ORE - getOreCount());
    }

    public int barkRemaining() {
        return Math.max(0, TARGET_BARK - getBarkCount());
    }

    public int linumRemaining() {
        return Math.max(0, TARGET_LINUM - getLinumCount());
    }

    public int leafRemaining() {
        return Math.max(0, TARGET_LEAF - getLeafCount());
    }

    public int phase1FishRemaining() {
        return Math.max(0, TARGET_FISH_PHASE_1 - getTotalFishCount());
    }

    /**
     * How many fish to actively gather right now. We hold off at the soft cap (12) to keep inventory room
     * for the other materials, and only go for the full 16 once a tool slot has freed up - i.e. we've hit
     * 7 bark or 7 ore (which drops the axe/pickaxe). Phase 2 still requires the full 16 (phase1FishRemaining).
     */
    public int phase1FishSeekTarget() {
        boolean slotFreed = getBarkCount() >= TARGET_BARK || getOreCount() >= TARGET_ORE;
        return slotFreed ? TARGET_FISH_PHASE_1 : TARGET_FISH_PHASE_1_SOFT;
    }

    public int phase1FishSeekRemaining() {
        return Math.max(0, phase1FishSeekTarget() - getTotalFishCount());
    }

    public int totalFishRemaining() {
        return Math.max(0, TARGET_FISH_TOTAL - getTotalFishCount());
    }

    public boolean isPhase1MaterialsComplete() {
        return getWeaponFrameCount() >= 1
                && oreRemaining() == 0
                && barkRemaining() == 0
                && linumRemaining() == 0
                && leafRemaining() == 0
                && phase1FishRemaining() == 0;
    }

    /**
     * Calculates the progression value of a room based on what is STILL NEEDED on the checklist.
     * E.g., a room with 3 Ore deposits is worth 0 points if we already have 7 Ore,
     * but worth high points if we still need Ore!
     */
    public int calculateRoomProgressionScore(GauntletRoom room) {
        if (room == null) return 0;
        int score = 0;

        // Score based on materials still needed
        score += Math.min(oreRemaining(), room.getOreNodes() * 3) * 20;
        score += Math.min(barkRemaining(), room.getPhrenRoots() * 3) * 20;
        score += Math.min(linumRemaining(), room.getLinumPlants() * 3) * 20;
        score += Math.min(leafRemaining(), room.getGrymRoots()) * 25;
        score += Math.min(phase1FishSeekRemaining(), room.getFishingSpots() * 3) * 15;

        // Weak mob for 1st weapon frame
        if (getWeaponFrameCount() == 0 && room.isHasWeakMob()) {
            score += 50;
        }

        // Demibosses
        if (room.getDemiboss() == GauntletRoom.DemibossType.CORRUPTED_DRAGON && !hasOrb()) {
            score += 150;
        } else if (room.getDemiboss() == GauntletRoom.DemibossType.CORRUPTED_DARK_BEAST && !hasBowstring()) {
            score += 150;
        } else if (room.getDemiboss() == GauntletRoom.DemibossType.CORRUPTED_BEAR && (hasOrb() ^ hasBowstring())) {
            // Bear fallback: valuable if we already have 1 component and need the other!
            score += 80;
        }

        return score;
    }
}

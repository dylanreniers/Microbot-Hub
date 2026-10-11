package net.runelite.client.plugins.custom.customgauntlet.prep;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

import java.util.Set;

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
    public static final int TARGET_FISH_PHASE_1 = 16;
    public static final int TARGET_FISH_TOTAL = 24;
    public static final int TARGET_TOTAL_SHARDS = 550;

    // Item IDs
    public static final Set<Integer> ORE_IDS = Set.of(23824, 23837, 23877);
    public static final Set<Integer> BARK_IDS = Set.of(23838, 23878);
    public static final Set<Integer> LINUM_IDS = Set.of(23836, 23876);
    public static final Set<Integer> LEAF_IDS = Set.of(23835, 23875);
    public static final Set<Integer> RAW_FISH_IDS = Set.of(23872);
    public static final Set<Integer> COOKED_FISH_IDS = Set.of(23874);
    public static final Set<Integer> SHARD_IDS = Set.of(23825, 23866);
    public static final Set<Integer> WEAPON_FRAME_IDS = Set.of(23834, 23841, 23871);
    public static final Set<Integer> ORB_IDS = Set.of(23868, 23870);
    public static final Set<Integer> BOWSTRING_IDS = Set.of(23869, 23871);

    // Tracking
    private int shardsSpent = 0;
    private int bearsKilled = 0;
    private boolean killedDragon = false;
    private boolean killedDarkBeast = false;

    private int droppedFishAtBase = 0;

    public int getOreCount() {
        return Rs2Inventory.count(ORE_IDS::contains);
    }

    public int getBarkCount() {
        return Rs2Inventory.count(BARK_IDS::contains);
    }

    public int getLinumCount() {
        return Rs2Inventory.count(LINUM_IDS::contains);
    }

    public int getLeafCount() {
        return Rs2Inventory.count(LEAF_IDS::contains);
    }

    public int getRawFishCount() {
        return Rs2Inventory.count(RAW_FISH_IDS::contains);
    }

    public int getCookedFishCount() {
        return Rs2Inventory.count(COOKED_FISH_IDS::contains);
    }

    public int getTotalFishCount() {
        return getRawFishCount() + getCookedFishCount() + droppedFishAtBase;
    }

    public int getWeaponFrameCount() {
        return Rs2Inventory.count(WEAPON_FRAME_IDS::contains);
    }

    public int getInventoryShards() {
        return Rs2Inventory.count(SHARD_IDS::contains);
    }

    public int getTotalShardsEarned() {
        return shardsSpent + getInventoryShards();
    }

    public boolean hasOrb() {
        return ORB_IDS.stream().anyMatch(Rs2Inventory::hasItem);
    }

    public boolean hasBowstring() {
        return BOWSTRING_IDS.stream().anyMatch(Rs2Inventory::hasItem);
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
        score += Math.min(phase1FishRemaining(), room.getFishingSpots() * 3) * 15;

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

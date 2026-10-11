package net.runelite.client.plugins.custom.customgauntlet.prep;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.coords.WorldPoint;

/**
 * Represents a single 16x16 room on the 7x7 Gauntlet dungeon grid.
 */
@Getter
@Setter
public class GauntletRoom {

    public enum RoomType {
        STARTING_BASE,
        HUNLLEF,
        NORMAL,
        OUTER_DEMIBOSS
    }

    public enum ExplorationState {
        UNEXPLORED,
        SCOUTED,    // Peeked through doorway
        EXPLORED    // Visited
    }

    public enum DemibossType {
        NONE,
        CORRUPTED_DRAGON,       // Magic orb
        CORRUPTED_DARK_BEAST,  // Range bowstring
        CORRUPTED_BEAR         // Melee spike
    }

    private final int gridX;
    private final int gridY;
    private final RoomType roomType;

    private ExplorationState state = ExplorationState.UNEXPLORED;
    private DemibossType demiboss = DemibossType.NONE;

    // Resource counts available in this room
    private int oreNodes = 0;
    private int phrenRoots = 0;
    private int linumPlants = 0;
    private int grymRoots = 0;
    private int fishingSpots = 0;

    private boolean hasWeakMob = false;   // Rat, bat, spider (1st weapon frame)
    private boolean hasMediumMob = false; // Scorpion, wolf, unicorn (extra shards)

    // Center tile of the room
    private WorldPoint centerPoint;

    public GauntletRoom(int gridX, int gridY) {
        this.gridX = gridX;
        this.gridY = gridY;

        if (gridX == 3 && gridY == 3) {
            this.roomType = RoomType.STARTING_BASE;
            this.state = ExplorationState.EXPLORED;
        } else if (isOuterDemibossLocation(gridX, gridY)) {
            this.roomType = RoomType.OUTER_DEMIBOSS;
        } else {
            this.roomType = RoomType.NORMAL;
        }
    }

    /**
     * In Gauntlet, demibosses only spawn in the 3 middle rooms along each outer edge.
     */
    public static boolean isOuterDemibossLocation(int x, int y) {
        // Outer ring edges (excluding the 4 extreme corners)
        boolean isTopOrBottom = (y == 0 || y == 6) && (x >= 2 && x <= 4);
        boolean isLeftOrRight = (x == 0 || x == 6) && (y >= 2 && y <= 4);
        return isTopOrBottom || isLeftOrRight;
    }

    public boolean hasDemiboss() {
        return demiboss != DemibossType.NONE;
    }
}

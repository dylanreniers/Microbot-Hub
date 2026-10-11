package net.runelite.client.plugins.custom.customgauntlet.prep;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the 7x7 Gauntlet dungeon room grid, tracks exploration states,
 * and provides pathing/selection queries.
 */
@Getter
@Setter
public class GauntletGrid {

    public static final int GRID_SIZE = 7;
    public static final int BASE_X = 3;
    public static final int BASE_Y = 3;
    public static final int ROOM_TILE_SIZE = 16;

    private final GauntletRoom[][] rooms = new GauntletRoom[GRID_SIZE][GRID_SIZE];
    private WorldPoint baseCenterPoint = null;

    public GauntletGrid() {
        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                rooms[x][y] = new GauntletRoom(x, y);
            }
        }
    }

    public GauntletRoom getRoom(int x, int y) {
        if (x < 0 || x >= GRID_SIZE || y < 0 || y >= GRID_SIZE) return null;
        return rooms[x][y];
    }

    public GauntletRoom getBaseRoom() {
        return rooms[BASE_X][BASE_Y];
    }

    /** Returns all adjacent orthogonal rooms (North, South, East, West). */
    public List<GauntletRoom> getAdjacentRooms(int x, int y) {
        List<GauntletRoom> adjacent = new ArrayList<>();
        int[] dx = {0, 0, -1, 1};
        int[] dy = {1, -1, 0, 0};

        for (int i = 0; i < 4; i++) {
            int nx = x + dx[i];
            int ny = y + dy[i];
            GauntletRoom r = getRoom(nx, ny);
            if (r != null) {
                adjacent.add(r);
            }
        }
        return adjacent;
    }

    /** Returns rooms adjacent to base (the initial scout ring). */
    public List<GauntletRoom> getBaseAdjacentRooms() {
        return getAdjacentRooms(BASE_X, BASE_Y);
    }

    /**
     * Converts a WorldPoint in the dungeon to the corresponding grid coordinate [x, y].
     */
    public int[] getGridCoordinates(WorldPoint point) {
        if (baseCenterPoint == null || point == null) return new int[]{BASE_X, BASE_Y};
        int diffX = point.getX() - baseCenterPoint.getX();
        int diffY = point.getY() - baseCenterPoint.getY();

        int gx = BASE_X + Math.round((float) diffX / ROOM_TILE_SIZE);
        int gy = BASE_Y + Math.round((float) diffY / ROOM_TILE_SIZE);

        gx = Math.max(0, Math.min(GRID_SIZE - 1, gx));
        gy = Math.max(0, Math.min(GRID_SIZE - 1, gy));
        return new int[]{gx, gy};
    }

    /** Finds the highest progression score room among currently scouted/accessible rooms. */
    public GauntletRoom getBestCandidateRoom(int currentX, int currentY, GauntletChecklist checklist) {
        GauntletRoom bestRoom = null;
        int bestScore = -1;

        // First check scouted rooms
        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                GauntletRoom r = rooms[x][y];
                if (r.getState() == GauntletRoom.ExplorationState.SCOUTED) {
                    int score = checklist.calculateRoomProgressionScore(r);
                    if (score > bestScore) {
                        bestScore = score;
                        bestRoom = r;
                    }
                }
            }
        }

        // If no scouted room offers score > 0, pick nearest unexplored room
        if (bestRoom == null || bestScore <= 0) {
            int shortestDist = Integer.MAX_VALUE;
            for (GauntletRoom adj : getAdjacentRooms(currentX, currentY)) {
                if (adj.getState() == GauntletRoom.ExplorationState.UNEXPLORED) {
                    int dist = Math.abs(adj.getGridX() - currentX) + Math.abs(adj.getGridY() - currentY);
                    if (dist < shortestDist) {
                        shortestDist = dist;
                        bestRoom = adj;
                    }
                }
            }
        }

        return bestRoom;
    }

    /** Finds known rooms containing a specific demiboss. */
    public List<GauntletRoom> getDemibossRooms(GauntletRoom.DemibossType type) {
        List<GauntletRoom> list = new ArrayList<>();
        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                if (rooms[x][y].getDemiboss() == type) {
                    list.add(rooms[x][y]);
                }
            }
        }
        return list;
    }

    /** Returns count of known Bear rooms (useful for the 2-bear fallback). */
    public int getKnownBearCount() {
        return getDemibossRooms(GauntletRoom.DemibossType.CORRUPTED_BEAR).size();
    }
}

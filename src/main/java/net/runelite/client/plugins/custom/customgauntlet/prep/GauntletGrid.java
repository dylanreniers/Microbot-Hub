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
    // The Hunllef room is the centre of the 7x7 maze; the starting base is one room beside it.
    public static final int HUNLLEF_X = 3;
    public static final int HUNLLEF_Y = 3;
    public static final int ROOM_TILE_SIZE = 16;

    private final GauntletRoom[][] rooms = new GauntletRoom[GRID_SIZE][GRID_SIZE];
    private WorldPoint baseCenterPoint = null;
    private WorldPoint hunllefCenterPoint = null; // null until we know which side of the base the Hunllef room is
    private int baseGridX = -1;
    private int baseGridY = -1;

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
        return getRoom(baseGridX, baseGridY);
    }

    /**
     * Places the Hunllef room one room from the base in direction (dirX, dirY) and puts it at the grid centre, so the
     * base sits on its opposite side.
     */
    public void setHunllefDirection(int dirX, int dirY) {
        hunllefCenterPoint = new WorldPoint(baseCenterPoint.getX() + dirX * ROOM_TILE_SIZE,
                baseCenterPoint.getY() + dirY * ROOM_TILE_SIZE, baseCenterPoint.getPlane());
        baseGridX = HUNLLEF_X - dirX;
        baseGridY = HUNLLEF_Y - dirY;
        GauntletRoom base = getBaseRoom();
        base.setRoomType(GauntletRoom.RoomType.STARTING_BASE);
        base.setState(GauntletRoom.ExplorationState.EXPLORED);
    }

    /** Ring of a room around the Hunllef room: 1 = the 8 rooms touching it (the base among them), 3 = the outer rim. */
    public static int ringOf(int gx, int gy) {
        return Math.max(Math.abs(gx - HUNLLEF_X), Math.abs(gy - HUNLLEF_Y));
    }

    /** All rooms in rings 1..ring (never the Hunllef room). */
    public List<GauntletRoom> getRoomsWithinRing(int ring) {
        List<GauntletRoom> list = new ArrayList<>();
        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                int r = ringOf(x, y);
                if (r >= 1 && r <= ring) list.add(rooms[x][y]);
            }
        }
        return list;
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

    /**
     * Grid coordinate [x, y] of an instance-coords WorldPoint ({@code WorldPoint.fromLocal}), measured from the Hunllef
     * room. Null while the Hunllef room isn't placed yet, or for a point outside the maze.
     */
    public int[] getGridCoordinates(WorldPoint point) {
        if (hunllefCenterPoint == null || point == null) return null;
        int gx = HUNLLEF_X + Math.round((float) (point.getX() - hunllefCenterPoint.getX()) / ROOM_TILE_SIZE);
        int gy = HUNLLEF_Y + Math.round((float) (point.getY() - hunllefCenterPoint.getY()) / ROOM_TILE_SIZE);
        if (gx < 0 || gx >= GRID_SIZE || gy < 0 || gy >= GRID_SIZE) return null;
        return new int[]{gx, gy};
    }

    /** Centre (instance coords) of a grid room, or null while the Hunllef room isn't placed. */
    public WorldPoint getRoomCenter(int gx, int gy) {
        if (hunllefCenterPoint == null) return null;
        return new WorldPoint(hunllefCenterPoint.getX() + (gx - HUNLLEF_X) * ROOM_TILE_SIZE,
                hunllefCenterPoint.getY() + (gy - HUNLLEF_Y) * ROOM_TILE_SIZE, hunllefCenterPoint.getPlane());
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

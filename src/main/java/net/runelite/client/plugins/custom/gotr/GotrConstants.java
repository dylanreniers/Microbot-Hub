package net.runelite.client.plugins.custom.gotr;

import com.google.common.collect.ImmutableList;
import net.runelite.api.ItemID;
import net.runelite.api.ObjectID;
import net.runelite.api.coords.WorldPoint;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Constants for the Guardians of the Rift minigame
 */
public final class GotrConstants {

    // Location constants
    public static final int GOTR_REGION_ID = 14484;
    public static final int OUTSIDE_BARRIER_Y = 9482;
    public static final int LARGE_MINE_X = 3637;
    public static final int HUGE_MINE_X = 3594;
    public static final WorldPoint LARGE_MINE_ENTRANCE = new WorldPoint(3632, 9503, 0);

    // Object IDs
    public static final int PORTAL_ID = ObjectID.PORTAL_43729;
    public static final int GREAT_GUARDIAN_ID = 11403;
    public static final int UNCHARGED_CELLS_ID = ObjectID.UNCHARGED_CELLS_43732;
    public static final int WORKBENCH_ID = ObjectID.WORKBENCH_43754;
    public static final int DEPOSIT_POOL_ID = ObjectID.DEPOSIT_POOL;
    public static final int BARRIER_PEEK_ID = ObjectID.BARRIER_43849;
    public static final int BARRIER_ENTER_ID = ObjectID.BARRIER_43700;
    public static final int CELL_TILE_BROKEN_ID = ObjectID.CELL_TILE_BROKEN;

    // Mining objects
    public static final int HUGE_GUARDIAN_REMAINS_ID = ObjectID.HUGE_GUARDIAN_REMAINS;
    public static final int LARGE_GUARDIAN_REMAINS_ID = ObjectID.LARGE_GUARDIAN_REMAINS;
    public static final int GUARDIAN_PARTS_ID = ObjectID.GUARDIAN_PARTS_43716;
    public static final int RUBBLE_ENTER_ID = ObjectID.RUBBLE_43724;
    public static final int RUBBLE_EXIT_ID = ObjectID.RUBBLE_43726;
    public static final int HUGE_MINE_EXIT_ID = 38044;

    // Widget IDs
    public static final int MINIGAME_WIDGET_ID = 48889857;
    public static final int TIMER_WIDGET_ID = 48889861;
    public static final int POWER_WIDGET_ID = 48889874;

    // Timer and delay constants
    public static final int GAME_START_THRESHOLD = 35;
    public static final int PORTAL_TIME_THRESHOLD = 85;
    public static final int GUARDIAN_POWER_THRESHOLD = 70;
    public static final int FIRST_PORTAL_TIME_ADJUSTMENT = 40;

    // Animation IDs
    public static final int PORTAL_ANIMATION_ID = 9363;

    // Item names
    public static final String GUARDIAN_FRAGMENTS = "guardian fragments";
    public static final String GUARDIAN_ESSENCE = "guardian essence";
    public static final String UNCHARGED_CELL = "Uncharged cell";
    public static final String PORTAL_TALISMAN = "portal talisman";
    public static final String GUARDIAN_STONE = "guardian stone";
    public static final String COLOSSAL_POUCH = "colossal pouch";
    public static final String PICKAXE = "pickaxe";

    // Rune IDs
    public static final List<Integer> RUNE_IDS = ImmutableList.of(
        ItemID.NATURE_RUNE,
        ItemID.LAW_RUNE,
        ItemID.BODY_RUNE,
        ItemID.DUST_RUNE,
        ItemID.LAVA_RUNE,
        ItemID.STEAM_RUNE,
        ItemID.SMOKE_RUNE,
        ItemID.SOUL_RUNE,
        ItemID.WATER_RUNE,
        ItemID.AIR_RUNE,
        ItemID.EARTH_RUNE,
        ItemID.FIRE_RUNE,
        ItemID.MIND_RUNE,
        ItemID.CHAOS_RUNE,
        ItemID.DEATH_RUNE,
        ItemID.BLOOD_RUNE,
        ItemID.COSMIC_RUNE,
        ItemID.ASTRAL_RUNE,
        ItemID.MIST_RUNE,
        ItemID.MUD_RUNE,
        ItemID.WRATH_RUNE
    );

    // Altar IDs for runecrafting
    public static final Integer[] RC_ALTAR_IDS = new Integer[]{
        ObjectID.ALTAR_34760, ObjectID.ALTAR_34761, ObjectID.ALTAR_34762, ObjectID.ALTAR_34763,
        ObjectID.ALTAR_34764, ObjectID.ALTAR_34765, ObjectID.ALTAR_34766, ObjectID.ALTAR_34767,
        ObjectID.ALTAR_34768, ObjectID.ALTAR_34769, ObjectID.ALTAR_34770, ObjectID.ALTAR_34771,
        ObjectID.ALTAR_34772, ObjectID.ALTAR_43479
    };

    // Portal IDs for leaving altars
    public static final Integer[] RC_PORTAL_IDS = new Integer[]{
        ObjectID.PORTAL_34748, ObjectID.PORTAL_34749, ObjectID.PORTAL_34750, ObjectID.PORTAL_34751,
        ObjectID.PORTAL_34752, ObjectID.PORTAL_34753, ObjectID.PORTAL_34754, ObjectID.PORTAL_34755,
        ObjectID.PORTAL_34756, ObjectID.PORTAL_34757, ObjectID.PORTAL_34758, ObjectID.PORTAL_34758,
        ObjectID.PORTAL_34759, ObjectID.PORTAL_43478
    };

    // Patterns for parsing
    public static final String REWARD_POINT_REGEX = "Total elemental energy:[^>]+>([\\d,]+).*Total catalytic energy:[^>]+>([\\d,]+).";
    public static final Pattern REWARD_POINT_PATTERN = Pattern.compile(REWARD_POINT_REGEX);
    public static final Pattern POWER_PERCENTAGE_PATTERN = Pattern.compile("(\\d+)%");

    // Skill requirements
    public static final int AGILITY_LEVEL_FOR_LARGE_MINE = 56;

    private GotrConstants() {
        // Utility class, prevent instantiation
    }
}

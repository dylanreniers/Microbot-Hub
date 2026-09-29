package net.runelite.client.plugins.custom.golemcrafting.constants;

import net.runelite.api.coords.WorldPoint;

/**
 * Static IDs, tiles and menu-action names for Golem crafting on Wyrmscraig. All verified live against
 * the running client (see the golem-crafting plugin notes): plinth base object ids vary per slot and
 * morph by build state, so plinths are located by their live menu actions ({@link #ACTION_START} /
 * {@link #ACTION_SHAPE} / {@link #ACTION_INSERT}) rather than by id.
 */
public final class GolemConstants {

    private GolemConstants() {
    }

    // --- Items ---
    public static final int SUNSTONE = 34020;
    public static final int SUNSTONE_CORE = 34022;
    public static final int CHISEL = 1755;
    public static final int JEWELLERS_CHISEL = 34024;
    public static final int HAMMER = 2347;
    public static final int IMCANDO_HAMMER = 25644;
    public static final int IMCANDO_HAMMER_OFFHAND = 29775;
    public static final int FUR_POUCH_CLOSED = 29303;
    public static final int FUR_POUCH_OPEN = 29470;
    public static final int DEFAULT_FUR = 10127; // Dashing kebbit fur
    public static final int GEM_BAG = 12020;
    public static final int GEM_BAG_OPEN = 24481;

    /** Uncut gems that a completed golem can reward (sapphire/emerald/ruby/diamond). */
    public static final int[] UNCUT_GEMS = {1623, 1621, 1619, 1617};
    public static final String JEWELLERS_CHISEL_NAME = "Jeweller's chisel";
    public static final String[] UNCUT_GEM_NAMES = {"Uncut sapphire", "Uncut emerald", "Uncut ruby", "Uncut diamond"};
    /** Range for looting a finished golem's drops. */
    public static final int LOOT_RANGE = 8;

    // --- Objects ---
    /** Both are minable "Sunstone rocks" variants — mine whichever is nearest to preserve momentum. */
    public static final int[] SUNSTONE_ROCKS = {62393, 62394};
    public static final int SUNSTONE_MONOLITH = 62216;
    public static final int BANK_CHEST = 62390;

    // --- Tiles ---
    public static final WorldPoint BANK_CHEST_TILE = new WorldPoint(2588, 2259, 0);
    public static final WorldPoint PLINTH_AREA = new WorldPoint(2596, 2255, 0);
    public static final WorldPoint ROCK_AREA = new WorldPoint(2601, 2243, 0);
    /** Stand here to start momentum mining — three rocks are adjacent. */
    public static final WorldPoint MINING_ANCHOR = new WorldPoint(2602, 2244, 0);

    /**
     * Fixed momentum-mining rotation: mine these rock tiles in order, advancing to the next the instant
     * a sunstone is obtained (XP drop). Cycled continuously; ~5 passes fills a 25-sunstone inventory.
     */
    public static final WorldPoint[] ROCK_ROTATION = {
            new WorldPoint(2602, 2245, 0),
            new WorldPoint(2603, 2244, 0),
            new WorldPoint(2602, 2243, 0),
            new WorldPoint(2601, 2242, 0),
            new WorldPoint(2599, 2244, 0),
    };

    // --- Menu actions ---
    public static final String ACTION_START = "Start-golem";
    public static final String ACTION_SHAPE = "Shape-golem";
    public static final String ACTION_INSERT = "Insert-core";
    public static final String ACTION_MINE = "Mine";
    public static final String ACTION_FILL = "Fill";
    public static final String ACTION_EMPTY = "Empty";
    public static final String ACTION_CHECK = "Check";
    public static final String ACTION_OPEN = "Open";

    // --- Animations ---
    public static final int ANIM_SHAPE = 14459;

    // --- Recipe maths (per golem: 4 sunstone body + 1 sunstone chiselled into a core + 1 fur) ---
    public static final int SUNSTONE_PER_GOLEM = 4;
    public static final int SUNSTONE_TOTAL_PER_GOLEM = 5; // body + one that becomes a core
}

package net.runelite.client.plugins.custom.cerberus.constants;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

/** Cerberus ids and timings. Ids/timings not on the wiki were supplied from in-game observation. */
public final class CerberusConstants {

    private CerberusConstants() {
    }

    /** The only Cerberus id that can be attacked; attacks are only counted while she has this id. */
    public static final int CERBERUS_ATTACKABLE_ID = 5862;

    // --- Summoned souls ---
    public static final int SOUL_RANGED_ID = 5867;
    public static final int SOUL_MAGIC_ID = 5868;
    public static final int SOUL_MELEE_ID = 5869;
    /** Ticks after the souls spawn at which each soul attacks, in west -> east order. */
    public static final int[] SOUL_ATTACK_OFFSETS = {12, 14, 16};
    /** Switch this many ticks before each soul attacks: a click in the event handler showed as active only
     *  two ticks later in the logs (the click waits on the shared input lock). */
    public static final int SOUL_PRAYER_LEAD_TICKS = 2;

    // --- Attack counting ---
    /** Her melee bite (also the triple's third animation); counted on start since it has no projectile. */
    public static final int MELEE_ATTACK_ANIMATION = 4491;
    /** The lava spit ("Grrrr"); counted on start — its fireballs aren't the magic/ranged projectiles. */
    public static final int LAVA_ATTACK_ANIMATION = 4493;
    /** Counting events closer together than this are the same attack. Kept small: magic/ranged are counted
     *  when their projectile appears (1-3 ticks after launch) but melee on its animation, so a magic attack
     *  followed 6 ticks later by a bite can be counted only 3 ticks apart. */
    public static final int MIN_ATTACK_GAP_TICKS = 2;
    /** Max distance from her (south-west) tile to a projectile's launch tile when the client gives no source actor. */
    public static final int BOSS_PROJECTILE_SOURCE_RADIUS = 6;

    // --- Triple attack (magic -> ranged -> melee) ---
    /** The triple is attack 1, 11, 21, ... i.e. attack n where n % 10 == 1. */
    public static final int TRIPLE_ATTACK_INTERVAL = 10;
    /** Her magic projectile (normal attacks too). In the triple: launched -> switch to Protect from Missiles. */
    public static final int TRIPLE_MAGIC_PROJECTILE = 1242;
    /** Her ranged projectile (normal attacks too). In the triple: launched -> switch to Protect from Melee. */
    public static final int TRIPLE_RANGED_PROJECTILE = 1245;
    /** The triple spans ~6 ticks (4489 / 4490 / 4491, 3 apart); give up on it after this many. */
    public static final int TRIPLE_MAX_TICKS = 10;
    /** A ranged projectile this soon after a magic one means it IS the triple (1-2 ticks apart in the logs;
     *  consecutive normal attacks are 6 ticks apart, their projectiles at least ~4). */
    public static final int TRIPLE_SIGNATURE_TICKS = 2;
    /** Ticks before a soul switch during which the pipeline holds its mouse clicks. */
    public static final int FLICK_CLICK_GUARD_TICKS = 2;

    // --- Sustain ---
    /** Ticks between eats/drinks: a dose takes this long to register, so drinking sooner wastes doses. */
    public static final int SUSTAIN_COOLDOWN_TICKS = 3;
    /** At or below this prayer, drinking goes ahead even while a protection flick is due. */
    public static final int EMERGENCY_PRAYER = 10;
    /** Prayer the three blocked souls drain (30 each without a spectral shield). The wiki's ~65 wasn't
     *  enough in practice: prayer hit 0 on the third soul. Topped up toward this as soon as a wave spawns. */
    public static final int SOUL_WAVE_PRAYER_COST = 90;
    /** Leave this much headroom below the real prayer level so a top-up dose isn't mostly wasted. */
    public static final int PRAYER_DOSE_HEADROOM = 20;

    // --- Lava pools ---
    public static final int LAVA_GRAPHICS_OBJECT = 1246;
    /** How far out (in tiles) to look for a lava-free tile. */
    public static final int LAVA_DODGE_RADIUS = 4;

    // --- Lair / between kills ---
    /** Every Cerberus form (fighting / idle / dead); any of them in the scene means we're in a lair room. */
    public static final int[] CERBERUS_IDS = {5862, 5863, 5866};
    public static final WorldPoint KEY_MASTER_TILE = new WorldPoint(1310, 1250, 0);
    public static final String KEY_MASTER_NAME = "Key Master";
    public static final String IRON_WINCH_NAME = "Iron Winch";
    public static final String HOUSE_TABLET = "Teleport to house";
    public static final String RING_OF_DUELING = "Ring of dueling";
    public static final String FEROX_ENCLAVE = "Ferox Enclave";
    /** We're in a lair room when Cerberus is within this many tiles (her rooms are in scene range of the
     *  Key Master, so "she's loaded" alone isn't enough). */
    public static final int IN_LAIR_RADIUS = 25;
    /** After turning a winch we've entered the lair once we're this far from the Key Master. */
    public static final int LAIR_ENTERED_DISTANCE = 20;
    /** The winch lands us at the south end of the room; she's about this many tiles north. */
    public static final int LAIR_ENTRANCE_TO_BOSS_TILES = 25;
    /** Close enough to her to stop running north from the entrance. */
    public static final int BOSS_NEARBY_RADIUS = 12;
    /** Stand this many tiles east/west of her centre: just outside her 5x5 body (centre +/- 2). */
    public static final int SIDE_SPOT_OFFSET = 3;
    // --- No-ghosts strategy ---
    public static final int CERBERUS_MAX_HP = 600;
    /** Souls only come while she's below this. */
    public static final int SOULS_HP_THRESHOLD = 400;
    /** Souls are attacks 7, 14, 28 (21 is the triple): at/above 400 HP through attack 14 means none before 28. */
    public static final int NO_GHOSTS_SAFE_ATTACK = 14;
    /** Step this many tiles further out from her side spot to stop meleeing while holding. */
    public static final int HOLD_STEP_BACK_TILES = 2;

    /** Left behind by a released moonlight moth: dropped, and never looted back up. */
    public static final String BUTTERFLY_JAR = "Butterfly jar";
    /** Ground items within this many tiles after a kill are looted. */
    public static final int LOOT_RANGE = 10;

    public static boolean isSoul(int npcId) {
        return npcId == SOUL_RANGED_ID || npcId == SOUL_MAGIC_ID || npcId == SOUL_MELEE_ID;
    }

    /** The protection prayer that blocks the given soul, or null if the id isn't a soul. */
    public static Rs2PrayerEnum soulPrayer(int npcId) {
        switch (npcId) {
            case SOUL_RANGED_ID:
                return Rs2PrayerEnum.PROTECT_RANGE;
            case SOUL_MAGIC_ID:
                return Rs2PrayerEnum.PROTECT_MAGIC;
            case SOUL_MELEE_ID:
                return Rs2PrayerEnum.PROTECT_MELEE;
            default:
                return null;
        }
    }
}

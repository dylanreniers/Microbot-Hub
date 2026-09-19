package net.runelite.client.plugins.custom.abyssalsire.constants;

import net.runelite.api.coords.WorldPoint;

/**
 * Static IDs and arena coordinates for the Abyssal Sire fight, gathered from live observation. All
 * arena tiles are on plane 0.
 *
 * <p>The Sire is a single NPC that changes id as the fight progresses; the plugin listens to
 * NpcSpawned / NpcChanged and reads the current id to drive the phase state machine. The respiratory
 * systems and tentacles are separate NPCs queried on demand by id.
 */
public final class SireConstants {

    private SireConstants() {
    }

    // ---- Names ----
    public static final String SIRE_NAME = "Abyssal Sire";

    // ---- Sire NPC ids (the boss transforms through these) ----
    /** Spawn id, and the one the Sire returns to (from 5887/5888) once the respiratory systems are
     *  cleared — a change to this AFTER the fight has started is the phase-2 tell. */
    public static final int SIRE_STANDING = 5886;
    /** Phase 1, awake (after a barrage disturbs it). */
    public static final int SIRE_PHASE1_AWAKE = 5887;
    /** Phase 1, asleep (stunned). */
    public static final int SIRE_PHASE1_ASLEEP = 5888;
    /** Exposed and being fought head-on (melee window). */
    public static final int SIRE_HEAD_ON = 5990;
    /** Phase 2, fully exposed and attacking with melee — only then is Protect from Melee needed. */
    public static final int SIRE_PHASE2_MELEE = 5890;
    /** Walking to the arena centre — marks the start of phase 3. */
    public static final int SIRE_WALKING = 5889;
    /** Winding up the miasma explosion. */
    public static final int SIRE_PREP_EXPLOSION = 5891;
    /** Recovered after the explosion animation — attackable again. */
    public static final int SIRE_POST_EXPLOSION = 5908;

    /** Every id the Sire NPC can present as (used to recognise the boss on spawn/change). */
    public static final int[] SIRE_IDS = {
            SIRE_STANDING, SIRE_PHASE1_AWAKE, SIRE_PHASE1_ASLEEP, SIRE_HEAD_ON,
            SIRE_PHASE2_MELEE, SIRE_WALKING, SIRE_PREP_EXPLOSION, SIRE_POST_EXPLOSION
    };

    public static boolean isSireId(int id) {
        for (int sireId : SIRE_IDS) {
            if (sireId == id) {
                return true;
            }
        }
        return false;
    }

    // ---- Supporting NPCs ----
    /** Tentacles present multiple ids (dormant vs attacking), so match them by NAME, not id. */
    public static final String TENTACLE_NAME = "Tentacle";
    public static final int RESPIRATORY_SYSTEM_ID = 5914;

    // ---- Barrage stun (matches RuneLite's Timers & Buffs ABYSSAL_SIRE_STUN) ----
    /** Chat message shown when a barrage disorients the Sire (stuns the tentacles). */
    public static final String STUN_MESSAGE_FRAGMENT = "disorientated temporarily";
    /** The disorient lasts 46 game ticks; re-barrage before it expires. */
    public static final int STUN_DURATION_TICKS = 46;
    public static final long STUN_DURATION_MS = STUN_DURATION_TICKS * 600L;

    // ---- Graphics / animations ----
    /** Ground miasma pool spot-anim (GraphicsObject id). */
    public static final int MIASMA_POOL_GRAPHICS_ID = 1275;
    /** The Sire's death animation. */
    public static final int SIRE_DEATH_ANIMATION = 7100;
    /** The local player's animation when knocked back by the explosion. */
    public static final int PLAYER_EXPLOSION_ANIMATION = 1816;

    // ---- Arena tiles ----
    /** Barrage / attack spot for phases 1-2. */
    public static final WorldPoint ORIGINAL_POSITION = new WorldPoint(2969, 4780, 0);
    /** Phase-2 miasma dodge: two tiles east of the original spot. */
    public static final WorldPoint PHASE2_MIASMA_DODGE = new WorldPoint(2971, 4780, 0);

    // Phase-3 miasma dodge pair (toggled when a pool appears).
    public static final WorldPoint PHASE3_TILE_A = new WorldPoint(2969, 4772, 0);
    public static final WorldPoint PHASE3_TILE_B = new WorldPoint(2971, 4772, 0);

    // Phase-3 explosion run-away pair (used when knocked back; second tile if the first has a pool).
    public static final WorldPoint PHASE3_ESCAPE_A = new WorldPoint(2969, 4770, 0);
    public static final WorldPoint PHASE3_ESCAPE_B = new WorldPoint(2971, 4770, 0);

    /** The four respiratory-system tiles (informational / overlay). */
    public static final WorldPoint[] RESPIRATORY_TILES = {
            new WorldPoint(2954, 4780, 0),
            new WorldPoint(2982, 4779, 0),
            new WorldPoint(2985, 4769, 0),
            new WorldPoint(2957, 4770, 0),
    };
}

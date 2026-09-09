package net.runelite.client.plugins.custom.madangel.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.Projectile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

import java.util.Set;

/**
 * Stateless helpers and constants shared by the Mad Angel actions and the plugin's event handlers.
 * All the boss's animation-id → reaction mappings live here (derived from a live capture session),
 * so tuning them is a one-place edit. Mirrors the {@code GorillaHelpers} pattern.
 */
@Slf4j
public final class MadAngelHelpers {

    public static final String ANGEL_NAME = "Mad Angel";
    public static final int ANGEL_ID = 16314;
    /** The boss occupies a 3x3 tile footprint. */
    public static final int ANGEL_SIZE = 3;
    /** How far to move to dodge a sweep: 5 tiles opposite the angel's facing (counts the tile we stand
     *  on plus the destination we want to end up on). */
    public static final int SWEEP_DODGE_TILES = 5;
    /** A sweep is a burst of cleave animations; it's "over" once none has fired for this many ticks.
     *  Also serves as the post-sweep hold: we stay on the far side this long after the last cleave so
     *  its hit lands before AttackAction walks us back toward her (else we re-approach into it — the
     *  "hit on the last sweep" bug). */
    public static final int SWEEP_END_TICKS = 4;

    // --- Sweep cleave animations. These now only TRIGGER a sweep dodge; the dodge itself is simply
    //     4 tiles opposite the angel's facing (see computeSweepDodgeTile), independent of which cleave. ---
    public static final Set<Integer> SWEEP_LEFT_ANIMS = Set.of(14431, 14433, 14434, 14436, 14439, 14440, 14441);
    public static final Set<Integer> SWEEP_RIGHT_ANIMS = Set.of(14429, 14430, 14432, 14435, 14437, 14438, 14442);

    /** Enrage begins at ~45% of the boss's health; its specials come faster and the sweep gets more cleaves. */
    public static final int ENRAGE_HP_PERCENT = 45;
    // --- Sweep cadence (measured from live capture; validated by the tick-scheduled sweep-plan log). A
    //     sweep is a burst of evenly spaced cleaves: dodge on each. AnimationChanged misses repeated-id
    //     cleaves, so the plan is driven purely by ticks from the first cleave. ---
    /** Normal phase: 4 cleaves, one every 6 ticks (3.6s). */
    public static final int SWEEP_NORMAL_INTERVAL_TICKS = 6;
    public static final int SWEEP_NORMAL_CLEAVES = 4;
    /** Enrage phase: 6 cleaves, one every 4 ticks (2.4s). */
    public static final int SWEEP_ENRAGE_INTERVAL_TICKS = 4;
    public static final int SWEEP_ENRAGE_CLEAVES = 6;

    /** Classifies a (possibly non-sweep) animation id into the dodge side, or null if it isn't a cleave. */
    public static MadAngelContext.Side sweepSideFor(int anim) {
        if (SWEEP_LEFT_ANIMS.contains(anim)) return MadAngelContext.Side.LEFT;
        if (SWEEP_RIGHT_ANIMS.contains(anim)) return MadAngelContext.Side.RIGHT;
        return null;
    }

    // ---- Sweep dodge spots (the four side-centers of the 3x3 boss) --------------------------

    /**
     * The four tiles at the centre of each side of the 3x3 boss, plus which two are to the player's
     * LEFT/RIGHT. Computed in LOCAL/scene coordinates because this is an instanced, rotated region: the
     * boss's {@code getWorldLocation()} is in template space (a different frame than the player's), so
     * world-space maths is garbage. In local space the boss's {@code getLocalLocation()} is the centre of
     * its 3x3 footprint, and each side-centre is exactly 2 tiles out along an axis (front/South
     * {@code (0,-2)}, back/North {@code (0,+2)}, left/West {@code (-2,0)}, right/East {@code (+2,0)}).
     * Both {@link LocalPoint}s (for canvas rendering) and {@link WorldPoint}s (for logs / walking) are
     * provided. N/E/S/W labels are nominal scene axes — what matters is the player-relative LEFT/RIGHT.
     */
    public static final class DodgeSpots {
        public final WorldPoint front, left, back, right;   // side centres (S, W, N, E), scene world coords
        public final WorldPoint dodgeLeft, dodgeRight;      // from the player's facing perspective
        public final LocalPoint frontLp, leftLp, backLp, rightLp;
        public final LocalPoint dodgeLeftLp, dodgeRightLp;
        public final String playerSide;                     // which side (N/E/S/W) the player stands on

        DodgeSpots(WorldPoint front, WorldPoint left, WorldPoint back, WorldPoint right,
                   WorldPoint dodgeLeft, WorldPoint dodgeRight,
                   LocalPoint frontLp, LocalPoint leftLp, LocalPoint backLp, LocalPoint rightLp,
                   LocalPoint dodgeLeftLp, LocalPoint dodgeRightLp, String playerSide) {
            this.front = front;
            this.left = left;
            this.back = back;
            this.right = right;
            this.dodgeLeft = dodgeLeft;
            this.dodgeRight = dodgeRight;
            this.frontLp = frontLp;
            this.leftLp = leftLp;
            this.backLp = backLp;
            this.rightLp = rightLp;
            this.dodgeLeftLp = dodgeLeftLp;
            this.dodgeRightLp = dodgeRightLp;
            this.playerSide = playerSide;
        }
    }

    /**
     * Computes the four side-centre tiles in the BOSS'S frame (front/back/left/right relative to where it
     * faces), so they rotate with its {@link NPC#getOrientation() orientation} — the sweep is the boss's
     * action, so its safe sides must follow its facing, not fixed scene axes. Orientation maps
     * {@code 0=South, 512=West, 1024=North, 1536=East} (confirmed from live capture: boss faces the
     * player, ori=1536 when player is East). {@code dodgeLeft/dodgeRight} are the boss's own left/right
     * side tiles. All in local coords (consistent across the rotated instance).
     */
    public static DodgeSpots computeDodgeSpots(Client client, NPC angel) {
        if (client == null || angel == null) {
            return null;
        }
        LocalPoint c = angel.getLocalLocation(); // centre of the 3x3 footprint
        if (c == null) {
            return null;
        }
        // Facing unit vector in local axes (E=+x, N=+y), from orientation rounded to the nearest cardinal.
        int card = (Math.round(angel.getOrientation() / 512f) * 512) & 2047;
        int fx, fy;
        switch (card) {
            case 0:    fx = 0;  fy = -1; break; // South
            case 512:  fx = -1; fy = 0;  break; // West
            case 1024: fx = 0;  fy = 1;  break; // North
            default:   fx = 1;  fy = 0;  break; // 1536 East
        }
        int two = 2 * LOCAL_TILE;
        LocalPoint frontLp = new LocalPoint(c.getX() + fx * two, c.getY() + fy * two); // where it faces
        LocalPoint backLp = new LocalPoint(c.getX() - fx * two, c.getY() - fy * two);  // behind it
        // Boss's right = facing rotated clockwise (x,y)->(y,-x); left = counter-clockwise (x,y)->(-y,x).
        LocalPoint rightLp = new LocalPoint(c.getX() + fy * two, c.getY() - fx * two); // boss's right
        LocalPoint leftLp = new LocalPoint(c.getX() - fy * two, c.getY() + fx * two);  // boss's left

        String facing = card == 0 ? "S" : card == 512 ? "W" : card == 1024 ? "N" : "E";
        return new DodgeSpots(
                WorldPoint.fromLocalInstance(client, frontLp),
                WorldPoint.fromLocalInstance(client, leftLp),
                WorldPoint.fromLocalInstance(client, backLp),
                WorldPoint.fromLocalInstance(client, rightLp),
                WorldPoint.fromLocalInstance(client, leftLp),   // dodgeLeft  = boss's left
                WorldPoint.fromLocalInstance(client, rightLp),  // dodgeRight = boss's right
                frontLp, leftLp, backLp, rightLp, leftLp, rightLp, "faces" + facing);
    }

    // --- Blast / "burst": a marked tile appears (graphics object 1448); an energy ball (projectile 4015)
    //     flies at it. Standing on the tile bounces it back. Resolves when the projectile despawns. ---
    public static final int BLAST_ANIM = 14443;
    public static final int BLAST_GRAPHICS_OBJECT = 1448;
    public static final int BLAST_PROJECTILE = 4015;
    /** Hold the marked tile until the energy ball has been gone this many ticks — it bounces up to 3
     *  times, so brief gaps between bounces must NOT end the reaction early (or we'd leave too soon). */
    public static final int BLAST_RESOLVE_GRACE_TICKS = 2;
    /** Failsafe: drop the blast reaction if it never resolves within this many ticks (covers 3 bounces). */
    public static final int BLAST_TIMEOUT_TICKS = 20;

    // --- Smite: a magic charge. Flick Protect from Magic ON on precise ticks after the animation starts,
    //     turning it OFF in between so each flick lands on the right tick. Offsets are in GAME TICKS. ---
    public static final int SMITE_NORMAL_ANIM = 4590;
    public static final int SMITE_ENRAGE_ANIM = 8543;
    /** Normal phase: one flick, 5 ticks after the charge begins (a 1-tick lead-in before counting). */
    public static final int[] SMITE_NORMAL_ON_TICKS = {5};
    /** Enrage phase: three flicks at ticks 5, 9, 11 after the charge begins (1-tick lead-in, then +4/+4/+2). */
    public static final int[] SMITE_ENRAGE_ON_TICKS = {5, 9, 11};

    private MadAngelHelpers() {
    }

    // ---- Target acquisition ----------------------------------------------------------------

    /** Keeps the current angel while it lives; otherwise finds the nearest Mad Angel. */
    public static Rs2NpcModel getTarget(MadAngelContext ctx) {
        Rs2NpcModel current = ctx.getCurrentTarget();
        if (current != null && !current.getNpc().isDead()) {
            return current;
        }
        return Microbot.getRs2NpcCache().query().withName(ANGEL_NAME).nearest();
    }

    // ---- Sweep dodge geometry --------------------------------------------------------------

    /** One tile in local (scene) coordinates. */
    private static final int LOCAL_TILE = 128;

    /**
     * The tile to dodge a sweep: move {@link #SWEEP_DODGE_TILES} tiles straight THROUGH the angel — i.e.
     * in the player→angel direction, past her to the far side. Computed in LOCAL/scene coordinates
     * (which are consistent for both actors) rather than world coordinates: the fight is an instanced,
     * rotated region where the angel's {@code getWorldLocation()} is in a different frame than the
     * player's, so world-space direction is garbage. The destination is converted back with
     * {@link WorldPoint#fromLocalInstance} for walking. Computed once at the start of a sweep; the
     * caller ping-pongs between this tile and the origin — no orientation read, hence no boss turn-back
     * timing issue.
     */
    public static WorldPoint computeSweepThroughTile(NPC angel) {
        Client client = Microbot.getClient();
        var localPlayer = client.getLocalPlayer();
        if (localPlayer == null || angel == null) {
            return null;
        }
        LocalPoint pl = localPlayer.getLocalLocation();
        LocalPoint al = angel.getLocalLocation();
        if (pl == null || al == null) {
            return null;
        }
        int ddx = al.getX() - pl.getX();
        int ddy = al.getY() - pl.getY();
        int dirX = 0, dirY = 0;
        if (Math.abs(ddx) >= Math.abs(ddy)) {
            dirX = Integer.signum(ddx);
        } else {
            dirY = Integer.signum(ddy);
        }
        if (dirX == 0 && dirY == 0) {
            return null; // on top of her — nothing sensible to compute
        }
        LocalPoint dest = new LocalPoint(
                pl.getX() + dirX * SWEEP_DODGE_TILES * LOCAL_TILE,
                pl.getY() + dirY * SWEEP_DODGE_TILES * LOCAL_TILE);
        return WorldPoint.fromLocalInstance(client, dest);
    }

    /**
     * Whether the player is in melee range of the 3x3 boss — i.e. orthogonally adjacent to its footprint
     * (not diagonal, which OSRS melee can't reach without a step). Computed in LOCAL/scene tiles from the
     * boss centre, since the instance's world coords put the boss in a different frame than the player.
     * A side-centre tile is dx/dy = ±2 tiles from centre and counts; corners (±2,±2) don't.
     */
    public static boolean isInMeleeRange(Client client, NPC angel) {
        if (client == null || angel == null) {
            return false;
        }
        LocalPoint c = angel.getLocalLocation();
        var localPlayer = client.getLocalPlayer();
        LocalPoint p = localPlayer != null ? localPlayer.getLocalLocation() : null;
        if (c == null || p == null) {
            return false;
        }
        int dxT = Math.round((p.getX() - c.getX()) / (float) LOCAL_TILE);
        int dyT = Math.round((p.getY() - c.getY()) / (float) LOCAL_TILE);
        return (Math.abs(dxT) == 2 && Math.abs(dyT) <= 1) || (Math.abs(dyT) == 2 && Math.abs(dxT) <= 1);
    }

    // ---- Blast tile / projectile (client-thread reads) --------------------------------------

    /** The world tile of the blast's marked graphics object (id 1448), or null if none is present. */
    public static WorldPoint findBlastTile() {
        Client client = Microbot.getClient();
        for (GraphicsObject go : client.getGraphicsObjects()) {
            if (go.getId() == BLAST_GRAPHICS_OBJECT) {
                LocalPoint lp = go.getLocation();
                if (lp != null) {
                    return WorldPoint.fromLocalInstance(client, lp);
                }
            }
        }
        return null;
    }

    /** True while the blast energy ball (projectile 4015) is still in flight. */
    public static boolean blastProjectilePresent() {
        for (Projectile p : Microbot.getClient().getProjectiles()) {
            if (p.getId() == BLAST_PROJECTILE) {
                return true;
            }
        }
        return false;
    }

    // ---- Prayer ----------------------------------------------------------------------------

    /** Toggles Protect from Magic for a smite flick and records the state so we know when to flick off. */
    public static void setProtectMagic(MadAngelContext ctx, boolean on) {
        Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, on);
        ctx.setSmitePrayerOn(on);
    }

    /** Ensures Protect from Melee is active — the default overhead, restored after each smite flick. */
    public static void ensureProtectFromMelee() {
        if (!Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_MELEE)) {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MELEE, true);
        }
    }
}

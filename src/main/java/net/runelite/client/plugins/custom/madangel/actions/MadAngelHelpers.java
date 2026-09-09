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

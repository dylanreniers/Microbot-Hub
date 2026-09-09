package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.HeadIcon;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.customdemonicgorilla.CustomDemonicGorillaConfig;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.ArmorEquiped;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2Potion;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static net.runelite.client.plugins.microbot.util.Global.sleep;

/**
 * Stateless helpers shared by the Demonic Gorilla actions. All logic operates on the passed
 * {@link GorillaContext}/{@link CustomDemonicGorillaConfig} so the same routines can be reused from
 * more than one action (e.g. gear switching happens both on a fresh target and mid-fight when the
 * gorilla flips its overhead). Mirrors the {@code ZulrahHelpers} pattern.
 */
@Slf4j
public final class GorillaHelpers {

    public static final String GORILLA_NAME = "Demonic gorilla";

    public static final int DEMONIC_GORILLA_PRAYER_SWITCH = 7224;
    public static final int DEMONIC_GORILLA_MAGIC_ATTACK = 7225;
    public static final int DEMONIC_GORILLA_MELEE_ATTACK = 7226;
    public static final int DEMONIC_GORILLA_RANGED_ATTACK = 7227;
    public static final int DEMONIC_GORILLA_AOE_ATTACK = 7228;

    /** Behind the rope at the cave entrance — safe spot we retreat to via the seed pod. */
    public static final WorldPoint SAFE_LOCATION = new WorldPoint(2465, 3494, 0);
    /** Inside the Crash Island cave where the gorillas are fought. */
    public static final WorldPoint GORILLA_LOCATION = new WorldPoint(2100, 5643, 0);

    private GorillaHelpers() {
    }

    /** Logs {@code message} to chat once, suppressing immediate repeats (per-context de-dup). */
    public static void logOnce(GorillaContext ctx, String message) {
        if (!message.equals(ctx.getLastChatMessage())) {
            Microbot.log(message);
            ctx.setLastChatMessage(message);
        }
    }

    // ---- Target acquisition ----------------------------------------------------------------

    public static Rs2NpcModel getTarget(GorillaContext ctx) {
        return getTarget(ctx, false);
    }

    /**
     * Resolves the gorilla we should be fighting: keeps the current one while it lives (unless
     * {@code force}), otherwise prefers a gorilla already interacting with us, then the nearest free
     * one. Raw {@code NPC.getInteracting()/isDead()} reads are done on the client thread.
     */
    public static Rs2NpcModel getTarget(GorillaContext ctx, boolean force) {
        Rs2NpcModel current = ctx.getCurrentTarget();
        if (current != null && !current.getNpc().isDead() && !force) {
            return current;
        }
        var interacting = Rs2Player.getInteracting();
        if (interacting != null && Objects.equals(interacting.getName(), GORILLA_NAME)) {
            var match = Microbot.getRs2NpcCache().query().withName(GORILLA_NAME)
                    .where(Rs2NpcModel::isInteractingWithPlayer).nearest();
            if (match != null) return match;
        }
        var playerLocation = Microbot.getClientThread()
                .invoke(() -> Microbot.getClient().getLocalPlayer().getWorldLocation());

        var alreadyInteractingNpcs = Microbot.getRs2NpcCache().query().withName(GORILLA_NAME)
                .where(Rs2NpcModel::isInteractingWithPlayer).toList();
        if (!alreadyInteractingNpcs.isEmpty()) {
            return alreadyInteractingNpcs.stream()
                    .min(Comparator.comparingInt(npc -> npc.getWorldLocation().distanceTo(playerLocation))).get();
        }

        List<Rs2NpcModel> gorillas = Microbot.getRs2NpcCache().query().withName(GORILLA_NAME).toListOnClientThread();
        if (gorillas.isEmpty()) {
            logOnce(ctx, "No demonic gorilla found.");
            return null;
        }

        String playerName = Rs2Player.getLocalPlayer().getName();

        // NPC.getInteracting()/isDead() are raw client API calls and must run on the client thread.
        return Microbot.getClientThread().invoke(() -> {
            for (Rs2NpcModel gorilla : gorillas) {
                if (gorilla != null) {
                    var interactingTwo = gorilla.getNpc().getInteracting();
                    String interactingName = interactingTwo != null ? interactingTwo.getName() : "None";
                    if (interactingTwo != null && Objects.equals(interactingName, playerName)) {
                        return gorilla;
                    }
                }
            }
            logOnce(ctx, "Finding closest demonic gorilla.");
            return gorillas.stream()
                    .filter(npc -> npc != null && !npc.getNpc().isDead() && npc.getNpc().getInteracting() == null)
                    .min(Comparator.comparingInt(npc -> npc.getWorldLocation().distanceTo(playerLocation)))
                    .orElse(null);
        });
    }

    // ---- Gear switching --------------------------------------------------------------------

    /**
     * Re-checks the target's overhead and swaps gear if it changed. Shared by {@link GearSwitchAction}
     * and {@link GorillaAttacksAction} (the gorilla's prayer-switch animation forces a re-evaluation).
     */
    public static void handleGearSwitching(GorillaContext ctx, CustomDemonicGorillaConfig config) {
        try {
            if (ctx.getFailedCount() >= 3) {
                ctx.setCurrentTarget(getTarget(ctx, true));
                logOnce(ctx, "Forcing new target");
            }
            Rs2NpcModel target = ctx.getCurrentTarget();
            if (target == null) return;
            HeadIcon newOverheadIcon = target.getHeadIcon();
            if (newOverheadIcon == null) return;
            if (newOverheadIcon != ctx.getCurrentOverheadIcon()) {
                ctx.setCurrentOverheadIcon(newOverheadIcon);
                switchGear(ctx, config, newOverheadIcon);
                sleep(Rs2Random.between(80, 170));
                ctx.setFailedCount(0);
            }
        } catch (Exception e) {
            logOnce(ctx, "Failed to retrieve HeadIcon for target.");
            ctx.setFailedCount(ctx.getFailedCount() + 1);
        }
    }

    /** Minimum gap between worn-gear recovery attempts (a couple ticks), so a full inventory that keeps
     *  blocking the swap can't hot-loop wearEquipment() and back the client thread up into a freeze. */
    private static final long GEAR_VERIFY_INTERVAL_MS = 1500;

    /**
     * Verifies the WORN equipment matches the setup for our intended combat style and re-equips if not.
     * A gear swap can silently fail — e.g. the inventory fills with loot, leaving no slot for the piece
     * being unequipped — which {@link #handleGearSwitching} won't retry because it only acts on an overhead
     * change. Left unhandled we keep fighting with the wrong weapon and get stuck on the gorilla. This runs
     * every tick but only actually checks/re-equips once per {@link #GEAR_VERIFY_INTERVAL_MS}; once a slot
     * frees (food eaten, potion finished) the retry succeeds and we recover on our own.
     */
    public static void verifyGear(GorillaContext ctx, CustomDemonicGorillaConfig config) {
        Rs2InventorySetup setup = setupFor(ctx, ctx.getCurrentGear());
        if (setup == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - ctx.getLastGearVerifyMs() < GEAR_VERIFY_INTERVAL_MS) {
            return;
        }
        ctx.setLastGearVerifyMs(now);
        if (setup.doesEquipmentMatch()) {
            return; // already wearing the right gear
        }
        logOnce(ctx, "Wrong gear worn for " + ctx.getCurrentGear() + " — re-equipping");
        setup.wearEquipment();
    }

    /** The inventory setup backing an {@link ArmorEquiped} style, or null if that style isn't configured. */
    private static Rs2InventorySetup setupFor(GorillaContext ctx, ArmorEquiped gear) {
        switch (gear) {
            case MELEE:
                return ctx.getMeleeGear();
            case RANGED:
                return ctx.getRangeGear();
            case MAGIC:
                return ctx.getMagicGear();
            default:
                return null;
        }
    }

    /** Picks the counter-style gear for the gorilla's current overhead and equips it. */
    public static void switchGear(GorillaContext ctx, CustomDemonicGorillaConfig config, HeadIcon combatNpcHeadIcon) {
        boolean useRange = config.useRangeStyle();
        boolean useMagic = config.useMagicStyle();
        boolean useMelee = config.useMeleeStyle();

        switch (combatNpcHeadIcon) {
            case RANGED:
                if (useMelee && useMagic) {
                    ctx.setCurrentGear(Rs2Random.dicePercentage(50) ? ArmorEquiped.MELEE : ArmorEquiped.MAGIC);
                } else if (useMelee) {
                    ctx.setCurrentGear(ArmorEquiped.MELEE);
                } else if (useMagic) {
                    ctx.setCurrentGear(ArmorEquiped.MAGIC);
                }
                break;
            case MAGIC:
                if (useRange && useMelee) {
                    ctx.setCurrentGear(Rs2Random.dicePercentage(50) ? ArmorEquiped.RANGED : ArmorEquiped.MELEE);
                } else if (useRange) {
                    ctx.setCurrentGear(ArmorEquiped.RANGED);
                } else if (useMelee) {
                    ctx.setCurrentGear(ArmorEquiped.MELEE);
                }
                break;
            case MELEE:
                if (useRange && useMagic) {
                    ctx.setCurrentGear(Rs2Random.dicePercentage(50) ? ArmorEquiped.RANGED : ArmorEquiped.MAGIC);
                } else if (useRange) {
                    ctx.setCurrentGear(ArmorEquiped.RANGED);
                } else if (useMagic) {
                    ctx.setCurrentGear(ArmorEquiped.MAGIC);
                }
                break;
        }
        if (ctx.getCurrentGear() == ArmorEquiped.MELEE) {
            equipGear(ctx, ctx.getMeleeGear());
        } else if (ctx.getCurrentGear() == ArmorEquiped.MAGIC) {
            equipGear(ctx, ctx.getMagicGear());
        } else if (ctx.getCurrentGear() == ArmorEquiped.RANGED) {
            equipGear(ctx, ctx.getRangeGear());
        }
    }

    private static void equipGear(GorillaContext ctx, Rs2InventorySetup gear) {
        if (gear == null) return;
        if (!gear.wearEquipment()) {
            // Couldn't fully equip (missing items / inventory full / bank closed). Do NOT eat food to
            // free a slot and retry in-place: useFood() -> Rs2Inventory.interact -> invokeMenu ->
            // Rs2Bank.isOpen -> handleBankPin fires a chain of client-thread invoke()s, and combined
            // with waitForInventoryChanges() this floods the single client thread on every overhead
            // flip. When the client thread backs up, the AWT EDT (antiban MasterPanel calling
            // Rs2Combat.inCombat) blocks on its own invoke and the whole game window freezes.
            // Log once and move on; the next overhead change re-evaluates.
            logOnce(ctx, "Failed to equip " + ctx.getCurrentGear() + " gear (missing items / inv full)");
        }
    }

    // ---- Prayer ----------------------------------------------------------------------------

    public static void switchDefensivePrayer(GorillaContext ctx, Rs2PrayerEnum newDefensivePrayer) {
        // Enable the NEW overhead FIRST, so there's never an unprotected gap. Only disable the old one
        // if the new one actually turned ON — otherwise (e.g. we momentarily hit 0 prayer points from
        // rapid flicking) disabling the old would leave us praying NOTHING right as the attack lands.
        boolean nowOn = Rs2Prayer.toggle(newDefensivePrayer, true);
        Rs2PrayerEnum old = ctx.getCurrentDefensivePrayer();
        if (nowOn && old != null && old != newDefensivePrayer) {
            Rs2Prayer.toggle(old, false);
        }
        if (nowOn) {
            ctx.setCurrentDefensivePrayer(newDefensivePrayer);
        }
    }

    public static void switchOffensivePrayer(GorillaContext ctx, Rs2PrayerEnum newOffensivePrayer) {
        if (ctx.getCurrentOffensivePrayer() != null) {
            Rs2Prayer.toggle(ctx.getCurrentOffensivePrayer(), false);
        }
        Rs2Prayer.toggle(newOffensivePrayer, true);
        ctx.setCurrentOffensivePrayer(newOffensivePrayer);
    }

    public static void disableAllPrayers(GorillaContext ctx) {
        Rs2Prayer.disableAllPrayers();
        ctx.setCurrentDefensivePrayer(null);
        ctx.setCurrentOffensivePrayer(null);
    }

    // ---- Movement --------------------------------------------------------------------------

    /** Steps the default read distance directly away from the current target. */
    public static boolean moveAwayFromTarget(GorillaContext ctx) {
        return moveAwayFromTarget(ctx, 6);
    }

    /**
     * Walks {@code tiles} tiles directly AWAY from the current target (falling back to whichever cardinal
     * step increases the distance if the straight-away tile is blocked), used to open the 5–6 tile gap
     * needed to read the melee tell / keep range distance.
     */
    public static boolean moveAwayFromTarget(GorillaContext ctx, int tiles) {
        Rs2NpcModel target = ctx.getCurrentTarget();
        if (target == null) {
            return false;
        }

        WorldPoint player = Rs2Player.getWorldLocation();
        WorldPoint targetLocation = target.getWorldLocation();
        if (player == null || targetLocation == null) {
            return false;
        }

        int dirX = player.getX() - targetLocation.getX(); // vector pointing FROM the target TO us (= away)
        int dirY = player.getY() - targetLocation.getY();
        double length = Math.sqrt(dirX * dirX + dirY * dirY);
        if (length == 0) { // standing on the gorilla's tile — pick an arbitrary away direction
            dirX = 1;
            length = 1;
        }
        int moveX = (int) Math.round(dirX / length * tiles);
        int moveY = (int) Math.round(dirY / length * tiles);

        // Move FURTHER along the away vector (player + away), not toward the gorilla.
        WorldPoint dest = new WorldPoint(player.getX() + moveX, player.getY() + moveY, player.getPlane());

        int currentDist = player.distanceTo(targetLocation);
        if (!Rs2Tile.isWalkable(dest) || dest.distanceTo(targetLocation) <= currentDist) {
            for (WorldPoint alt : List.of(
                    new WorldPoint(player.getX() + tiles, player.getY(), player.getPlane()),
                    new WorldPoint(player.getX() - tiles, player.getY(), player.getPlane()),
                    new WorldPoint(player.getX(), player.getY() + tiles, player.getPlane()),
                    new WorldPoint(player.getX(), player.getY() - tiles, player.getPlane()))) {
                if (Rs2Tile.isWalkable(alt) && alt.distanceTo(targetLocation) > currentDist) {
                    dest = alt;
                    break;
                }
            }
        }

        if (dest.equals(player) || dest.distanceTo(targetLocation) <= currentDist) {
            return false;
        }
        return Rs2Walker.walkFastCanvas(dest);
    }

    /** Finds a nearby walkable tile that isn't in the danger set (used to dodge the AOE boulder). */
    public static WorldPoint findSafeTile(GorillaContext ctx, WorldPoint playerLocation, List<WorldPoint> dangerousWorldPoints) {
        List<WorldPoint> nearbyTiles = List.of(
                new WorldPoint(playerLocation.getX() + 1, playerLocation.getY(), playerLocation.getPlane()),
                new WorldPoint(playerLocation.getX() + 2, playerLocation.getY(), playerLocation.getPlane()),
                new WorldPoint(playerLocation.getX() - 1, playerLocation.getY(), playerLocation.getPlane()),
                new WorldPoint(playerLocation.getX() - 2, playerLocation.getY(), playerLocation.getPlane()),
                new WorldPoint(playerLocation.getX(), playerLocation.getY() + 1, playerLocation.getPlane()),
                new WorldPoint(playerLocation.getX(), playerLocation.getY() + 2, playerLocation.getPlane()),
                new WorldPoint(playerLocation.getX(), playerLocation.getY() - 1, playerLocation.getPlane()),
                new WorldPoint(playerLocation.getX(), playerLocation.getY() - 2, playerLocation.getPlane())
        );

        for (WorldPoint tile : nearbyTiles) {
            final LocalPoint location = LocalPoint.fromWorld(Microbot.getClient(), tile);
            if (!dangerousWorldPoints.contains(tile) && Rs2Tile.isWalkable(location)) {
                logOnce(ctx, "Found safe tile: " + tile);
                return tile;
            }
        }
        logOnce(ctx, "No safe tile found!");
        return null;
    }

    // ---- Supplies --------------------------------------------------------------------------

    /** True when we should abandon the trip and bank: out of food and low HP, or out of prayer pots. */
    public static boolean shouldRetreat(CustomDemonicGorillaConfig config) {
        int currentHealth = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
        int currentPrayer = Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER);
        boolean noFood = Rs2Inventory.getInventoryFood().isEmpty();
        boolean noPrayerPotions = Rs2Inventory.items()
                .noneMatch(item -> item != null && item.getName() != null && Rs2Potion.getPrayerPotionsVariants().contains(item.getName()));

        return (noFood && currentHealth <= config.healthThreshold()) || (noPrayerPotions && currentPrayer < 10);
    }
}

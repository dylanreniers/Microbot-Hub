package net.runelite.client.plugins.custom.cerberus.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.plugins.custom.cerberus.CerberusSupplies;
import net.runelite.client.plugins.custom.cerberus.constants.CerberusConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.shared.FeroxService;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * The restock trip, run as ONE blocking sequence on the script thread (the bank / walker / teleport
 * helpers all block via {@code sleepUntil}, so a linear script fits them better than a per-tick machine):
 * <ol>
 *   <li>If supplies are low: ring of dueling -> Ferox Enclave, drink from the pool, load the resupply
 *       inventory setup at the bank.</li>
 *   <li>House tablet "Outside" (Taverley), walk to the Key Master, "Listen" to learn which of the north /
 *       east / west caves has no adventurers, "Turn" that cave's Iron Winch, (no-ghosts) switch auto-retaliate
 *       off, drink stat-boosting potions,
 *       and run north from the entrance until Cerberus is close ({@code PositionAction} then takes us
 *       beside her).</li>
 * </ol>
 * Any failed step stops the loop (phase STOPPED) so the problem is visible rather than retried blindly.
 */
@Slf4j
public class RestockAction implements CerberusAction {

    private static final int TELEPORT_TIMEOUT_MS = 10_000;
    private static final int WALK_TIMEOUT_MS = 180_000;
    private static final int STEP_TIMEOUT_MS = 15_000;
    private static final int BANK_OPEN_ATTEMPTS = 3;
    /** Max tiles per minimap click inside the lair (well inside the minimap's radius). */
    private static final int MINIMAP_STEP_TILES = 10;
    private static final int BANK_OPEN_WAIT_MS = 8_000;
    /** The Key Master tells us about the caves over a few dialogue pages; stop reading after this many. */
    private static final int MAX_DIALOGUE_PAGES = 8;

    private enum Cave {NORTH, EAST, WEST}

    /** Stats boosted on entering the lair, in this order. */
    private static final Skill[] BOOSTED_SKILLS = {Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.MAGIC};

    @Inject
    private FeroxService feroxService;

    @Override
    public int order() {
        return 800;
    }

    @Override
    public String key() {
        return "restock";
    }

    @Override
    public boolean needsExecution(CerberusState state) {
        return state.context().getPhase() == CerberusContext.Phase.RESTOCK;
    }

    @Override
    public Object execute(CerberusState state) {
        CerberusContext ctx = state.context();
        try {
            if (runTrip(state)) {
                log.info("[cerberus] in a free lair — ready for the next kill");
                // She often stands up while we walk north; resetting then wiped the synced fight and the
                // plugin re-acquired her UNSYNCED (no triple prediction, no-ghosts disabled, thrall at once).
                if (!ctx.isFightActive()) {
                    ctx.resetFight();
                }
                ctx.setSideOffset(null); // new room: pick east/west afresh
                ctx.setPhase(CerberusContext.Phase.FIGHT);
                return "trip-complete";
            }
        } catch (Exception e) {
            log.error("[cerberus] restock trip threw", e);
        }
        log.error("[cerberus] restock trip failed — stopping the loop; check the step above");
        Microbot.log("Cerberus: restock trip failed — see the log. Restart the plugin to retry.");
        ctx.setPhase(CerberusContext.Phase.STOPPED);
        return "trip-failed";
    }

    private boolean runTrip(CerberusState state) {
        Rs2Prayer.disableAllPrayers();
        if (CerberusSupplies.needsResupply(state.config())) {
            Rs2InventorySetup setup = state.context().getResupplySetup();
            if (setup == null) {
                log.error("[cerberus] supplies are low but no resupply inventory setup is configured");
                return false;
            }
            if (!teleportToFerox() || !restoreAtPool() || !resupply(setup)) {
                return false;
            }
        }
        return teleportOutsideHouse() && walkToKeyMaster() && enterFreeLair(state);
    }

    // ---- Ferox ---------------------------------------------------------------------------------

    private boolean teleportToFerox() {
        if (FeroxService.isNearFerox()) {
            return true;
        }
        log.info("[cerberus] ring of dueling -> Ferox Enclave");
        if (Rs2Equipment.isWearing(CerberusConstants.RING_OF_DUELING, false)) {
            Rs2Equipment.interact(EquipmentInventorySlot.RING, CerberusConstants.FEROX_ENCLAVE);
        } else {
            Rs2ItemModel ring = Rs2Inventory.items()
                    .filter(i -> i.getName() != null && i.getName().startsWith(CerberusConstants.RING_OF_DUELING))
                    .findFirst().orElse(null);
            if (ring == null) {
                log.error("[cerberus] no ring of dueling worn or in the inventory");
                return false;
            }
            Rs2Inventory.interact(ring, "Rub");
            if (!Rs2Dialogue.sleepUntilHasDialogueOption(CerberusConstants.FEROX_ENCLAVE)) {
                log.error("[cerberus] ring of dueling teleport menu didn't open");
                return false;
            }
            Rs2Dialogue.clickOption(CerberusConstants.FEROX_ENCLAVE);
        }
        if (!sleepUntil(FeroxService::isNearFerox, TELEPORT_TIMEOUT_MS)) {
            log.error("[cerberus] did not arrive at Ferox Enclave");
            return false;
        }
        sleep(600, 1200);
        return true;
    }

    private boolean restoreAtPool() {
        log.info("[cerberus] drinking from the Pool of Refreshment");
        feroxService.drinkFromPoolOfRefreshment();
        Rs2Player.toggleRunEnergy(true);
        return true; // the pool is a top-up; the bank resupply is what matters
    }

    /** Wear the setup's gear first (so swapped gear lands in the inventory), then deposit and reload. */
    private boolean resupply(Rs2InventorySetup setup) {
        log.info("[cerberus] resupplying at the Ferox bank");
        if (!openFeroxBank()) {
            log.error("[cerberus] could not open the Ferox bank after {} attempts", BANK_OPEN_ATTEMPTS);
            return false;
        }
        if (!setup.doesEquipmentMatch()) {
            setup.wearEquipment();
            sleepUntil(setup::doesEquipmentMatch, 3000);
            if (!setup.doesEquipmentMatch()) {
                setup.loadEquipment();
                sleepUntil(setup::doesEquipmentMatch, 5000);
            }
        }
        Rs2Bank.depositAll();
        sleepUntil(Rs2Inventory::isEmpty, 3000);
        if (!setup.loadInventory()) {
            log.error("[cerberus] could not load the resupply setup (missing items in the bank?)");
            return false;
        }
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 3000);
        return true;
    }

    /** Walk to and open the Ferox bank, retrying: one missed click used to fail the whole trip. */
    private boolean openFeroxBank() {
        for (int attempt = 1; attempt <= BANK_OPEN_ATTEMPTS; attempt++) {
            Rs2Bank.walkToBankAndUseBank(BankLocation.FEROX_ENCLAVE);
            if (sleepUntil(Rs2Bank::isOpen, BANK_OPEN_WAIT_MS)) {
                return true;
            }
            log.warn("[cerberus] bank not open (attempt {}/{}) — {} tiles from the bank, pin screen={}", attempt,
                    BANK_OPEN_ATTEMPTS, Rs2Player.getWorldLocation().distanceTo(BankLocation.FEROX_ENCLAVE.getWorldPoint()),
                    Rs2Bank.isBankPinWidgetVisible());
        }
        return false;
    }

    // ---- Back to the lair ----------------------------------------------------------------------

    private boolean teleportOutsideHouse() {
        log.info("[cerberus] house tablet -> outside (Taverley)");
        WorldPoint before = Rs2Player.getWorldLocation();
        if (!Rs2Inventory.interact(CerberusConstants.HOUSE_TABLET, "Outside")) {
            log.error("[cerberus] could not break a '{}' tablet (Outside)", CerberusConstants.HOUSE_TABLET);
            return false;
        }
        if (!sleepUntil(() -> before == null || Rs2Player.getWorldLocation().distanceTo(before) > 50, TELEPORT_TIMEOUT_MS)) {
            log.error("[cerberus] house tablet didn't teleport us");
            return false;
        }
        sleep(1200, 1800);
        return true;
    }

    private boolean walkToKeyMaster() {
        log.info("[cerberus] walking to the Key Master");
        WorldPoint target = CerberusConstants.KEY_MASTER_TILE;
        long deadline = System.currentTimeMillis() + WALK_TIMEOUT_MS;
        while (Rs2Player.getWorldLocation().distanceTo(target) > 4 && System.currentTimeMillis() < deadline) {
            Rs2Walker.walkTo(target, 3);
            sleep(600);
        }
        boolean arrived = Rs2Player.getWorldLocation().distanceTo(target) <= 4;
        if (!arrived) {
            log.error("[cerberus] did not reach the Key Master (at {})", Rs2Player.getWorldLocation());
        }
        return arrived;
    }

    private boolean enterFreeLair(CerberusState state) {
        CerberusContext ctx = state.context();
        Cave cave = listenForFreeCave();
        if (cave == null) {
            return false;
        }
        Rs2TileObjectModel winch = winchFor(cave);
        if (winch == null) {
            log.error("[cerberus] no Iron Winch found for the {} cave", cave);
            return false;
        }
        log.info("[cerberus] turning the {} cave's Iron Winch at {}", cave, winch.getWorldLocation());
        winch.click("Turn");
        // The winch drops us at the south end of the room, out of sight of her.
        if (!sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(CerberusConstants.KEY_MASTER_TILE)
                > CerberusConstants.LAIR_ENTERED_DISTANCE, STEP_TIMEOUT_MS)) {
            log.error("[cerberus] turned the winch but didn't enter the lair");
            return false;
        }
        sleep(1200, 1800);
        if (state.config().noGhosts() && autoRetaliateOn() && Rs2Combat.setAutoRetaliate(false)) {
            ctx.setRetaliateDisabledByUs(true); // no-ghosts: off until her 14th attack
            log.info("[cerberus] no-ghosts: auto-retaliate off");
        }
        drinkBoostPotions();
        return walkNorthToBoss(ctx);
    }

    /**
     * Boost before the walk up to her: every combat stat with a matching potion in the inventory (super
     * combat / divine for melee, ranging / bastion, magic / battlemage, single-stat supers). The client
     * helper skips stats that are already boosted, so a super combat covers Attack, Strength and Defence.
     */
    private void drinkBoostPotions() {
        for (Skill skill : BOOSTED_SKILLS) {
            int before = Rs2Player.getBoostedSkillLevel(skill);
            if (Rs2Player.drinkCombatPotionAt(skill)) {
                log.info("[cerberus] drank a {} boost", skill.getName());
                // Wait for the boost to land so the next stat sees it (and the potion delay passes).
                sleepUntil(() -> Rs2Player.getBoostedSkillLevel(skill) > before, 2400);
                sleep(600, 900);
            }
        }
    }

    /** From the lair entrance, run north (~25 tiles) until Cerberus is close. */
    private boolean walkNorthToBoss(CerberusContext ctx) {
        WorldPoint entrance = Rs2Player.getWorldLocation();
        ctx.setLairEntrance(entrance); // a no-ghosts reset walks back here, over the flames
        WorldPoint target = entrance.dy(CerberusConstants.LAIR_ENTRANCE_TO_BOSS_TILES);
        log.info("[cerberus] in the lair at {} — running north to {}", entrance, target);
        long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS * 2L;
        // Minimap clicks in short steps, not Rs2Walker.walkTo: inside the lair the web walker can't path
        // through the flame wall and may pick a teleport (it chose the house tablet on a no-ghosts reset).
        while (!bossNearby() && System.currentTimeMillis() < deadline) {
            WorldPoint here = Rs2Player.getWorldLocation();
            if (here.distanceTo(target) <= 2) {
                break;
            }
            WorldPoint step = here.dy(Math.min(MINIMAP_STEP_TILES, Math.max(1, target.getY() - here.getY())));
            Rs2Walker.walkMiniMap(step);
            sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(step) <= 1 || bossNearby(), 6000);
            sleep(300);
        }
        if (!sleepUntil(this::bossNearby, 3000)) {
            log.error("[cerberus] ran north from the entrance but Cerberus isn't nearby (at {})",
                    Rs2Player.getWorldLocation());
            return false;
        }
        return true;
    }

    /**
     * "Listen" to the Key Master and read every dialogue page (an intro page, then one page with a line per
     * cave). The first of north / east / west reported with no adventurers is returned.
     */
    private Cave listenForFreeCave() {
        log.info("[cerberus] listening to the Key Master");
        Boolean listened = Microbot.getClientThread().invoke((Supplier<Boolean>) () -> Microbot.getRs2NpcCache().query()
                .withName(CerberusConstants.KEY_MASTER_NAME).interact("Listen"));
        if (!Boolean.TRUE.equals(listened) || !Rs2Dialogue.sleepUntilInDialogue()) {
            log.error("[cerberus] could not 'Listen' to the Key Master");
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (int page = 0; page < MAX_DIALOGUE_PAGES && Rs2Dialogue.isInDialogue(); page++) {
            String text = Rs2Dialogue.getDialogueText();
            if (text != null && !text.isBlank() && (lines.isEmpty() || !lines.get(lines.size() - 1).equals(text))) {
                lines.add(text);
            }
            if (!Rs2Dialogue.hasContinue()) {
                break;
            }
            Rs2Dialogue.clickContinue();
            sleepUntil(() -> !Rs2Dialogue.isInDialogue() || !text.equals(Rs2Dialogue.getDialogueText()), 3000);
        }
        log.info("[cerberus] Key Master said: {}", lines);
        for (Cave cave : Cave.values()) {
            if (isFree(cave, lines)) {
                log.info("[cerberus] the {} cave is free", cave);
                return cave;
            }
        }
        log.error("[cerberus] couldn't find a free cave in the Key Master's lines (all occupied, or the wording "
                + "isn't recognised) — lines above");
        return null;
    }

    /**
     * A cave is free when its own line starts with "No adventurers". The Key Master says all three on ONE
     * page separated by {@code <br>} ("No adventurers are in the northern cave.<br>No adventurers are in the
     * western cave.<br>..."), so each page is split into lines before matching the direction.
     */
    private boolean isFree(Cave cave, List<String> pages) {
        String direction = cave.name().toLowerCase(Locale.ROOT);
        for (String page : pages) {
            for (String line : page.split("(?i)<br\\s*/?>")) {
                String text = line.replaceAll("<[^>]+>", "").trim().toLowerCase(Locale.ROOT);
                if (text.contains(direction)) {
                    return text.startsWith("no adventurers");
                }
            }
        }
        return false;
    }

    /** The three Iron Winches sit north / east / west; pick by position relative to each other. */
    private Rs2TileObjectModel winchFor(Cave cave) {
        List<Rs2TileObjectModel> winches = Microbot.getClientThread().invoke(
                (Supplier<List<Rs2TileObjectModel>>) () -> Microbot.getRs2TileObjectCache().query()
                        .withName(CerberusConstants.IRON_WINCH_NAME).toList());
        if (winches == null || winches.isEmpty()) {
            return null;
        }
        switch (cave) {
            case NORTH:
                return winches.stream().max(Comparator.comparingInt(w -> w.getWorldLocation().getY())).orElse(null);
            case EAST:
                return winches.stream().max(Comparator.comparingInt(w -> w.getWorldLocation().getX())).orElse(null);
            default:
                return winches.stream().min(Comparator.comparingInt(w -> w.getWorldLocation().getX())).orElse(null);
        }
    }

    private boolean bossNearby() {
        Boolean found = Microbot.getClientThread().invoke((Supplier<Boolean>) () -> Microbot.getRs2NpcCache().query()
                .withIds(CerberusConstants.CERBERUS_IDS).nearest(CerberusConstants.BOSS_NEARBY_RADIUS) != null);
        return Boolean.TRUE.equals(found);
    }

    /** Only record "switched off by us" when it was on — so stopping the plugin restores the player's choice. */
    private static boolean autoRetaliateOn() {
        return Microbot.getVarbitPlayerValue(VarPlayerID.OPTION_NODEF) == 0;
    }
}

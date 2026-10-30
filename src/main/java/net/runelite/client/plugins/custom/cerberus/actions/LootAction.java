package net.runelite.client.plugins.custom.cerberus.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.plugins.custom.cerberus.CerberusSupplies;
import net.runelite.client.plugins.custom.cerberus.constants.CerberusConstants;
import net.runelite.client.plugins.grounditems.GroundItem;
import net.runelite.client.plugins.microbot.util.grounditem.LootingParameters;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;

/**
 * After Cerberus dies: picks up everything she dropped except our own empty Butterfly jars (eating to make
 * room when the inventory is full),
 * then checks supplies. Enough for another kill -> back to FIGHT and wait in the room for her respawn;
 * otherwise -> RESTOCK.
 */
@Slf4j
public class LootAction implements CerberusAction {

    /** Grace for the drop to land after death. */
    private static final long INITIAL_LOOT_WAIT_MS = 3000;
    /** Deadline extension after each pickup, or while loot is still on the ground. */
    private static final long LOOT_EXTEND_MS = 1500;
    /** Hard cap on the whole loot phase, so an unreachable item can't stall the loop. */
    private static final long MAX_LOOT_MS = 15_000;

    @Override
    public int order() {
        return 700;
    }

    @Override
    public String key() {
        return "loot";
    }

    @Override
    public boolean needsExecution(CerberusState state) {
        return state.context().getPhase() == CerberusContext.Phase.LOOT;
    }

    @Override
    public Object execute(CerberusState state) {
        CerberusContext ctx = state.context();
        long now = System.currentTimeMillis();
        if (ctx.getLootStartMs() == 0) {
            ctx.setLootStartMs(now);
            ctx.setLootDeadlineMs(now + INITIAL_LOOT_WAIT_MS);
            // Kill done: prayers off first, then (no-ghosts) auto-retaliate off for the next kill, then loot.
            Rs2Prayer.disableAllPrayers();
            if (state.config().noGhosts() && autoRetaliateOn() && Rs2Combat.setAutoRetaliate(false)) {
                ctx.setRetaliateDisabledByUs(true);
            }
        }

        if (Rs2Inventory.isFull() && nearestLoot() != null && Rs2Player.eatAt(100, true)) {
            log.info("[cerberus] inventory full — eating to make space for loot");
            ctx.setLootDeadlineMs(now + LOOT_EXTEND_MS);
            return "eat-for-space";
        }

        // Everything except the empty jars we drop ourselves (they're tradeable, so the value pass would grab them).
        LootingParameters all = new LootingParameters(0, Integer.MAX_VALUE, CerberusConstants.LOOT_RANGE, 1, 0,
                false, false, new String[]{CerberusConstants.BUTTERFLY_JAR});
        boolean looted = Rs2GroundItem.lootItemBasedOnValue(all);
        looted |= Rs2GroundItem.lootUntradables(all);
        looted |= Rs2GroundItem.lootCoins(all);
        if (looted || (now - ctx.getLootStartMs() < MAX_LOOT_MS && nearestLoot() != null)) {
            ctx.setLootDeadlineMs(now + LOOT_EXTEND_MS);
            return "loot";
        }
        if (now < ctx.getLootDeadlineMs()) {
            return "loot-wait";
        }

        ctx.setLootStartMs(0);
        ctx.setLootDeadlineMs(0);
        int prayer = CerberusSupplies.totalPrayerPoints();
        int hp = CerberusSupplies.totalHitpoints();
        boolean resupply = CerberusSupplies.needsResupply(state.config());
        log.info("[cerberus] loot done — prayer total {} (min {}), hp total {} (min {}) -> {}", prayer,
                state.config().minPrayerPoints(), hp, state.config().minHitpoints(), resupply ? "resupply" : "next kill");
        ctx.setPhase(resupply ? CerberusContext.Phase.RESTOCK : CerberusContext.Phase.FIGHT);
        return resupply ? "restock" : "next-kill";
    }

    private GroundItem nearestLoot() {
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me == null) {
            return null;
        }
        GroundItem nearest = null;
        int best = Integer.MAX_VALUE;
        for (GroundItem item : Rs2GroundItem.getGroundItems().values()) {
            if (item == null || item.getLocation() == null
                    || CerberusConstants.BUTTERFLY_JAR.equalsIgnoreCase(item.getName())) {
                continue; // our own dropped jars aren't loot — don't keep waiting on them
            }
            int dist = item.getLocation().distanceTo(me);
            if (dist <= CerberusConstants.LOOT_RANGE && dist < best) {
                best = dist;
                nearest = item;
            }
        }
        return nearest;
    }

    /** Only record "switched off by us" when it was on — so stopping the plugin restores the player's choice. */
    private static boolean autoRetaliateOn() {
        return Microbot.getVarbitPlayerValue(VarPlayerID.OPTION_NODEF) == 0;
    }
}

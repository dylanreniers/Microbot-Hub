package net.runelite.client.plugins.custom.gotr.services;

import net.runelite.api.ItemID;
import net.runelite.api.NpcID;
import net.runelite.client.plugins.custom.gotr.GotrConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;

import javax.inject.Singleton;

/**
 * Service for handling pouch operations in GOTR
 */
@Singleton
public class PouchService {

    private boolean useNpcContact = true;

    /**
     * Sets whether to use NPC Contact spell for pouch repair
     */
    public void setUseNpcContact(boolean useNpcContact) {
        this.useNpcContact = useNpcContact;
    }

    /**
     * Checks pouches if any are unknown
     */
    public void checkPouches() {
        if (Rs2Inventory.anyPouchUnknown()) {
            Rs2Inventory.checkPouches();
            Global.sleep(Rs2Random.randomGaussian(1500, 300));
        }
    }

    /**
     * Repairs degraded pouches using the best available method
     * @return true if repair was attempted or completed
     */
    public boolean repairPouches() {
        if (!Rs2Inventory.hasDegradedPouch()) {
            return false;
        }

        if (useNpcContact) {
            return Rs2Magic.repairPouchesWithLunar();
        } else {
            repairWithCordelia();
            return true;
        }
    }

    /**
     * Fills pouches with essence if inventory is full and pouches are empty
     * @return true if pouches were filled
     */
    public boolean fillPouchesIfNeeded(int guardianPower) {
        if (Rs2Inventory.isFull() && Rs2Inventory.anyPouchEmpty() && guardianPower < 90) {
            Rs2Inventory.fillPouches();
            Global.sleep(Rs2Random.randomGaussian(600, 300));
            return true;
        }
        return false;
    }

    /**
     * Empties pouches if they are full and inventory has space
     */
    public void emptyPouchesIfNeeded() {
        if (Rs2Inventory.anyPouchFull() && !Rs2Inventory.isFull()) {
            Rs2Inventory.emptyPouches();
            Rs2Inventory.waitForInventoryChanges(5000);
            Global.sleep(Rs2Random.randomGaussian(350, 150));
        }
    }

    /**
     * Repairs pouch by talking to Cordelia
     * Requires the repair option to be unlocked for 25 pearls
     */
    private void repairWithCordelia() {
        if (!Rs2Inventory.hasDegradedPouch() || !Rs2Inventory.hasItem(ItemID.ABYSSAL_PEARLS)) {
            return;
        }

        Rs2NpcModel pouchRepairNpc = Rs2Npc.getNpc(NpcID.APPRENTICE_CORDELIA_12180);
        if (pouchRepairNpc == null || !Rs2Npc.hasAction(pouchRepairNpc.getId(), "Repair")) {
            return;
        }

        if (!Rs2Npc.canWalkTo(pouchRepairNpc, 10)) {
            return;
        }

        if (Rs2Npc.interact(pouchRepairNpc, "Repair")) {
            Microbot.log("Repairing pouches with Cordelia...");

            Global.sleepUntil(() -> {
                Rs2Dialogue.clickContinue();
                return !Rs2Inventory.hasDegradedPouch();
            }, 10000);
        }
    }
}

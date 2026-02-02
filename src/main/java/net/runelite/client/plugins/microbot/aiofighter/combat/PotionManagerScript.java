package net.runelite.client.plugins.microbot.aiofighter.combat;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.aiofighter.AIOFighterConfig;
import net.runelite.client.plugins.microbot.aiofighter.AIOFighterPlugin;
import net.runelite.client.plugins.microbot.aiofighter.enums.State;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2Potion;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.http.api.worlds.WorldType;

import java.util.concurrent.TimeUnit;

@Slf4j
public class PotionManagerScript extends Script {
    public boolean run(AIOFighterConfig config) {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;
                if(AIOFighterPlugin.getState() == State.GETTING_TASK) return;

                // Always attempt to drink anti-poison
                if (Rs2Player.drinkAntiPoisonPotion()) {
                    Rs2Player.waitForAnimation();
                }

                // Always attempt to drink antifire potion
                if (Rs2Player.drinkAntiFirePotion()) {
                    Rs2Player.waitForAnimation();
                }

                log.info("Drinking prayer potion?");
                // Always attempt to drink prayer potion
                if (Rs2Player.drinkPrayerPotion()) {
                    log.info("Drank prayer potion");
                    Rs2Player.waitForAnimation();
                }

                drinkPrayerPot();

                // Only drink combat potions when in combat
                if (Rs2Combat.inCombat()) {
                    // Attempt to drink ranging potion
                    if (Rs2Player.drinkCombatPotionAt(Skill.RANGED, false)) {
                        Rs2Player.waitForAnimation();
                    }

                    // Attempt to drink magic potion
                    if (Rs2Player.drinkCombatPotionAt(Skill.MAGIC, false)) {
                        Rs2Player.waitForAnimation();
                    }

                    // Attempt to drink combat potions for STR, ATT, DEF
                    if (Rs2Player.drinkCombatPotionAt(Skill.STRENGTH)) {
                        Rs2Player.waitForAnimation();
                    }
                    if (Rs2Player.drinkCombatPotionAt(Skill.ATTACK)) {
                        Rs2Player.waitForAnimation();
                    }
                    if (Rs2Player.drinkCombatPotionAt(Skill.DEFENCE)) {
                        Rs2Player.waitForAnimation();
                    }
                }

                // Always attempt to drink goading potion
                if (Rs2Player.drinkGoadingPotion()) {
                    Rs2Player.waitForAnimation();
                }

                if(Rs2Inventory.hasItem(ItemID.VIAL_EMPTY)) {
                    Rs2Inventory.dropAll(ItemID.VIAL_EMPTY);
                    Rs2Inventory.waitForInventoryChanges(1000);
                }

            } catch (Exception ex) {
                Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    public void drinkPrayerPot() {
        if (Rs2Player.getBoostedSkillLevel(Skill.PRAYER) <= Rs2Random.between(8, 15)) {
            if (Rs2Inventory.contains(it -> it != null && it.getName().contains("Prayer potion") || it.getName().contains("Moonlight moth"))) {
                Rs2ItemModel prayerpotion = Rs2Inventory.get(it -> it != null && it.getName().contains("Prayer potion") || it.getName().contains("Moonlight moth"));
                String action = "Drink";
                if (prayerpotion.getName().equals("Moonlight moth")) {
                    action = "Release";
                }

                if (Rs2Inventory.interact(prayerpotion, action)) {
                    sleep(0, 750);
                }

                Rs2Inventory.dropAll("Butterfly jar");
            }
        }
    }

    // shutdown
    @Override
    public void shutdown() {
        super.shutdown();
    }

}

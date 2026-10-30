package net.runelite.client.plugins.custom.cerberus;

import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.misc.Rs2Food;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Estimates how much prayer and HP we can still bring to bear (current points plus everything in the
 * inventory), to decide between kills whether another kill is sustainable or we should resupply.
 */
public final class CerberusSupplies {

    private static final Pattern DOSES = Pattern.compile("\\((\\d)\\)");
    /** Heal for food not in {@link Rs2Food}. */
    private static final int DEFAULT_FOOD_HEAL = 20;

    private CerberusSupplies() {
    }

    /** Current prayer + every prayer dose / moonlight moth in the inventory. */
    public static int totalPrayerPoints() {
        int level = Rs2Player.getRealSkillLevel(Skill.PRAYER);
        int prayerPotionDose = 7 + level / 4;
        int restoreDose = 8 + level / 4;
        int total = Rs2Player.getBoostedSkillLevel(Skill.PRAYER);
        for (Rs2ItemModel item : (Iterable<Rs2ItemModel>) Rs2Inventory.items()::iterator) {
            String name = item.getName() == null ? "" : item.getName().toLowerCase();
            if (name.contains("super restore") || name.contains("sanfew serum")) {
                total += restoreDose * doses(name) * Math.max(1, item.getQuantity());
            } else if (name.contains("prayer potion") || name.contains("moonlight moth mix")) {
                total += prayerPotionDose * doses(name) * Math.max(1, item.getQuantity());
            } else if (name.equals("moonlight moth")) {
                // Released for prayer; counted as one prayer-potion dose.
                total += prayerPotionDose * Math.max(1, item.getQuantity());
            }
        }
        return total;
    }

    /** Current HP + the heal of every food in the inventory. */
    public static int totalHitpoints() {
        int total = Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS);
        for (Rs2ItemModel food : Rs2Inventory.getInventoryFood()) {
            total += healOf(food.getId()) * Math.max(1, food.getQuantity());
        }
        return total;
    }

    /** True when either total is at or below its configured minimum. */
    public static boolean needsResupply(CerberusConfig config) {
        return totalPrayerPoints() <= config.minPrayerPoints() || totalHitpoints() < config.minHitpoints();
    }

    private static int doses(String name) {
        Matcher m = DOSES.matcher(name);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    private static int healOf(int itemId) {
        for (Rs2Food food : Rs2Food.values()) {
            if (food.getId() == itemId) {
                return food.getHeal();
            }
        }
        return DEFAULT_FOOD_HEAL;
    }
}

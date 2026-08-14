package net.runelite.client.plugins.custom.zulrah.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

@Slf4j
public class DrinkPrayerAction implements ZulrahAction {

    private static final int PRAYER_THRESHOLD = 20;

    @Override
    public int order() {
        return 200;
    }

    @Override
    public String key() {
        return "drink-prayer";
    }

    @Override
    public boolean needsExecution(ZulrahState state) {
        return Rs2Player.getPrayerPercentage() <= PRAYER_THRESHOLD;
    }

    /**
     * Restores prayer preferring a caught moonlight moth over a potion dose: releasing a moth restores
     * prayer for free, so we burn those first and only fall back to prayer potions once we're out. The
     * released moth leaves an empty butterfly jar behind; {@link DropButterflyJarAction} clears that on a
     * later tick, so we don't sleep here to wait for it.
     */
    @Override
    public Object execute(ZulrahState state) {
        if (Rs2Inventory.hasItem(ItemID.BUTTERFLY_JAR_MOONMOTH)) {
            log.info("Releasing moonlight moth to restore prayer.");
            return Rs2Inventory.interact(ItemID.BUTTERFLY_JAR_MOONMOTH, "Release");
        }
        log.info("No moonlight moth available; drinking prayer potion.");
        return Rs2Player.drinkPrayerPotionAt(PRAYER_THRESHOLD);
    }
}

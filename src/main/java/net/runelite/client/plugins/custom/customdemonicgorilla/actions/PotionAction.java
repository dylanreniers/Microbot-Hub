package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.State;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.misc.Rs2Potion;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.List;

/**
 * FIGHTING phase: keeps combat boosts topped up — drinks combat/attack/strength/range potions
 * whenever the boosted stat drops below the configured threshold. Corresponds to the old
 * {@code evaluateAndConsumePotions}.
 */
@Slf4j
public class PotionAction implements GorillaAction {

    @Override
    public int order() {
        return 800;
    }

    @Override
    public String key() {
        return "potion";
    }

    @Override
    public boolean needsExecution(GorillaState state) {
        GorillaContext ctx = state.context();
        return ctx.getBotStatus() == State.FIGHTING && ctx.getCurrentTarget() != null;
    }

    @Override
    public Object execute(GorillaState state) {
        int threshold = state.config().boostedStatsThreshold();

        if (!isCombatPotionActive(threshold)) {
            consumePotion(Rs2Potion.getCombatPotionsVariants());
        }
        if (!Rs2Player.hasAttackActive(threshold)) {
            consumePotion(Rs2Potion.getAttackPotionsVariants());
        }
        if (!Rs2Player.hasStrengthActive(threshold)) {
            consumePotion(Rs2Potion.getStrengthPotionsVariants());
        }
        if (!isRangingPotionActive(threshold)) {
            consumePotion(Rs2Potion.getRangePotionsVariants());
        }
        return null;
    }

    private boolean isCombatPotionActive(int threshold) {
        return Rs2Player.hasDivineCombatActive() || (Rs2Player.hasAttackActive(threshold) && Rs2Player.hasStrengthActive(threshold));
    }

    private boolean isRangingPotionActive(int threshold) {
        return Rs2Player.hasRangingPotionActive(threshold) || Rs2Player.hasDivineBastionActive() || Rs2Player.hasDivineRangedActive();
    }

    private void consumePotion(List<String> keyword) {
        var potion = Rs2Inventory.get(keyword.toArray(String[]::new));
        if (potion != null) {
            Rs2Inventory.interact(potion, "Drink");
        }
    }
}

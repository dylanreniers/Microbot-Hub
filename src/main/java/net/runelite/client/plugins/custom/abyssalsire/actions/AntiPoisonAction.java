package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.misc.Rs2Potion;

import java.util.List;

/**
 * Keeps poison/venom immunity up during the fight. The Sire and its miasma pools apply poison/venom,
 * so we drink an antidote when we have no immunity (anti-venom preferred). A short cooldown avoids
 * re-drinking while the status varp is still catching up.
 *
 * <p>Immunity is read from the {@link VarPlayerID#POISON} varp directly, NOT from
 * {@code Rs2Player.hasAntiPoisonActive()/hasAntiVenomActive()}: the varp is <b>negative</b> while any
 * poison/venom immunity is up, 0 when clear, positive when poisoned/venomed. The client's helpers only
 * recognise venom-tier immunity (varp &lt; -38) or an active poison (varp &gt; 0), so mid-tier antidote
 * immunity (e.g. antidote++ ≈ -24) looks like "nothing active" to them — which made us sip the whole
 * potion.
 */
@Slf4j
public class AntiPoisonAction implements SireAction {

    private static final long DRINK_COOLDOWN_MS = 3000;

    @Override
    public int order() {
        return 150;
    }

    @Override
    public String key() {
        return "anti-poison";
    }

    @Override
    public boolean needsExecution(SireState state) {
        SireContext ctx = state.context();
        if (ctx.getPhase() == SirePhase.IDLE || ctx.getPhase() == SirePhase.DEAD) {
            return false;
        }
        if (System.currentTimeMillis() < ctx.getNextAntipoisonMs()) {
            return false;
        }
        // Negative varp = poison/venom immunity is up -> nothing to do. Drink only when it's 0 (no
        // immunity) or positive (actively poisoned/venomed).
        return Microbot.getVarbitPlayerValue(VarPlayerID.POISON) >= 0;
    }

    @Override
    public Object execute(SireState state) {
        state.context().setNextAntipoisonMs(System.currentTimeMillis() + DRINK_COOLDOWN_MS);

        List<String> antiVenom = Rs2Potion.getAntiVenomVariants();
        if (Rs2Inventory.hasItem(antiVenom.toArray(new String[0]))) {
            log.info("[sire] drinking anti-venom");
            return Rs2Inventory.interact(antiVenom.toArray(new String[0]), "Drink");
        }
        List<String> antiPoison = Rs2Potion.getAntiPoisonVariants();
        if (Rs2Inventory.hasItem(antiPoison.toArray(new String[0]))) {
            log.info("[sire] drinking antipoison");
            return Rs2Inventory.interact(antiPoison.toArray(new String[0]), "Drink");
        }
        return false;
    }
}

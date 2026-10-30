package net.runelite.client.plugins.custom.cerberus.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.cerberus.constants.CerberusConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.function.Supplier;

/**
 * (Re)attacks Cerberus whenever we're not already interacting with her — e.g. after a lava dodge broke
 * the interaction or eating/drinking interrupted it. Holds while moving or standing in lava so it never
 * cancels a dodge walk, and on the few ticks right before a protection flick so its mouse gesture can't
 * delay the flick (see {@code CerberusPlugin#isFlickImminent}).
 */
@Slf4j
public class AttackAction implements CerberusAction {

    @Override
    public int order() {
        return 500;
    }

    @Override
    public String key() {
        return "attack";
    }

    @Override
    public boolean needsExecution(CerberusState state) {
        CerberusContext ctx = state.context();
        if (!ctx.isFightActive() || ctx.isEngaged() || ctx.isPrayerCritical() || ctx.isHoldingDamage()
                || Rs2Player.isMoving()) {
            return false;
        }
        WorldPoint player = Rs2Player.getWorldLocation();
        return player != null && !ctx.lavaTiles().contains(player);
    }

    @Override
    public Object execute(CerberusState state) {
        if (!state.claimClick()) {
            return false;
        }
        Boolean attacked = Microbot.getClientThread().invoke((Supplier<Boolean>) () -> Microbot.getRs2NpcCache().query()
                .withId(CerberusConstants.CERBERUS_ATTACKABLE_ID)
                .interact("Attack"));
        log.info("[cerberus] re-attack -> {}", attacked);
        return attacked;
    }
}

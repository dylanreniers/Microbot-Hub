package net.runelite.client.plugins.microbot.moonsofperil.handlers;

import net.runelite.client.plugins.microbot.moonsofperil.MoonsOfPerilConfig;
import net.runelite.client.plugins.microbot.moonsofperil.enums.State;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;

public class LogoutHandler implements BaseHandler {

    private MoonsOfPerilConfig cfg;

    public LogoutHandler(MoonsOfPerilConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public boolean validate() {
        return this.cfg.numberOfChests() > 0 && RewardHandler.getRewardChestCount().get() == this.cfg.numberOfChests();
    }

    @Override
    public State execute() {
        Rs2Player.logout();
        return null;
    }
}

package net.runelite.client.plugins.custom.karambwans;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.enums.Activity;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import javax.inject.Inject;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.custom.karambwans.KarambwansInfo.botStatus;
import static net.runelite.client.plugins.custom.karambwans.KarambwansInfo.states;

@Slf4j
public class KarambwansScript extends Script {

    @Inject
    private KarambwanLocationService karambwanLocationService;

    @Inject
    private KarambwanBankService karambwanBankService;

    public boolean run(KarambwansConfig config) {
        Microbot.enableAutoRunOn = false;
        Rs2Antiban.setActivity(Activity.CATCHING_RAW_KARAMBWAN);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;


                switch (botStatus) {
                    case FISHING:
                        fishingLoop();
                        Rs2Antiban.takeMicroBreakByChance();
                        botStatus = states.WALKING_TO_BANK;
                        Rs2Player.waitForAnimation();
                        break;
                    case WALKING_TO_BANK:
                        karambwanLocationService.teleportToCastleWars();
                        botStatus = states.BANKING;
                        Rs2Random.waitEx(400, 200);
                        break;
                    case BANKING:
                        karambwanBankService.handleBanking();
                        botStatus = states.WALKING_TO_FISH;
                        Rs2Random.waitEx(400, 200);
                        break;
                    case WALKING_TO_FISH:
                        karambwanLocationService.teleportToHouse();
                        karambwanLocationService.teleportToKarambwanFishingSpot();
                        botStatus = states.FISHING;
                        Rs2Random.waitEx(400, 200);
                        break;
                }
            } catch (Exception ex) {
                Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }

    private void fishingLoop() {
        while (!Rs2Inventory.isFull() && super.isRunning()) {
            if (!Rs2Player.isInteracting() || !Rs2Player.isAnimating()) {
                if (Rs2Inventory.contains(ItemID.TBWT_RAW_KARAMBWANJI)) {
                    interactWithFishingSpot();
                    Rs2Player.waitForAnimation();
                    sleep(2000, 4000);
                } else {
                    Microbot.showMessage("Raw karambwanji not detected. Shutting down");
                    shutdown();
                    return;
                }
            }
        }
    }

    private void interactWithFishingSpot() {
        Rs2Npc.interact(NpcID._0_45_48_KARAMBWAN, "Fish");
    }
}


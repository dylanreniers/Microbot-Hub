package net.runelite.client.plugins.microbot.sailing.features;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.sailing.SailingConfig;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2PlayerModel;

import javax.inject.Inject;
import java.util.Arrays;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

@Slf4j
public class SalvagingFeature {

    @Inject
    Rs2TileObjectCache rs2TileObjectCache;

    public void run(SailingConfig config) {
        try {
            var shipwreck = rs2TileObjectCache.query()
                    .where(x -> x.getName() != null && x.getName().toLowerCase().contains("shipwreck"))
                    .within(5)
                    .nearestOnClientThread();
            var player = new Rs2PlayerModel();

            var isInvFull = Rs2Inventory.count() >= Rs2Random.between(24, 28);
            if (isInvFull && Rs2Inventory.count("salvage") > 0 && player.getAnimation() == -1) {
                // Rs2Inventory.dropAll("large salvage");
                rs2TileObjectCache.query()
                        .fromWorldView()
                        .where(x -> x.getName() != null && x.getName().equalsIgnoreCase("salvaging station"))
                        .where(x -> x.getWorldView().getId() == new Rs2PlayerModel().getWorldView().getId())
                        .nearestOnClientThread()
                        .click();
                sleepUntil(() -> Rs2Inventory.count("salvage") == 0, 20000);
            } else if (isInvFull) {
                dropJunk(config);
            } else {
                if (player.getAnimation() != -1) {
                    log.info("Currently salvaging, waiting...");
                    sleep(5000, 10000);
                    return;
                }

                if (shipwreck == null) {
                    log.info("No shipwreck found nearby");
                    sleep(5000);
                    dropJunk(config);
                    return;
                }

                rs2TileObjectCache.query().fromWorldView().where(x -> x.getName() != null && x.getName().toLowerCase().contains("salvaging hook")).nearestOnClientThread().click("Deploy");
                sleepUntil(() -> player.getAnimation() != -1, 5000);

            }
        } catch (Exception ex) {
            log.error("Error in performance test loop", ex);
        }
    }

    private void dropJunk(SailingConfig config) {
        var dropItems = config.dropItems();
        if (dropItems == null || dropItems.isBlank()) {
            return;
        }

        var junkItems = Arrays.stream(dropItems.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toArray(String[]::new);

        if (junkItems.length == 0) {
            return;
        }

        Rs2Inventory.dropAll(junkItems);
    }
}

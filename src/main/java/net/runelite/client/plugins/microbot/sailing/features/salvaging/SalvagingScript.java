package net.runelite.client.plugins.microbot.sailing.features.salvaging;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.api.boat.Rs2BoatCache;
import net.runelite.client.plugins.microbot.api.player.models.Rs2PlayerModel;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.sailing.SailingConfig;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

@Slf4j
public class SalvagingScript {

    private static final int SIZE_SALVAGEABLE_AREA = 15;
    private static final int MIN_INVENTORY_FULL = 24;
    private static final int MAX_INVENTORY_FULL = 28;
    private static final int SALVAGE_TIMEOUT = 20000;
    private static final int DEPLOY_TIMEOUT = 5000;
    private static final int WAIT_TIME = 5000;
    private static final int WAIT_TIME_MAX = 10000;

    private final Rs2TileObjectCache tileObjectCache;
    private final Rs2BoatCache boatCache;

    @Inject
    public SalvagingScript(Rs2TileObjectCache tileObjectCache, Rs2BoatCache boatCache) {
        this.tileObjectCache = tileObjectCache;
        this.boatCache = boatCache;
    }

    public List<Rs2TileObjectModel> getActiveWrecks() {
        return tileObjectCache.query()
                .where(wreck -> SalvageObjectIds.ACTIVE_SHIPWRECK_IDS.contains(wreck.getId()))
                .toListOnClientThread();
    }

    public List<Rs2TileObjectModel> getInactiveWrecks() {
        return tileObjectCache.query()
                .where(wreck -> SalvageObjectIds.INACTIVE_SHIPWRECK_IDS.contains(wreck.getId()))
                .toListOnClientThread();
    }

    public void run(SailingConfig config) {
        try {
            var player = new Rs2PlayerModel();

            if (isPlayerAnimating(player)) {
                log.info("Currently salvaging, waiting...");
                sleep(WAIT_TIME, WAIT_TIME_MAX);
                return;
            }

            var nearbyWreck = findNearestWreck(player.getWorldLocation());
            if (nearbyWreck == null) {
                log.info("No shipwreck found nearby");
                sleep(WAIT_TIME);
                dropJunk(config);
                return;
            }

            if (isInventoryFull()) {
                handleFullInventory(config, player);
            } else {
                deploySalvagingHook(player);
            }

        } catch (Exception ex) {
            log.error("Error in salvaging script", ex);
        }
    }

    private boolean isPlayerAnimating(Rs2PlayerModel player) {
        return player.getAnimation() != -1;
    }

    private boolean isInventoryFull() {
        return Rs2Inventory.count() >= Rs2Random.between(MIN_INVENTORY_FULL, MAX_INVENTORY_FULL);
    }

    private boolean hasSalvageItems() {
        return Rs2Inventory.count("salvage") > 0;
    }

    private Rs2TileObjectModel findNearestWreck(WorldPoint playerLocation) {
        var activeWrecks = getActiveWrecks();

        if (activeWrecks.isEmpty()) {
            log.info("No active shipwrecks found");
            sleep(WAIT_TIME);
            return null;
        }

        return activeWrecks.stream()
                .filter(wreck -> isWithinSalvageArea(playerLocation, wreck))
                .min(Comparator.comparingInt(wreck -> playerLocation.distanceTo(wreck.getWorldLocation())))
                .orElse(null);
    }

    private boolean isWithinSalvageArea(WorldPoint playerLocation, Rs2TileObjectModel wreck) {
        return playerLocation.distanceTo(wreck.getWorldLocation()) <= SIZE_SALVAGEABLE_AREA;
    }

    private void handleFullInventory(SailingConfig config, Rs2PlayerModel player) {
        if (hasSalvageItems() && !isPlayerAnimating(player)) {
            depositSalvageOrDrop(config);
        } else {
            if (config.enableAlching()) {
                alchItems(config);
            }
            dropJunk(config);
        }
    }

    private void depositSalvageOrDrop(SailingConfig config) {
        var salvagingStation = findSalvagingStation();

        if (salvagingStation != null) {
            depositAtStation(salvagingStation);
        } else {
            log.info("No salvaging station found, dropping junk items");
            dropJunk(config);
        }
    }

    private Rs2TileObjectModel findSalvagingStation() {
        var playerWorldView = new Rs2PlayerModel().getWorldView().getId();

        return tileObjectCache.query()
                .fromWorldView()
                .where(obj -> obj.getName() != null && obj.getName().equalsIgnoreCase("salvaging station"))
                .where(obj -> obj.getWorldView().getId() == playerWorldView)
                .nearestOnClientThread();
    }

    private void depositAtStation(Rs2TileObjectModel station) {
        station.click();
        sleepUntil(() -> !hasSalvageItems(), SALVAGE_TIMEOUT);
    }

    private void deploySalvagingHook(Rs2PlayerModel player) {
        var hook = tileObjectCache.query()
                .fromWorldView()
                .where(obj -> obj.getName() != null && obj.getName().toLowerCase().contains("salvaging hook"))
                .nearestOnClientThread();

        if (hook != null) {
            hook.click("Deploy");
            sleepUntil(() -> isPlayerAnimating(player), DEPLOY_TIMEOUT);
        }
    }

    private void alchItems(SailingConfig config) {
        var alchItems = config.alchItems();
        if (alchItems == null || alchItems.isBlank()) {
            return;
        }

        var itemsToAlch = Arrays.stream(alchItems.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toArray(String[]::new);

        for (String itemName : itemsToAlch) {
            if (Rs2Inventory.hasItem(itemName)) {
                Rs2Magic.alch(itemName);
                Rs2Player.waitForXpDrop(Skill.MAGIC, 10000, false);
            }
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

        if (junkItems.length > 0) {
            Rs2Inventory.dropAll(junkItems);
        }
    }
}

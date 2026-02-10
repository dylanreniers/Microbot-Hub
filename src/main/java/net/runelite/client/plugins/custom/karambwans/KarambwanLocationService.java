package net.runelite.client.plugins.custom.karambwans;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.IEntity;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;

import javax.inject.Inject;
import javax.inject.Singleton;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

@Singleton
@Slf4j
public class KarambwanLocationService {

    public static final String CASTLE_WARS = "Castle Wars";
    private static final int POH_PORTAL_ID = 4525;
    private static final int FAIRY_RING_ID = 29228;
    @Inject
    private Rs2TileObjectCache rs2TileObjectCache;

    public static boolean isNearCastleWars() {
        return Rs2Player.getWorldLocation().distanceTo(BankLocation.CASTLE_WARS.getWorldPoint()) <= 50;
    }

    public void teleportToCastleWars() {
        log.info("Teleporting to castle wars...");
        Rs2Equipment.interact(EquipmentInventorySlot.RING, CASTLE_WARS);
        sleepUntil(() -> !Rs2Player.isAnimating());
        sleepUntil(KarambwanLocationService::isNearCastleWars);
    }

    public void teleportToHouse() {
        log.info("Teleporting to house.");
        Rs2Magic.cast(MagicAction.TELEPORT_TO_HOUSE);
        sleepUntil(Rs2Player::isAnimating);
        sleepUntil(() -> !Rs2Player.isAnimating());
        sleep(600, 1200);
        log.info("Waiting until POH portal can be found.");
        sleepUntil(() -> rs2TileObjectCache.query()
                .where(IEntity::isReachable)
                .where(object -> object.getId() == POH_PORTAL_ID)
                .nearestOnClientThread(40) != null);
    }

    public void teleportToKarambwanFishingSpot() {
        Rs2TileObjectModel fairyRing = rs2TileObjectCache.query()
                .where(IEntity::isReachable)
                .where(object -> object.getId() == FAIRY_RING_ID)
                .nearestOnClientThread(40);

        Microbot.getClientThread().invoke(() -> fairyRing.click("Last-destination (DKP)"));
        sleepUntil(Rs2Player::isAnimating);
        sleepUntil(() -> !Rs2Player.isAnimating());
        sleep(600, 1200);
    }
}

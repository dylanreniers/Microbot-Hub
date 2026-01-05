package net.runelite.client.plugins.microbot.custom.barrows.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.api.IEntity;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.custom.barrows.BarrowsScript;
import net.runelite.client.plugins.microbot.custom.barrows.BarrowsScriptException;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

@Slf4j
@RequiredArgsConstructor
public class LocationService {

    private static final int POH_PORTAL_ID = 4525;
    private static final String BARROWS_PORTAL = "Barrows Portal";
    private static final String ENTER = "Enter";
    private static final String FEROX_ENCLAVE = "Ferox Enclave";
    private static final int BARROWS_MIN_Y = 9600;
    private static final int BARROWS_MAX_Y = 9730;

    private final Rs2TileObjectCache rs2TileObjectCache;

    private void teleportToHouse() {
        log.info("Teleporting to house.");
        Rs2Magic.cast(MagicAction.TELEPORT_TO_HOUSE);
        sleepUntil(Rs2Player::isAnimating);
        sleepUntil(() -> !Rs2Player.isAnimating());
        sleep(600, 1200);
        log.info("Waiting until POH portal can no longer be found.");
        sleepUntil(() -> rs2TileObjectCache.query()
                .where(IEntity::isReachable)
                .where(object -> object.getId() == POH_PORTAL_ID)
                .nearestOnClientThread(40) == null);
    }

    public void handleTravelToBarrows() {
        teleportToHouse();

        log.info("Looking for Barrows portal.");
        Rs2TileObjectModel barrowsPortal = rs2TileObjectCache
                .query()
                .withName(BARROWS_PORTAL)
                .nearestOnClientThread(40);

        if (barrowsPortal == null) {
            log.info("Barrows Portal not found.");
            throw new BarrowsScriptException("No barrows portal found in POH.");
        }

        log.info("Entering barrows portal.");

        barrowsPortal.click(ENTER);

        sleepUntil(Rs2Player::isMoving); //TODO: better would be to check the coordinates of the player
        sleepUntil(() -> !Rs2Player.isMoving());
        sleep(600, 1800);
    }

    public void teleportToFerox() {
        if (!isNearFerox()) {
            Rs2Equipment.interact(EquipmentInventorySlot.RING, FEROX_ENCLAVE);
            sleepUntil(() -> !Rs2Player.isAnimating());
            sleepUntil(LocationService::isNearFerox);
        }
    }

    public static boolean isNearFerox() {
        return Rs2Player.getWorldLocation().distanceTo(BankLocation.FEROX_ENCLAVE.getWorldPoint()) <= 50;
    }

    public boolean needsTravelToBarrows() {
        if (isInsideMounds() || isInBarrowsTunnel()) {
            return false;
        }

        WorldPoint barrowsArea = new WorldPoint(3573, 3296, 0);
        return Rs2Player.getWorldLocation().distanceTo(barrowsArea) > 60;
    }

    public boolean isInBarrowsTunnel() {
        int y = Rs2Player.getWorldLocation().getY();
        return y > BARROWS_MIN_Y && y < BARROWS_MAX_Y && Rs2Player.getWorldLocation().getPlane() == 0;
    }

    private boolean isInsideMounds() {
        int y = Rs2Player.getWorldLocation().getY();
        return y > BARROWS_MIN_Y && y < BARROWS_MAX_Y && Rs2Player.getWorldLocation().getPlane() == 3;
    }
}

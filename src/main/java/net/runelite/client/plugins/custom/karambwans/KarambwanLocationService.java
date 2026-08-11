package net.runelite.client.plugins.custom.karambwans;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.IEntity;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
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

    // Fairy ring destination for the karambwan fishing spot on Karamja.
    private static final String KARAMBWAN_FAIRY_CODE = "DKP";
    // The POH fairy ring's menu option is just "Last-destination" (no code suffix); the
    // remembered code lives in the dial varbits below instead.
    private static final String LAST_DESTINATION_ACTION = "Last-destination";

    // Fairy ring dial letters per varbit value (from FairyRingPlugin): value -> letter.
    private static final String[] LEFT_DIAL = {"A", "D", "C", "B"};
    private static final String[] MIDDLE_DIAL = {"I", "L", "K", "J"};
    private static final String[] RIGHT_DIAL = {"P", "S", "R", "Q"};

    // Fairy ring "Configure" interface widgets (mirrors Rs2Walker#handleFairyRing).
    private static final int SLOT_ONE = 26083331;
    private static final int SLOT_TWO = 26083332;
    private static final int SLOT_THREE = 26083333;
    private static final int SLOT_ONE_CW_ROTATION = 26083347;
    private static final int SLOT_ONE_ACW_ROTATION = 26083348;
    private static final int SLOT_TWO_CW_ROTATION = 26083349;
    private static final int SLOT_TWO_ACW_ROTATION = 26083350;
    private static final int SLOT_THREE_CW_ROTATION = 26083351;
    private static final int SLOT_THREE_ACW_ROTATION = 26083352;

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
        sleepUntil(() -> Microbot.getRs2TileObjectCache().query()
                .where(IEntity::isReachable)
                .where(object -> object.getId() == POH_PORTAL_ID)
                .nearestOnClientThread(40) != null);
    }

    public void teleportToKarambwanFishingSpot() {
        Rs2TileObjectModel fairyRing = Microbot.getRs2TileObjectCache().query()
                .where(IEntity::isReachable)
                .where(object -> object.getId() == FAIRY_RING_ID)
                .nearestOnClientThread(40);

        if (fairyRing == null) {
            log.warn("Could not find a reachable fairy ring near the house.");
            return;
        }

        // The ring's first menu option is "Zanaris", so clicking a missing action silently
        // teleports there. Only use the Last-destination shortcut when the ring's remembered
        // code is already DKP; otherwise dial it in manually.
        if (isRememberedDestination(KARAMBWAN_FAIRY_CODE)) {
            log.info("Fairy ring already remembers {}, using Last-destination.", KARAMBWAN_FAIRY_CODE);
            Microbot.getClientThread().invoke(() -> fairyRing.click(LAST_DESTINATION_ACTION));
        } else {
            log.info("Fairy ring not set to {}, configuring.", KARAMBWAN_FAIRY_CODE);
            configureFairyRingToKarambwan(fairyRing);
        }

        sleepUntil(Rs2Player::isAnimating);
        sleepUntil(() -> !Rs2Player.isAnimating());
        sleep(600, 1200);
    }

    /**
     * The POH fairy ring exposes only a plain "Last-destination" option; the remembered code is
     * held in the three dial varbits. Decodes them the same way FairyRingPlugin does and compares
     * against the requested code (e.g. "DKP").
     */
    private boolean isRememberedDestination(String code) {
        int left = Microbot.getVarbitValue(VarbitID.FAIRYRING_1);
        int middle = Microbot.getVarbitValue(VarbitID.FAIRYRING_2);
        int right = Microbot.getVarbitValue(VarbitID.FAIRYRING_3);
        if (left < 0 || left >= LEFT_DIAL.length
                || middle < 0 || middle >= MIDDLE_DIAL.length
                || right < 0 || right >= RIGHT_DIAL.length) {
            return false;
        }

        String remembered = LEFT_DIAL[left] + MIDDLE_DIAL[middle] + RIGHT_DIAL[right];
        return code.equalsIgnoreCase(remembered);
    }

    private void configureFairyRingToKarambwan(Rs2TileObjectModel fairyRing) {
        Microbot.getClientThread().invoke(() -> fairyRing.click("Configure"));
        if (!sleepUntil(() -> !Rs2Widget.isHidden(ComponentID.FAIRY_RING_TELEPORT_BUTTON), 10000)) {
            log.warn("Fairy ring configure interface did not open.");
            return;
        }

        rotateSlotToDesiredRotation(SLOT_ONE, getDesiredRotation(KARAMBWAN_FAIRY_CODE.charAt(0)),
                SLOT_ONE_ACW_ROTATION, SLOT_ONE_CW_ROTATION);
        rotateSlotToDesiredRotation(SLOT_TWO, getDesiredRotation(KARAMBWAN_FAIRY_CODE.charAt(1)),
                SLOT_TWO_ACW_ROTATION, SLOT_TWO_CW_ROTATION);
        rotateSlotToDesiredRotation(SLOT_THREE, getDesiredRotation(KARAMBWAN_FAIRY_CODE.charAt(2)),
                SLOT_THREE_ACW_ROTATION, SLOT_THREE_CW_ROTATION);
        Rs2Widget.clickWidget(ComponentID.FAIRY_RING_TELEPORT_BUTTON);
    }

    private void rotateSlotToDesiredRotation(int slotId, int desiredRotation, int slotAcwRotationId, int slotCwRotationId) {
        Widget slot = Rs2Widget.getWidget(slotId);
        if (slot == null || desiredRotation < 0) {
            return;
        }

        int currentRotation = slot.getRotationY();
        int anticlockwiseTurns = (desiredRotation - currentRotation + 2048) % 2048;
        int clockwiseTurns = (currentRotation - desiredRotation + 2048) % 2048;

        int turns = Math.min(clockwiseTurns, anticlockwiseTurns) / 512;
        boolean rotateCW = clockwiseTurns <= anticlockwiseTurns;
        int rotationWidget = rotateCW ? slotCwRotationId : slotAcwRotationId;

        for (int i = 0; i < turns; i++) {
            final int previousRotation = currentRotation;
            Rs2Widget.clickWidget(rotationWidget);
            sleepUntil(() -> {
                Widget slotWidget = Rs2Widget.getWidget(slotId);
                return slotWidget != null && slotWidget.getRotationY() != previousRotation;
            }, 2000);

            Widget slotWidget = Rs2Widget.getWidget(slotId);
            if (slotWidget == null) {
                break;
            }
            currentRotation = slotWidget.getRotationY();
        }
    }

    private int getDesiredRotation(char letter) {
        switch (letter) {
            case 'A':
            case 'I':
            case 'P':
                return 0;
            case 'B':
            case 'J':
            case 'Q':
                return 512;
            case 'C':
            case 'K':
            case 'R':
                return 1024;
            case 'D':
            case 'L':
            case 'S':
                return 1536;
            default:
                return -1;
        }
    }
}

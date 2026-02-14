package net.runelite.client.plugins.custom.zulrah;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.plugins.custom.zulrah.constants.StandLocation;
import net.runelite.client.plugins.custom.zulrah.constants.ZulrahType;
import net.runelite.client.plugins.custom.zulrah.rotationutils.ZulrahPhase;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.util.AbstractScript;

import javax.inject.Inject;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class ZulrahScript extends AbstractScript {

    private static final int VENOM_THRESHOLD = 1000000;
    private static final int VENOM_MAXIUMUM_DAMAGE = 20;

    @Inject
    private ZulrahConfig zulrahConfig;

    private AtomicReference<ZulrahPhase> zulrahPhase;
    private boolean zulrahPhaseChanged;

    private Rs2InventorySetup magicSetup;
    private Rs2InventorySetup rangeSetup;

    private ScheduledFuture<?> meleeFuture;

    @Override
    public void tick() {
        if (Rs2Player.eatAt(50, true)) {
            log.info("Eating.");
            attackZulrah();
        }
        if (Rs2Player.drinkPrayerPotionAt(20)) {
            log.info("Drank prayer potion.");
            attackZulrah();
        }
        final int poison = Microbot.getClient().getVarpValue(VarPlayerID.POISON);
        if (poison >= VENOM_THRESHOLD && nextPoisonDamage(poison) > 10) {
            log.info("Drinking anti poison to reduce venom damage");
            Rs2Player.drinkAntiPoisonPotion();
            attackZulrah();
        }

        if (zulrahPhaseChanged) {
            changeZulrahPhase();
        }
    }

    @Override
    public void initialize() {
        log.info("Initializing");
        zulrahPhase = new AtomicReference<>();
        magicSetup = new Rs2InventorySetup(zulrahConfig.mageInventorySetup(), mainScheduledFuture);
        rangeSetup = new Rs2InventorySetup(zulrahConfig.rangeInventorySetup(), mainScheduledFuture);
    }

    @Override
    public void onShutdown() {
        if (meleeFuture != null && !meleeFuture.isCancelled()) {
            meleeFuture.cancel(true);
            meleeFuture = null;
        }
        super.shutdown();
    }

    public void setZulrahPhase(ZulrahPhase zulrahPhase) {
        this.zulrahPhase.set(zulrahPhase);
        zulrahPhaseChanged = true;
    }

    public void handleZulrahAttack() {
        if (this.zulrahPhase.get().getZulrahNpc().isJad()) {
            log.info("Jad phase. Toggling prayer.");
            toggleProtectionPrayer();
        }
    }

    private void toggleProtectionPrayer() {
        if (Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_RANGE)) {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, true);
        } else {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_RANGE, true);
        }
    }

    private void changeZulrahPhase() {
        if (Objects.nonNull(this.zulrahPhase.get())) {
            log.info("Changing zulrah phase. Next phase is {}", this.zulrahPhase.get().getZulrahNpc().getType().getName());
            handleProtectionPrayers();
            equipCorrectArmour();
            walkToNextSpot();
            handleSpecialPhases();
            attackZulrah();
        }

        zulrahPhaseChanged = false;
    }

    private void attackZulrah() {
        var zulrah = Microbot.getRs2NpcCache().query().withName("zulrah").nearestOnClientThread(20);
        if (Objects.nonNull(zulrah)) {
            log.info("Found it.");
            zulrah.click("attack");
            executeOnSeparateThread(() -> {
                sleep(1200);
                zulrah.click("attack");
            });
        }
    }

    private void walkToNextSpot() {
        log.info("Walking to next location {}. Current location: {}", this.zulrahPhase.get().getAttributes().getStandLocation().toWorldPoint(), Rs2Player.getWorldLocation());
        Rs2Walker.walkFastCanvas(this.zulrahPhase.get().getAttributes().getStandLocation().toWorldPoint(), true);
        sleepUntil(() ->  Rs2Player.getWorldLocation().equals(this.zulrahPhase.get().getAttributes().getStandLocation().toWorldPoint()));
        log.info("At location... {}", Rs2Player.getWorldLocation());
    }

    private void handleProtectionPrayers() {
        if (Objects.nonNull(this.zulrahPhase.get().getAttributes().getPrayer())) {
            log.info("Toggling prayer: {}", this.zulrahPhase.get().getAttributes().getPrayer());
            Rs2Prayer.toggle(this.zulrahPhase.get().getAttributes().getPrayer(), true);
            if (this.zulrahPhase.get().getZulrahNpc().getType() == ZulrahType.MAGIC) {
                Rs2Prayer.toggle(Rs2Prayer.getBestRangePrayer(), true);
            } else {
                Rs2Prayer.toggle(Rs2Prayer.getBestMagePrayer(), true);
            }
        } else {
            Rs2Prayer.disableAllPrayers();
        }
    }

    private void equipCorrectArmour() {
        Rs2InventorySetup inventorySetup;
        if (this.zulrahPhase.get().getZulrahNpc().getType() == ZulrahType.MAGIC) {
            log.info("Changing to range setup");
            inventorySetup = rangeSetup;
        } else {
            log.info("Changing to magic setup");
            inventorySetup = magicSetup;
        }
        executeOnSeparateThread(() -> {
            while (!inventorySetup.doesEquipmentMatch()) {
                inventorySetup.wearEquipment();
                sleep(1200);
            }
        });
    }

    private void handleSpecialPhases() {
        if (this.zulrahPhase.get().getZulrahNpc().getType() == ZulrahType.MELEE && this.zulrahPhase.get().getAttributes().getStandLocation() == StandLocation.NORTHEAST_NORTH) {
            meleeFuture = executeOnSeparateThread(() -> {
                log.info("Walking to another spot to avoid melee hit.");
                WorldPoint nextLocation = Rs2Player.getWorldLocation().equals(this.zulrahPhase.get().getAttributes().getStandLocation().toWorldPoint()) ?
                        StandLocation.NORTHEAST_TOP.toWorldPoint() : StandLocation.NORTHEAST_NORTH.toWorldPoint();
                Rs2Walker.walkFastCanvas(nextLocation, true);
                sleepUntil(() -> Rs2Player.getWorldLocation().equals(nextLocation));
                attackZulrah();
            }, 1800, 4800);
        } else {
            if (Objects.nonNull(meleeFuture) && !meleeFuture.isCancelled()) {
                meleeFuture.cancel(true);
                meleeFuture = null;
            }
        }
    }

    private static int nextPoisonDamage(int poisonValue) {
        int damage;

        if (poisonValue >= VENOM_THRESHOLD) {
            //Venom Damage starts at 6, and increments in twos;
            //The VarPlayer increments in values of 1, however.
            poisonValue -= VENOM_THRESHOLD - 3;
            damage = poisonValue * 2;
            //Venom Damage caps at 20, but the VarPlayer keeps increasing
            if (damage > VENOM_MAXIUMUM_DAMAGE) {
                damage = VENOM_MAXIUMUM_DAMAGE;
            }
        }
        else {
            damage = (int) Math.ceil(poisonValue / 5.0f);
        }

        return damage;
    }
}

package net.runelite.client.plugins.custom.zulrah;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.GameObject;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
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
import java.util.function.Supplier;

@Slf4j
public class ZulrahScript extends AbstractScript {

    private static final int VENOM_THRESHOLD = 1000000;
    private static final int VENOM_MAXIUMUM_DAMAGE = 20;

    // GameObject id of Zulrah's ground venom cloud (confirmed live via GameObjectSpawned logs).
    private static final int TOXIC_CLOUD_GAMEOBJECT_ID = 11700;

    // Zulrah attacks every 3 game ticks (1.8s). The melee-dodge cadence must match this so the
    // player has stepped 2 tiles away before each tail swing lands.
    private static final long ZULRAH_ATTACK_INTERVAL_MS = 1800;

    @Inject
    private ZulrahConfig zulrahConfig;

    private AtomicReference<ZulrahPhase> zulrahPhase;
    private boolean zulrahPhaseChanged;

    private Rs2InventorySetup magicSetup;
    private Rs2InventorySetup rangeSetup;

    private ScheduledFuture<?> meleeFuture;

    // The tile to stand on for the current phase, set on every phase change. The tick loop walks
    // here continuously so the character always returns to the safespot after any interruption.
    private volatile WorldPoint standLocation;

    @Override
    public void tick() {
        // Survival first — runs every tick regardless of positioning/attacking.
        if (Rs2Player.eatAt(50, true)) {
            log.info("Eating.");
        }
        Rs2Player.drinkPrayerPotionAt(20);
        final int poison = Microbot.getClient().getVarpValue(VarPlayerID.POISON);
        if (poison >= VENOM_THRESHOLD && nextPoisonDamage(poison) > 10) {
            log.info("Drinking anti poison to reduce venom damage");
            Rs2Player.drinkAntiPoisonPotion();
        }

        if (zulrahPhaseChanged) {
            changeZulrahPhase();
        }

        avoidToxicClouds();

        // Continuous positioning + attacking: recovers from any interruption (gear swap cancelling
        // the attack, an attack click cancelling the walk, etc.) instead of firing once per phase.
        maintainPositionAndAttack();
    }

    /**
     * Every tick: if not on the current phase's stand tile, keep walking there (positioning takes
     * priority so we don't sit in venom clouds / melee range). Only once on the tile do we attack,
     * so the attack click never cancels the walk. Re-attacks whenever the interaction has dropped.
     */
    private void maintainPositionAndAttack() {
        if (isMeleeDodgeActive()) {
            // The melee-dodge task owns movement; attack in place only while stationary so the
            // attack click never cancels a dodge step.
            if (!Rs2Player.isMoving()) {
                ensureAttackingZulrah();
            }
            return;
        }
        final WorldPoint target = standLocation;
        if (target == null) {
            return;
        }
        if (!Rs2Player.getWorldLocation().equals(target)) {
            if (!Rs2Player.isMoving()) {
                Rs2Walker.walkFastCanvas(target, true);
            }
            return;
        }
        ensureAttackingZulrah();
    }

    private void ensureAttackingZulrah() {
        final Actor interacting = Rs2Player.getInteracting();
        if (interacting != null && "zulrah".equalsIgnoreCase(interacting.getName())) {
            return;
        }
        clickNearestZulrah();
    }

    private boolean isMeleeDodgeActive() {
        return meleeFuture != null && !meleeFuture.isCancelled();
    }

    /**
     * If the player is standing on a venom-cloud tile ({@link #TOXIC_CLOUD_GAMEOBJECT_ID}), step
     * back to the current phase's stand location. Detection is done in scene/local coordinates:
     * the cloud's {@code getWorldLocation()} reports instance-template coords that don't match the
     * player's world location, so we compare the player's scene tile against the objects on it.
     * Non-blocking.
     */
    private void avoidToxicClouds() {
        final ZulrahPhase phase = this.zulrahPhase.get();
        if (phase == null || Rs2Player.isMoving()) {
            return;
        }
        final Supplier<Boolean> cloudCheck = () -> {
            final var client = Microbot.getClient();
            if (client.getLocalPlayer() == null) {
                return false;
            }
            final LocalPoint lp = client.getLocalPlayer().getLocalLocation();
            if (lp == null) {
                return false;
            }
            final Tile tile = client.getScene().getTiles()[client.getPlane()][lp.getSceneX()][lp.getSceneY()];
            if (tile == null) {
                return false;
            }
            for (GameObject go : tile.getGameObjects()) {
                if (go != null && go.getId() == TOXIC_CLOUD_GAMEOBJECT_ID) {
                    return true;
                }
            }
            return false;
        };
        if (Boolean.TRUE.equals(Microbot.getClientThread().invoke(cloudCheck))) {
            log.info("Standing in a venom cloud - repositioning to stand location.");
            Rs2Walker.walkFastCanvas(phase.getAttributes().getStandLocation().toWorldPoint(), true);
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
        // NOTE: do NOT call shutdown()/super.shutdown() here. onShutdown() is invoked
        // *by* AbstractScript.shutdown(); calling back into it causes infinite recursion.
        if (meleeFuture != null && !meleeFuture.isCancelled()) {
            meleeFuture.cancel(true);
            meleeFuture = null;
        }
    }

    public void setZulrahPhase(ZulrahPhase zulrahPhase) {
        if (zulrahPhase == null) {
            // Rotation not yet identified / end of rotation: keep the current phase rather than clobbering it.
            return;
        }
        this.zulrahPhase.set(zulrahPhase);
        zulrahPhaseChanged = true;
    }

    public void handleZulrahAttack() {
        final ZulrahPhase phase = this.zulrahPhase.get();
        if (phase == null) {
            return;
        }
        if (phase.getZulrahNpc().isJad()) {
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
        final ZulrahPhase phase = this.zulrahPhase.get();
        if (phase != null) {
            log.info("Changing zulrah phase. Next phase is {}", phase.getZulrahNpc().getType().getName());
            handleProtectionPrayers();
            equipCorrectArmour();
            // Set the stand tile for this phase; the tick loop walks here continuously.
            standLocation = phase.getAttributes().getStandLocation().toWorldPoint();
            handleSpecialPhases();
        }

        zulrahPhaseChanged = false;
    }

    private void clickNearestZulrah() {
        var zulrah = Microbot.getRs2NpcCache().query().withName("zulrah").nearestOnClientThread(20);
        if (Objects.nonNull(zulrah)) {
            zulrah.click("attack");
        }
    }

    private void handleProtectionPrayers() {
        final ZulrahPhase phase = this.zulrahPhase.get();
        if (phase == null) {
            return;
        }

        // Keep the offensive prayer up on EVERY phase (previously disableAllPrayers() dropped it,
        // costing DPS). The offensive prayer mirrors the gear switch in equipCorrectArmour().
        final Rs2PrayerEnum offensivePrayer = attackWithMagic(phase)
                ? Rs2Prayer.getBestMagePrayer()
                : Rs2Prayer.getBestRangePrayer();
        Rs2Prayer.toggle(offensivePrayer, true);

        // Manage only the two overhead protection prayers; leave the offensive prayer untouched.
        final Rs2PrayerEnum overhead = phase.getAttributes().getPrayer();
        log.info("Overhead protection for phase: {}", overhead);
        Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, overhead == Rs2PrayerEnum.PROTECT_MAGIC);
        Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_RANGE, overhead == Rs2PrayerEnum.PROTECT_RANGE);
    }

    /**
     * We attack the magic (tanzanite) form with ranged and everything else with magic. The jad
     * phase is always Zulrah's green form, so it is attacked with magic regardless of the form its
     * rotation entry is encoded with (ROT_C/D encode it as MAGIC only to set the magic start prayer).
     */
    private static boolean attackWithMagic(ZulrahPhase phase) {
        return phase.getZulrahNpc().isJad() || phase.getZulrahNpc().getType() != ZulrahType.MAGIC;
    }

    private void equipCorrectArmour() {
        final Rs2InventorySetup setup = attackWithMagic(this.zulrahPhase.get()) ? magicSetup : rangeSetup;
        log.info("Changing to {} setup", setup == magicSetup ? "magic" : "range");
        executeOnSeparateThread(() -> {
            // Bounded: a missing item must not spin this worker thread forever.
            long deadline = System.currentTimeMillis() + 6000;
            while (!setup.doesEquipmentMatch() && System.currentTimeMillis() < deadline) {
                setup.wearEquipment();
                sleep(1200);
            }
        });
    }

    private void handleSpecialPhases() {
        final ZulrahPhase phase = this.zulrahPhase.get();
        boolean meleeAtNorthEast = phase.getZulrahNpc().getType() == ZulrahType.MELEE
                && phase.getAttributes().getStandLocation() == StandLocation.NORTHEAST_NORTH;
        if (meleeAtNorthEast) {
            // Step 2 tiles to the alternate NE spot on Zulrah's attack cadence (every 3 ticks).
            // The task only steps; attacking in place is handled by maintainPositionAndAttack() so
            // the attack click never cancels a dodge step. The old version blocked on sleepUntil and
            // used a 4800ms period, giving a ~9-tick cadence that let every 2nd/3rd swing through.
            meleeFuture = executeOnSeparateThread(() -> {
                WorldPoint next = Rs2Player.getWorldLocation().equals(StandLocation.NORTHEAST_NORTH.toWorldPoint())
                        ? StandLocation.NORTHEAST_TOP.toWorldPoint()
                        : StandLocation.NORTHEAST_NORTH.toWorldPoint();
                Rs2Walker.walkFastCanvas(next, true);
            }, ZULRAH_ATTACK_INTERVAL_MS, ZULRAH_ATTACK_INTERVAL_MS);
        } else if (Objects.nonNull(meleeFuture) && !meleeFuture.isCancelled()) {
            meleeFuture.cancel(true);
            meleeFuture = null;
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

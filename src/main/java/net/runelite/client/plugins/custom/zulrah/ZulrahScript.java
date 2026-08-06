package net.runelite.client.plugins.custom.zulrah;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameObject;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.plugins.custom.zulrah.constants.StandLocation;
import net.runelite.client.plugins.custom.zulrah.constants.ZulrahType;
import net.runelite.client.plugins.custom.zulrah.rotationutils.ZulrahPhase;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.util.AbstractScript;

import javax.inject.Inject;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Slf4j
public class ZulrahScript extends AbstractScript {

    private static final int VENOM_THRESHOLD = 1000000;
    private static final int VENOM_MAXIUMUM_DAMAGE = 20;

    // GameObject id of Zulrah's ground venom cloud (confirmed live via GameObjectSpawned logs).
    private static final int TOXIC_CLOUD_GAMEOBJECT_ID = 11700;

    // Attack-projectile ids fired at the player during the jad phase. Capture them live from the
    // projectile logging in ZulrahPlugin, then set them here to enable projectile-driven flicking.
    // While either is -1 the animation-based toggle stays in charge.
    private static final int ZULRAH_RANGED_PROJECTILE_ID = -1;
    private static final int ZULRAH_MAGIC_PROJECTILE_ID = -1;

    // Fallback weapon attack speed (ticks) if the equipped weapon's stats can't be read.
    private static final int DEFAULT_ATTACK_SPEED_TICKS = 4;

    @Inject
    private ZulrahConfig zulrahConfig;
    @Inject
    private ItemManager itemManager;

    private AtomicReference<ZulrahPhase> zulrahPhase;
    private boolean zulrahPhaseChanged;

    // Attack-cadence tracking: attack the instant the weapon is off cooldown, walk during the
    // cooldown ticks (they are free for movement). lastAttackAtMs is the wall-clock of the last
    // attack; the weapon speed is cached and refreshed only when the equipped weapon changes.
    private long lastAttackAtMs;
    private int cachedWeaponId = -1;
    private int cachedAttackSpeedTicks = DEFAULT_ATTACK_SPEED_TICKS;

    // Gear-swap is done inline on the tick thread (not a worker thread) so it never drives the mouse
    // at the same time as attacking/walking. Bounded so genuinely-missing items don't stall combat.
    private static final int MAX_GEAR_SWAP_ATTEMPTS = 2;
    private int gearSwapAttempts;

    private Rs2InventorySetup magicSetup;
    private Rs2InventorySetup rangeSetup;

    // Melee (crimson) dodge state. We step to the alternate NE tile each time Zulrah plays a tail
    // swing (SNAKEBOSS_ATTACK_TAIL_LEFT/RIGHT), and only attack once we have actually reached the tile.
    private volatile boolean meleeDodgePhase;
    private boolean meleeDodgeAtNorth = true;

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

        // Re-validate prayer every tick so a missed/overwritten toggle self-corrects.
        enforcePrayers();

//        avoidToxicClouds();

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
        // Swap gear first (inline). Until it's done we don't attack or walk, so the mouse is never
        // driven by two things at once.
        if (!ensureGearEquipped()) {
            return;
        }
        if (meleeDodgePhase) {
            handleMeleeDodge();
            return;
        }
        final WorldPoint target = standLocation;
        if (target == null) {
            return;
        }

        if (Rs2Player.getWorldLocation().equals(target)) {
            // On the safespot: attack continuously, and keep the cooldown timer in sync with the
            // game's own attack cadence so it's accurate when we next reposition.
            ensureAttackingZulrah();
            if (isInteractingWithZulrah() && offCooldown()) {
                lastAttackAtMs = System.currentTimeMillis();
            }
            return;
        }

        // Repositioning: the ticks between attacks are free for movement. The moment the weapon is
        // off cooldown and Zulrah is in range, fire one attack (rooting us for that tick), otherwise
        // keep walking to the safespot. This attacks while "running" without losing the exact tile.
        final long now = System.currentTimeMillis();
        final long sinceLastMs = now - lastAttackAtMs;
        final long cooldownMs = attackSpeedTicks() * 600L;
        if (sinceLastMs >= cooldownMs && zulrahInRange()) {
            log.info("[dps] attacking Zulrah mid-reposition to {} ({}ms since last attack)", target, sinceLastMs);
            clickNearestZulrah();
            lastAttackAtMs = now;
        } else if (!Rs2Player.isMoving()) {
            log.info("[dps] walking to {} | {}ms since last attack, {}ms until next attack", target, sinceLastMs, cooldownMs - sinceLastMs);
            Rs2Walker.walkFastCanvas(target, true);
        }
    }

    private void ensureAttackingZulrah() {
        if (!isInteractingWithZulrah()) {
            clickNearestZulrah();
        }
    }

    private boolean isInteractingWithZulrah() {
        final Actor interacting = Rs2Player.getInteracting();
        return interacting != null && "zulrah".equalsIgnoreCase(interacting.getName());
    }

    private boolean offCooldown() {
        return System.currentTimeMillis() - lastAttackAtMs >= attackSpeedTicks() * 600L;
    }

    private boolean zulrahInRange() {
        final Rs2NpcModel zulrah = Microbot.getRs2NpcCache().query().withName("zulrah").nearestOnClientThread(20);
        if (zulrah == null) {
            return false;
        }
        // Rs2Combat.getAttackRange() reads the weapon definition, so evaluate on the client thread.
        final Supplier<Boolean> check = () ->
                Rs2Player.getWorldLocation().distanceTo(zulrah.getWorldLocation()) <= Rs2Combat.getAttackRange();
        return Boolean.TRUE.equals(Microbot.getClientThread().invoke(check));
    }

    /** Equipped weapon's attack speed in ticks, cached and refreshed only when the weapon changes. */
    private int attackSpeedTicks() {
        final var weapon = Rs2Equipment.get(EquipmentInventorySlot.WEAPON);
        final int id = weapon != null ? weapon.getId() : -1;
        if (id != cachedWeaponId) {
            cachedWeaponId = id;
            cachedAttackSpeedTicks = DEFAULT_ATTACK_SPEED_TICKS;
            if (id != -1) {
                // getItemStats -> getItemComposition must run on the client thread.
                final Supplier<Integer> lookup = () -> {
                    final ItemStats stats = itemManager.getItemStats(id);
                    return stats != null && stats.getEquipment() != null ? stats.getEquipment().getAspeed() : 0;
                };
                final Integer aspeed = Microbot.getClientThread().invoke(lookup);
                if (aspeed != null && aspeed > 0) {
                    cachedAttackSpeedTicks = aspeed;
                }
            }
        }
        return cachedAttackSpeedTicks;
    }

    /**
     * The dodge step itself is triggered by Zulrah's tail-swing animation (see handleMeleeSwing).
     * Here we just walk to the chosen tile and only attack once we have arrived, so the attack click
     * can't cancel the walk and strand us on the tile being swung at.
     */
    private void handleMeleeDodge() {
        final WorldPoint target = (meleeDodgeAtNorth ? StandLocation.NORTHEAST_NORTH : StandLocation.NORTHEAST_TOP).toWorldPoint();
        if (Rs2Player.getWorldLocation().equals(target)) {
            ensureAttackingZulrah();
        } else if (!Rs2Player.isMoving()) {
            Rs2Walker.walkFastCanvas(target, true);
        }
    }

    /**
     * Called when Zulrah plays a melee tail swing (SNAKEBOSS_ATTACK_TAIL_LEFT/RIGHT). It swings at
     * our current tile and the hit lands a tick later, so we flip to the alternate NE tile now; the
     * next handleMeleeDodge tick walks us clear before the hit.
     */
    public void handleMeleeSwing() {
        if (meleeDodgePhase) {
            meleeDodgeAtNorth = !meleeDodgeAtNorth;
        }
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
        reset();
    }

    /**
     * Clears per-fight state. Called on (re)start and whenever the plugin resets the fight, so a
     * fresh encounter starts idle at the first phase instead of walking to the previous fight's last
     * stand tile (e.g. a pillar where Zulrah died).
     */
    public void reset() {
        standLocation = null;
        zulrahPhaseChanged = false;
        meleeDodgePhase = false;
        lastAttackAtMs = 0;
        gearSwapAttempts = 0;
        if (zulrahPhase != null) {
            zulrahPhase.set(null);
        }
    }

    @Override
    public void onShutdown() {
        // NOTE: do NOT call shutdown()/super.shutdown() here. onShutdown() is invoked
        // *by* AbstractScript.shutdown(); calling back into it causes infinite recursion.
        reset();
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
        // Fallback flick off the attack animation, used only until the jad projectile ids are
        // captured (see handleZulrahProjectile). Blind toggle: fragile if it ever misses a swing.
        if (phase.getZulrahNpc().isJad() && !projectileFlickEnabled()) {
            log.info("Jad phase. Toggling prayer.");
            toggleProtectionPrayer();
        }
    }

    /**
     * Projectile-driven jad flick. A just-fired projectile's hit is already locked by the prayer set
     * on the previous projectile, so we switch the overhead for the NEXT attack: a ranged projectile
     * means the next attack is magic (→ Protect from Magic) and vice versa. Definite, not a toggle,
     * so it re-syncs every attack instead of drifting if one is missed. Jad phase only.
     */
    public void handleZulrahProjectile(int projectileId) {
        if (!projectileFlickEnabled()) {
            return;
        }
        final ZulrahPhase phase = this.zulrahPhase.get();
        if (phase == null || !phase.getZulrahNpc().isJad()) {
            return;
        }
        if (projectileId == ZULRAH_RANGED_PROJECTILE_ID) {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_RANGE, false);
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, true);
        } else if (projectileId == ZULRAH_MAGIC_PROJECTILE_ID) {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, false);
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_RANGE, true);
        }
    }

    private static boolean projectileFlickEnabled() {
        return ZULRAH_RANGED_PROJECTILE_ID > 0 && ZULRAH_MAGIC_PROJECTILE_ID > 0;
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
            enforcePrayers();
            // Mark that this phase's gear may need swapping; the swap runs inline on the tick thread
            // (see ensureGearEquipped) so it never fights the combat mouse actions.
            gearSwapAttempts = 0;
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

    /**
     * Validates the prayer state against the current phase and corrects any mismatch. Runs every
     * tick (not just on phase change), so a toggle that failed or was clicked off — e.g. the blue
     * phase that "kept praying range" — is fixed on the next tick instead of the whole phase.
     */
    private void enforcePrayers() {
        final ZulrahPhase phase = this.zulrahPhase.get();
        if (phase == null) {
            return;
        }

        // Offensive prayer: keep the correct one up on every phase (magic vs the range form).
        final Rs2PrayerEnum offensive = attackWithMagic(phase)
                ? Rs2Prayer.getBestMagePrayer()
                : Rs2Prayer.getBestRangePrayer();
        if (offensive != null && !Rs2Prayer.isPrayerActive(offensive)) {
            Rs2Prayer.toggle(offensive, true);
        }

        // Overhead protection. The jad phase flicks its own overhead per attack — don't fight it.
        if (phase.getZulrahNpc().isJad()) {
            return;
        }
        enforceOverhead(phase.getAttributes().getPrayer());
    }

    /**
     * Sets the overhead protection prayer. Overhead prayers are mutually exclusive in-game, so we
     * only ENABLE the wanted one and let the game auto-disable the other. The previous version
     * toggled both explicitly; because the prayer varbit only updates on the next game tick, the
     * "disable the other" click landed after the wanted one had already turned it off, re-enabling
     * it — which flipped the wanted one back off, flip-flopping every tick.
     */
    private void enforceOverhead(Rs2PrayerEnum wanted) {
        if (wanted != null) {
            if (!Rs2Prayer.isPrayerActive(wanted)) {
                log.info("[prayer] enabling {}", wanted);
                Rs2Prayer.toggle(wanted, true);
            }
            return;
        }
        // No overhead this phase (e.g. melee): turn off whichever protection prayer is active.
        if (Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_MAGIC)) {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, false);
        }
        if (Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_RANGE)) {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_RANGE, false);
        }
    }

    /**
     * We attack the magic (tanzanite) form with ranged and everything else with magic. The jad
     * phase is always Zulrah's green form, so it is attacked with magic regardless of the form its
     * rotation entry is encoded with (ROT_C/D encode it as MAGIC only to set the magic start prayer).
     */
    private static boolean attackWithMagic(ZulrahPhase phase) {
        return phase.getZulrahNpc().isJad() || phase.getZulrahNpc().getType() != ZulrahType.MAGIC;
    }

    /**
     * Ensures the correct setup for this phase is equipped, running the swap INLINE on the tick
     * thread. Returns true when combat may proceed. Doing this on the tick thread (rather than a
     * worker thread) guarantees the gear swap and the attack/walk clicks never drive the mouse at
     * the same time — the erratic "mouse everywhere" race. Bounded so genuinely-missing gear doesn't
     * block combat forever.
     */
    private boolean ensureGearEquipped() {
        final ZulrahPhase phase = this.zulrahPhase.get();
        if (phase == null) {
            return true;
        }
        final Rs2InventorySetup setup = attackWithMagic(phase) ? magicSetup : rangeSetup;
        if (setup == null || setup.doesEquipmentMatch()) {
            return true;
        }
        if (gearSwapAttempts >= MAX_GEAR_SWAP_ATTEMPTS) {
            return true; // items likely missing; stop blocking combat
        }
        if (gearSwapAttempts == 0) {
            log.info("Changing to {} setup", setup == magicSetup ? "magic" : "range");
        }
        gearSwapAttempts++;
        setup.wearEquipment();
        if (gearSwapAttempts >= MAX_GEAR_SWAP_ATTEMPTS && !setup.doesEquipmentMatch()) {
            log.warn("Equipment swap incomplete (missing items?) — continuing without it.");
        }
        return setup.doesEquipmentMatch();
    }

    private void handleSpecialPhases() {
        final ZulrahPhase phase = this.zulrahPhase.get();
        boolean meleeAtNorthEast = phase.getZulrahNpc().getType() == ZulrahType.MELEE
                && phase.getAttributes().getStandLocation() == StandLocation.NORTHEAST_NORTH;
        meleeDodgePhase = meleeAtNorthEast;
        if (meleeAtNorthEast) {
            meleeDodgeAtNorth = true;
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

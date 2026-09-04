package net.runelite.client.plugins.custom.customdemonicgorilla;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Hitsplat;
import net.runelite.api.NPC;
import net.runelite.api.Projectile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaContext.AttackStyle;
import net.runelite.client.plugins.custom.customdemonicgorilla.actions.GorillaHelpers;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.plugins.microbot.util.misc.TimeUtils;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.time.Instant;

@PluginDescriptor(
        name = PluginDescriptor.TaFCat + "Demonic Gorillas (Custom)",
        description = "Custom build: automates restocking, prayer flicking, and gear switching during Demonic Gorillas",
        tags = {"demonic", "Gorilla", "flicker", "weapon", "switch", "microbot", "custom"},
        version = CustomDemonicGorillaPlugin.version,
        minClientVersion = "2.0.13",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class CustomDemonicGorillaPlugin extends Plugin {

    public final static String version = "1.4.6";

    private static final int DEMONIC_GORILLA_ROCK = 856;

    @Inject
    private CustomDemonicGorillaConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private CustomDemonicGorillaOverlay demonicGorillaOverlay;
    @Inject
    private CustomDemonicGorillaScript demonicGorillaScript;
    private Instant scriptStartTime;

    private final CustomDemonicGorillaLooterScript lootScript = new CustomDemonicGorillaLooterScript();

    @Provides
    CustomDemonicGorillaConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(CustomDemonicGorillaConfig.class);
    }

    /** Exposes the durable run state for the overlay and this plugin's event handlers. */
    public GorillaContext getContext() {
        return demonicGorillaScript.getContext();
    }

    @Override
    protected void startUp() {
        if (!validateConfig()) {
            Microbot.stopPlugin(this);
            return;
        }
        scriptStartTime = Instant.now();
        if (overlayManager != null) {
            overlayManager.add(demonicGorillaOverlay);
        }
        demonicGorillaScript.run();
        lootScript.run(config, getContext());
    }

    /** Fail fast if a combat style is enabled without its gear setup, or the banking setup is missing. */
    private boolean validateConfig() {
        if (config.useMagicStyle() && config.magicGear() == null) {
            Microbot.showMessage("You've selected magic combatstyle, but your magic inventory setup is null. Please set it in the config. If you already have one selected, select another inventory setup and then select your magic setup again.");
            return false;
        }
        if (config.useRangeStyle() && config.rangeGear() == null) {
            Microbot.showMessage("You've selected ranged combatstyle, but your range inventory setup is null. Please set it in the config. If you already have one selected, select another inventory setup and then select your range setup again.");
            return false;
        }
        if (config.useMeleeStyle() && config.meleeGear() == null) {
            Microbot.showMessage("You've selected melee combatstyle, but your melee inventory setup is null. Please set it in the config. If you already have one selected, select another inventory setup and then select your melee setup again.");
            return false;
        }
        if (config.gearSetup() == null) {
            Microbot.showMessage("Your banking gear setup is null. Please set it in the config. If you already have one selected, select another inventory setup and then select your gear setup again.");
            return false;
        }
        return true;
    }

    @Override
    protected void shutDown() {
        demonicGorillaScript.shutdown();
        lootScript.shutdown();
        overlayManager.remove(demonicGorillaOverlay);
    }

    protected String getTimeRunning() {
        return scriptStartTime != null ? TimeUtils.getFormattedDurationBetween(scriptStartTime, Instant.now()) : "";
    }

    @Subscribe
    public void onProjectileMoved(ProjectileMoved event) {
        final Projectile projectile = event.getProjectile();
        if (projectile.getId() != DEMONIC_GORILLA_ROCK) {
            return;
        }
        // The boulder lands on its TARGET tile, which is known the moment the projectile spawns — so we
        // arm the dodge early. (The old code waited for the projectile's current position to reach the
        // player, i.e. until it had basically landed, which is why boulders were connecting.)
        WorldPoint target = projectile.getTargetPoint();
        if (target == null && projectile.getTarget() != null) {
            // Fallback if the world target isn't populated: derive it from the target LocalPoint.
            target = WorldPoint.fromLocal(Microbot.getClient(), projectile.getTarget());
        }
        WorldPoint me = Rs2Player.getWorldLocation();
        GorillaContext ctx = getContext();
        // Arm if the boulder is aimed at our tile OR an adjacent one: it targets where we stood at cast,
        // but while meleeing we've usually already drifted a tile off by the time we see the projectile.
        boolean nearUs = target != null && me != null
                && target.getPlane() == me.getPlane() && target.distanceTo(me) <= 1;
        log.info("[gorilla-boulder] projectile target={} me={} nearUs={} remainingCycles={}",
                target, me, nearUs, projectile.getRemainingCycles());
        if (nearUs && !target.equals(ctx.getBoulderTargetTile())) {
            ctx.setBoulderTargetTile(target);
            ctx.setBoulderLandsAtMs(System.currentTimeMillis() + projectile.getRemainingCycles() * 20L);
            ctx.setBoulderDodgePending(true);
        }
    }

    /**
     * The demonic gorilla shouts "Rhaaaa" (overhead text) the moment it switches attack style — the
     * reliable cry, distinct from the ambiguous 7224 defensive-emote animation. When it comes from the
     * gorilla we're fighting, arm the style-switch prediction (consumed by GorillaAttacksAction).
     */
    @Subscribe
    public void onOverheadTextChanged(OverheadTextChanged event) {
        Actor actor = event.getActor();
        String text = event.getOverheadText();
        if (text == null || !text.toLowerCase().startsWith("rhaa")) {
            return;
        }
        if (isOurTargetGorilla(actor)) {
            getContext().setStyleSwitchCryPending(true);
        }
    }

    /**
     * Authoritative fail-check: the gorilla's actual attack animation always wins over the prediction.
     * The instant our target gorilla plays a magic/ranged/melee attack, flick the matching overhead —
     * event-driven (client thread) so it beats the 50 ms pipeline poll and never lags a wrong guess.
     */
    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        Actor actor = event.getActor();
        if (!isOurTargetGorilla(actor)) {
            return;
        }
        Rs2PrayerEnum protect;
        AttackStyle style;
        switch (actor.getAnimation()) {
            case GorillaHelpers.DEMONIC_GORILLA_MAGIC_ATTACK:
                protect = Rs2PrayerEnum.PROTECT_MAGIC;
                style = AttackStyle.MAGIC;
                break;
            case GorillaHelpers.DEMONIC_GORILLA_RANGED_ATTACK:
                protect = Rs2PrayerEnum.PROTECT_RANGE;
                style = AttackStyle.RANGED;
                break;
            case GorillaHelpers.DEMONIC_GORILLA_MELEE_ATTACK:
                protect = Rs2PrayerEnum.PROTECT_MELEE;
                style = AttackStyle.MELEE;
                break;
            default:
                return; // not an attack animation (cry/emote/AOE handled elsewhere)
        }
        GorillaContext ctx = getContext();
        // TEMP measurement: was the prediction already on the correct overhead when the real attack fired?
        boolean predicted = Rs2Prayer.isPrayerActive(protect);
        log.info("[gorilla-pred] fail-check: {} attack, prediction was {}", style, predicted ? "CORRECT" : "wrong");
        // The animation is ground truth: end any pending prediction and pray the matching overhead.
        ctx.setCurrentAttackStyle(style);
        ctx.setAwaitingStyleSwitch(false);
        ctx.setStyleSwitchCryPending(false);
        // Re-pray whenever the overhead isn't ACTUALLY active (not just when it differs from the tracked
        // value) so prayer that got disabled — points ran out then a restore was drunk — comes back on.
        if (!Rs2Prayer.isPrayerActive(protect)) {
            GorillaHelpers.switchDefensivePrayer(ctx, protect);
        }
    }

    /** True if {@code actor} is the Demonic gorilla we're fighting (our tracked target, or the one we
     *  are mutually interacting with) — so another player's gorilla crying never triggers us. */
    private boolean isOurTargetGorilla(Actor actor) {
        if (!(actor instanceof NPC) || actor.getName() == null || !actor.getName().equalsIgnoreCase("Demonic gorilla")) {
            return false;
        }
        var target = getContext().getCurrentTarget();
        if (target != null && target.getNpc() == actor) {
            return true;
        }
        var localPlayer = Microbot.getClient().getLocalPlayer();
        return localPlayer != null && (actor.getInteracting() == localPlayer || localPlayer.getInteracting() == actor);
    }

    /**
     * TEMP measurement: log every point of damage WE take, tagged with which protection prayer was
     * actually active, the gorilla's current style, and whether a boulder just landed — so we can
     * classify real hits as prayer-failure (wrong overhead), boulder (AOE, unprotectable) or
     * protected-but-hit, instead of trusting the overcounting "prediction was wrong" metric.
     */
    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        if (event.getActor() != Microbot.getClient().getLocalPlayer()) {
            return;
        }
        Hitsplat hitsplat = event.getHitsplat();
        if (hitsplat.getAmount() <= 0) {
            return; // 0 = blocked/no damage
        }
        String praying = Microbot.getVarbitValue(4118) == 1 ? "MELEE"
                : Microbot.getVarbitValue(4117) == 1 ? "RANGE"
                : Microbot.getVarbitValue(4116) == 1 ? "MAGIC" : "NONE";
        GorillaContext ctx = getContext();
        long sinceBoulder = System.currentTimeMillis() - ctx.getBoulderLandsAtMs();
        boolean likelyBoulder = ctx.getBoulderLandsAtMs() > 0 && sinceBoulder >= -200 && sinceBoulder <= 900;
        log.info("[gorilla-hit] dmg={} praying={} gorillaStyle={} likelyBoulder={}",
                hitsplat.getAmount(), praying, ctx.getCurrentAttackStyle(), likelyBoulder);
    }

    @Subscribe
    public void onGameTick(GameTick gameTick) {
        GorillaContext ctx = getContext();
        WorldPoint currentLocation = Rs2Player.getWorldLocation();
        ctx.setPlayerMoved(!ctx.getLastLocation().contains(currentLocation));
        ctx.getLastLocation().add(currentLocation);
        ctx.setGameTickCount(ctx.getGameTickCount() + 1);
    }
}

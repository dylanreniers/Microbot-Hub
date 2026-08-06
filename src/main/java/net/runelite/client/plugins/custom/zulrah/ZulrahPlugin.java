package net.runelite.client.plugins.custom.zulrah;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Projectile;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.custom.zulrah.rotationutils.RotationType;
import net.runelite.client.plugins.custom.zulrah.rotationutils.ZulrahPhase;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.PluginConstants;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

@PluginDescriptor(
        name = "Zulrah Slayer",
        description = "Helps with various aspects during a fight with Zulrah",
        tags = {"Zulrah", "Helper", "boss", "bossing", "snek", "snake", "tool"},
        authors = {"Donder"},
        version = ZulrahPlugin.version,
        minClientVersion = "2.0.1",
        iconUrl = "https://i.imgur.com/syri2MC.png",
        cardUrl = "https://i.imgur.com/syri2MC.png",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class ZulrahPlugin extends Plugin {
    public static final String version = "1.0.2";
    public static final int GOING_UNDER_WATER = 5072;   // SNAKEBOSS_SINKFAST
    public static final int ATTACK_ANIMATION = 5069;    // SNAKEBOSS_ATTACK_ACIDX1 (ranged/magic)
    public static final int START_ANIMATION = 5071;     // SNAKEBOSS_SPAWN
    public static final int RESURFACE_ANIMATION = 5073; // SNAKEBOSS_EMERGEFAST
    public static final int RESET_ANIMATION = 5804;     // SNAKEBOSS_DEATH
    public static final int MELEE_TAIL_LEFT = 5806;     // SNAKEBOSS_ATTACK_TAIL_LEFT
    public static final int MELEE_TAIL_RIGHT = 5807;    // SNAKEBOSS_ATTACK_TAIL_RIGHT

    @Inject
    private ZulrahScript zulrahScript;
    @Inject
    private Client client;
    @Inject
    private ZulrahConfig config;

    // Instance state: a static field would leak across plugin restarts.
    @Getter
    private boolean zulrahReset;
    @Getter
    private int stage = 0;
    @Getter
    private RotationType currentRotation;

    private List<RotationType> potentialRotations = new ArrayList<>();

    // Each projectile is handled once, on its first (spawn) frame, so the jad flick fires per attack
    // instead of on every frame it moves. loggedProjectileIds prints each distinct id once, to
    // capture the ranged / magic attack projectile ids live.
    private final Set<Projectile> seenProjectiles = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Integer> loggedProjectileIds = new HashSet<>();

    @Provides
    ZulrahConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ZulrahConfig.class);
    }

    @Override
    protected void startUp() {
        if (config.mageInventorySetup() == null || config.rangeInventorySetup() == null) {
            Microbot.showMessage("Zulrah: configure both the Mage and Range inventory setups before starting.");
            Microbot.stopPlugin(this);
            return;
        }
        zulrahScript.run();
    }

    @Override
    protected void shutDown() {
        reset();
        zulrahScript.shutdown();
    }

    private void reset() {
        stage = -1;
        currentRotation = null;
        potentialRotations.clear();
        zulrahReset = false;
        seenProjectiles.clear();
        zulrahScript.reset();
        log.info("Zulrah Reset!");
    }

    @Nullable
    private RotationType getRotation(NPC npc) {
        if (currentRotation == null) {
            potentialRotations = RotationType.findPotentialRotations(npc, stage);
            if (potentialRotations.isEmpty()) {
                log.warn("No potential rotations for stage {} / npc {}", stage, npc.getId());
                return null;
            }
            var firstRotation = potentialRotations.get(0);
            currentRotation = potentialRotations.size() == 1 ? firstRotation : null;
            log.info("Trying rotation {}", firstRotation.getRotationName());
            return firstRotation;
        } else {
            log.info("Rotation already defined: {}", currentRotation.getRotationName());
        }

        return currentRotation;
    }

    @Subscribe
    private void onAnimationChanged(AnimationChanged event) {
        if (!(event.getActor() instanceof NPC)) {
            return;
        }

        NPC npc = (NPC) event.getActor();
        if (npc.getName() != null && !npc.getName().equalsIgnoreCase("zulrah")) {
            return;
        }

        switch (npc.getAnimation()) {
            case ATTACK_ANIMATION: {
                zulrahScript.handleZulrahAttack();
                break;
            }
            case MELEE_TAIL_LEFT:
            case MELEE_TAIL_RIGHT: {
                zulrahScript.handleMeleeSwing();
                break;
            }
            case START_ANIMATION: {
                stage = 0;
                zulrahScript.setZulrahPhase(getCurrentPhase(getRotation(npc)));
                logZulrahState("START");
                break;
            }
            case RESURFACE_ANIMATION: {
                if (currentRotation == null) {
                    ++stage;
                    zulrahScript.setZulrahPhase(getCurrentPhase(getRotation(npc)));
                    logZulrahState("RESURFACE");
                }

                break;
            }
            case GOING_UNDER_WATER: {
                if (zulrahReset) {
                    zulrahReset = false;
                }
                if (currentRotation == null) {
                    break;
                } else if (!isLastPhase(currentRotation)) {
                    ++stage;
                    zulrahScript.setZulrahPhase(getCurrentPhase(getRotation(npc)));
                    logZulrahState("UNDERWATER");
                    break;
                }

                stage = -1;
                currentRotation = null;
                potentialRotations.clear();
                zulrahReset = true;
                logZulrahState("ROTATION END");
                break;
            }
            case RESET_ANIMATION: {
                reset();
            }
        }
    }

    @Subscribe
    private void onProjectileMoved(ProjectileMoved event) {
        Projectile projectile = event.getProjectile();
        if (projectile == null || !seenProjectiles.add(projectile)) {
            return; // handle each projectile once, on its first (spawn) frame
        }
        int id = projectile.getId();
        if (loggedProjectileIds.add(id)) {
            boolean atPlayer = projectile.getInteracting() == client.getLocalPlayer();
            log.info("[projectile] first seen id={} atPlayer={}", id, atPlayer);
        }
        zulrahScript.handleZulrahProjectile(id);
    }

    @Subscribe
    private void onGameStateChanged(GameStateChanged event) {
        switch (event.getGameState()) {
            case LOADING:
            case CONNECTION_LOST:
            case HOPPING: {
                reset();
            }
        }
    }

    @Nullable
    private ZulrahPhase getCurrentPhase(@Nullable RotationType type) {
        if (type == null || stage < 0 || stage >= type.getZulrahPhases().size()) {
            return null;
        }
        return type.getZulrahPhases().get(stage);
    }

    private boolean isLastPhase(RotationType type) {
        return stage == type.getZulrahPhases().size() - 1;
    }

    /** Prints the detected rotation and the current phase so the fight can be followed live. */
    private void logZulrahState(String event) {
        String rotation = currentRotation != null
                ? currentRotation.getRotationName()
                : "undetermined (" + potentialRotations.size() + " candidates)";
        ZulrahPhase phase = getCurrentPhase(currentRotation);
        String phaseDesc = phase != null
                ? phase.getZulrahNpc().getType().getName()
                  + " @ " + phase.getAttributes().getStandLocation()
                  + (phase.getZulrahNpc().isJad() ? " [JAD]" : "")
                  + " pray=" + phase.getAttributes().getPrayer()
                : "n/a";
        log.info("[zulrah] {} | rotation={} stage={} phase={}", event, rotation, stage, phaseDesc);
    }
}

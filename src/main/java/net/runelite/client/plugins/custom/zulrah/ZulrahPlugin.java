package net.runelite.client.plugins.custom.zulrah;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
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
import java.util.List;

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
    public static final int GOING_UNDER_WATER = 5072;
    public static final int ATTACK_ANIMATION = 5069;
    public static final int START_ANIMATION = 5071;
    public static final int RESURFACE_ANIMATION = 5073;
    public static final int RESET_ANIMATION = 5804;

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
    private void onGameObjectSpawned(GameObjectSpawned event) {
        GameObject obj = event.getGameObject();
        if (obj.getId() == 11700) {
            log.info("Converted: {}", WorldPoint.fromLocalInstance(client, event.getGameObject().getLocalLocation()));
            log.info("Event world location: {}", event.getTile().getWorldLocation());
            log.info("Found toxic cloud at {}", obj.getWorldLocation());
            log.info("Found toxic cloud at local location {}", obj.getLocalLocation());
        }
    }

    @Subscribe
    private void onDecorativeObjectSpawned(DecorativeObjectSpawned event) {
        var obj = event.getDecorativeObject();
        if (obj == null || obj.getLocalLocation() == null) {
            return;
        }
        log.info("DecorativeObject created: id={} at {}", obj.getId(),
                WorldPoint.fromLocalInstance(client, obj.getLocalLocation()));
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
            case START_ANIMATION: {
                log.info("New Zulrah Encounter Started");
                log.info("What is this animation?");
                stage = 0;
                zulrahScript.setZulrahPhase(getCurrentPhase(getRotation(npc)));
                break;
            }
            case RESURFACE_ANIMATION: {
                log.info("Zulrah resurfaced?");
                if (currentRotation == null) {
                    log.info("Current rotation not yet defined. Waiting until more clarity to already move to next location.");
                    ++stage;
                    zulrahScript.setZulrahPhase(getCurrentPhase(getRotation(npc)));
                }

                break;
            }
            case GOING_UNDER_WATER: {
                log.info("Zulrah is going under water.");
                if (zulrahReset) {
                    zulrahReset = false;
                }
                if (currentRotation == null) {
                    break;
                } else if (!isLastPhase(currentRotation)) {
                    log.info("Currently rotation is already known. Moving faster to next location.");
                    ++stage;
                    zulrahScript.setZulrahPhase(getCurrentPhase(getRotation(npc)));
                    break;
                }

                stage = -1;
                currentRotation = null;
                potentialRotations.clear();
                zulrahReset = true;
                log.info("Resetting Zulrah");
                break;
            }
            case RESET_ANIMATION: {
                reset();
            }
        }
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
}

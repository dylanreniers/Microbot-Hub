package net.runelite.client.plugins.custom.abyssalsire;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.GraphicsObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.custom.abyssalsire.constants.SireConstants;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.PluginConstants;

import javax.inject.Inject;

@PluginDescriptor(
        name = "Abyssal Sire",
        description = "Full Abyssal Sire helper: barrage + range respiratory systems, then melee the "
                + "Sire through the explosion phase, dodging miasma pools.",
        tags = {"abyssal", "sire", "boss", "bossing", "slayer", "abyssal sire"},
        authors = {"Donder"},
        version = AbyssalSirePlugin.version,
        minClientVersion = "2.0.1",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class AbyssalSirePlugin extends Plugin {

    public static final String version = "1.0.0";

    @Inject
    private AbyssalSireScript script;
    @Inject
    private AbyssalSireConfig config;
    @Inject
    private Client client;

    @Provides
    AbyssalSireConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(AbyssalSireConfig.class);
    }

    @Override
    protected void startUp() {
        if (config.rangeInventorySetup() == null || config.meleeInventorySetup() == null) {
            Microbot.showMessage("Abyssal Sire: configure both the Range and Melee inventory setups before starting.");
            Microbot.stopPlugin(this);
            return;
        }
        script.initialize();
    }

    @Override
    protected void shutDown() {
        // Reset the fight context explicitly (shutdown() also resets via onShutdown, but make the
        // guarantee obvious) so the next run/kill starts from a clean slate.
        script.reset();
        script.shutdown();
    }

    @Subscribe
    private void onGameTick(GameTick event) {
        script.gameTick();
    }

    /**
     * The Sire is a single NPC that changes id through the fight; the id drives the phase machine
     * (see {@link AbyssalSireScript#onSireId}). A spawn is the initial appearance; a change is a
     * transform (e.g. to 5886 once fighting = phase 2, to 5889 = phase 3).
     */
    @Subscribe
    private void onNpcSpawned(NpcSpawned event) {
        if (isSire(event.getNpc())) {
            script.onSireId(event.getNpc().getId(), true);
        }
    }

    @Subscribe
    private void onNpcChanged(NpcChanged event) {
        if (isSire(event.getNpc())) {
            script.onSireId(event.getNpc().getId(), false);
        }
    }

    /**
     * A respiratory system despawning during phase 1 means we killed it. The Sire NPC despawning
     * during a combat phase means it died — a reliable backup to the death animation (transforms are
     * NpcChanged, not despawn, so a Sire despawn only happens on death / region unload).
     */
    @Subscribe
    private void onNpcDespawned(NpcDespawned event) {
        NPC npc = event.getNpc();
        if (npc == null) {
            return;
        }
        if (npc.getId() == SireConstants.RESPIRATORY_SYSTEM_ID) {
            script.onRespiratoryKilled(npc.getWorldLocation());
        } else if (isSire(npc)) {
            script.onSireDespawned();
        }
    }

    private boolean isSire(NPC npc) {
        return npc != null
                && (SireConstants.isSireId(npc.getId())
                    || SireConstants.SIRE_NAME.equalsIgnoreCase(npc.getName()));
    }

    /** Sire death animation, and the local player's explosion-knockback animation. */
    @Subscribe
    private void onAnimationChanged(AnimationChanged event) {
        if (event.getActor() instanceof NPC) {
            NPC npc = (NPC) event.getActor();
            if (SireConstants.SIRE_NAME.equalsIgnoreCase(npc.getName())
                    && npc.getAnimation() == SireConstants.SIRE_DEATH_ANIMATION) {
                script.onSireDeath();
            }
            return;
        }
        if (event.getActor() instanceof Player && event.getActor() == client.getLocalPlayer()
                && event.getActor().getAnimation() == SireConstants.PLAYER_EXPLOSION_ANIMATION) {
            script.onExplosionKnockback();
        }
    }

    /**
     * A miasma pool spot-anim spawned — track it (with its tile captured now) until it despawns, so the
     * dodge logic sees a stable "pool is here" from spawn to despawn instead of a flickery per-tick read.
     */
    @Subscribe
    private void onGraphicsObjectCreated(GraphicsObjectCreated event) {
        GraphicsObject go = event.getGraphicsObject();
        if (go == null || go.getId() != SireConstants.MIASMA_POOL_GRAPHICS_ID) {
            return;
        }
        LocalPoint lp = go.getLocation();
        if (lp != null) {
            script.context().getMiasmaPools().put(go, WorldPoint.fromLocal(client, lp));
        }
    }

    /** The barrage landed and disoriented the Sire — start the 46-tick stun clock. */
    @Subscribe
    private void onChatMessage(ChatMessage event) {
        if (event.getMessage() != null && event.getMessage().contains(SireConstants.STUN_MESSAGE_FRAGMENT)) {
            script.onSireStunned();
        }
    }

    @Subscribe
    private void onGameStateChanged(GameStateChanged event) {
        // Only a world HOP resets here. LOADING and CONNECTION_LOST fire spuriously mid-fight (a scene
        // refresh when the Sire walks to the centre, a brief network blip) and must NOT wipe the fight
        // — the phase may only leave an active phase on death or when the player leaves the arena
        // (handled by AbyssalSireScript.resetIfLeftArena()).
        if (event.getGameState() == net.runelite.api.GameState.HOPPING) {
            script.reset();
        }
    }
}

package net.runelite.client.plugins.custom.tormenteddemons;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

@PluginDescriptor(
        name = PluginDescriptor.zerozero + "Tormented Demons (Custom)",
        description = "Automates restocking, prayer flicking, and inventory-setup gear switching during Tormented Demon",
        tags = {"tormented", "flicker", "weapon", "switch", "microbot"},
        version = CustomTormentedDemonPlugin.version,
        minClientVersion = "2.0.13",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class CustomTormentedDemonPlugin extends Plugin {

    public static final String version = "1.0.0";

    // Tormented Demon (LUC2_UNDEAD_DEMON) per-attack animations — each is set the tick the demon launches
    // that attack, so they tell us the style of the incoming hit.
    private static final int MAGIC_ATTACK_ANIMATION = 11388; // FIREY_BALLS
    private static final int RANGE_ATTACK_ANIMATION = 11389;  // SPARE_RIBS
    private static final int MELEE_ATTACK_ANIMATION = 11392;  // MELEE
    // 11387 (EXPLOSION_FIRE) is the AoE special — NOT a style change. It is handled by the tile dodge
    // (graphics object 2856) and must not drive the protection prayer.

    private static final int TORMENTED_VENGEANCE_SPECIAL = 2856;
    /** How far out (in tiles) to look for a safe tile when dodging the special. Must exceed the AoE radius. */
    private static final int DODGE_SEARCH_RADIUS = 5;
    /** How long to wait for the player to actually arrive on the chosen safe tile. */
    private static final int DODGE_LAND_TIMEOUT_MS = 3000;

    /** Tiles flagged dangerous by the vengeance-special graphics objects for the current batch (instanced world coords). */
    private final Set<WorldPoint> dangerousTiles = ConcurrentHashMap.newKeySet();
    /** Ensures a single dodge is scheduled per special-attack batch, no matter how many graphics objects spawn. */
    private final AtomicBoolean dodgeScheduled = new AtomicBoolean(false);

    private ScheduledExecutorService scheduledExecutorService;

    @Inject
    private ConfigManager configManager;

    @Inject
    private CustomTormentedDemonConfig config;

    @Provides
    CustomTormentedDemonConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(CustomTormentedDemonConfig.class);
    }

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private CustomTormentedDemonOverlay tormentedDemonOverlay;

    @Inject
    private CustomTormentedDemonScript tormentedDemonScript;

    @Override
    protected void startUp() throws AWTException {
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        if (overlayManager != null) {
            overlayManager.add(tormentedDemonOverlay);
        }
        tormentedDemonScript.run(config);
    }

    @Override
    protected void shutDown() {
        tormentedDemonScript.shutdown();
        overlayManager.remove(tormentedDemonOverlay);
        dangerousTiles.clear();
        dodgeScheduled.set(false);
        Microbot.pauseAllScripts.compareAndSet(true, false);
        if (scheduledExecutorService != null && !scheduledExecutorService.isShutdown()) {
            scheduledExecutorService.shutdown();
        }
    }


    @Subscribe
    public void onGraphicsObjectCreated(GraphicsObjectCreated event) {
        final GraphicsObject graphicsObject = event.getGraphicsObject();
        if (graphicsObject.getId() != TORMENTED_VENGEANCE_SPECIAL) {
            return;
        }

        // Event handlers run on the client thread, so it's safe to read the world view / convert here.
        // Collect every graphics-object tile of this special into the batch. Use fromLocal (instanced
        // world coords) so the tiles line up with Rs2Player.getWorldLocation() and the walkable-tile scan.
        WorldPoint dangerTile = toWorldPoint(graphicsObject);
        if (dangerTile != null) {
            dangerousTiles.add(dangerTile);
        }

        // Schedule the dodge exactly once per batch. More graphics objects may still spawn this tick;
        // they just add tiles to the set. On the next tick (dodgeDelay) we pick the nearest safe tile.
        if (dodgeScheduled.compareAndSet(false, true)) {
            Microbot.pauseAllScripts.compareAndSet(false, true);
            // The demon always changes attack style after this special, so pre-switch our overhead to a
            // guess now. If the guess is wrong, onAnimationChanged corrects it on the demon's next attack.
            preGuessProtectionPrayerAfterSpecial();
            try {
                scheduledExecutorService.schedule(this::dodgeToSafeTile, config.dodgeDelay(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                dodgeScheduled.set(false);
                dangerousTiles.clear();
                Microbot.pauseAllScripts.compareAndSet(true, false);
                tormentedDemonScript.logOnceToChat("Error scheduling dodge: " + e.getMessage());
            }
        }
    }

    /**
     * After the special (which always precedes an attack-style change), pre-switch our overhead protection
     * to a different style than the one currently up — a best-effort guess at the demon's next attack.
     * {@link #onAnimationChanged} still corrects it the moment the demon actually attacks, so a wrong guess
     * only costs a possible first hit, while a right guess protects it. Cycles melee -> range -> magic -> melee.
     */
    private void preGuessProtectionPrayerAfterSpecial() {
        if (!config.enableDefensivePrayer()) {
            return;
        }
        Rs2PrayerEnum current = Rs2Prayer.getActiveProtectionPrayer();
        Rs2PrayerEnum guess;
        if (current == Rs2PrayerEnum.PROTECT_MELEE) {
            guess = Rs2PrayerEnum.PROTECT_RANGE;
        } else if (current == Rs2PrayerEnum.PROTECT_RANGE) {
            guess = Rs2PrayerEnum.PROTECT_MAGIC;
        } else if (current == Rs2PrayerEnum.PROTECT_MAGIC) {
            guess = Rs2PrayerEnum.PROTECT_MELEE;
        } else {
            guess = Rs2PrayerEnum.PROTECT_MELEE; // nothing active -> default guess
        }
        if (!Rs2Prayer.isPrayerActive(guess)) {
            Rs2Prayer.toggle(guess, true);
        }
    }

    /**
     * Runs one tick after the special's graphics objects spawned. Finds the nearest walkable tile that is
     * not covered by a special-attack graphics object and walks there, waiting until we actually land on it.
     */
    private void dodgeToSafeTile() {
        try {
            WorldPoint safeTile = findNearestSafeTile();
            if (safeTile == null) {
                tormentedDemonScript.logOnceToChat("Dodge: already on a safe tile (or none found).");
                return;
            }
            Rs2Walker.walkFastCanvas(safeTile);
            boolean landed = sleepUntil(() -> safeTile.equals(Rs2Player.getWorldLocation()), DODGE_LAND_TIMEOUT_MS);
            if (landed) {
                tormentedDemonScript.logOnceToChat("Successfully dodged Tormented Demon special attack.");
            } else {
                tormentedDemonScript.logOnceToChat("Dodge walk did not land on " + safeTile);
            }
        } catch (Exception e) {
            tormentedDemonScript.logOnceToChat("Error during dodging: " + e.getMessage());
        } finally {
            dangerousTiles.clear();
            dodgeScheduled.set(false);
            Microbot.pauseAllScripts.compareAndSet(true, false);
        }
    }

    /**
     * Nearest walkable tile around the player that is not covered by a special-attack graphics object.
     * Returns null when the player's current tile is already safe (no need to move) or nothing suitable
     * is in range. {@link Rs2Tile#getWalkableTilesAroundPlayer(int)} excludes the player's own tile.
     */
    private WorldPoint findNearestSafeTile() {
        WorldPoint playerLocation = Rs2Player.getWorldLocation();
        if (playerLocation == null) {
            return null;
        }
        // If we're not standing in the AoE, staying put is the safe move.
        if (!dangerousTiles.contains(playerLocation)) {
            return null;
        }
        List<WorldPoint> walkable = Rs2Tile.getWalkableTilesAroundPlayer(DODGE_SEARCH_RADIUS);
        return walkable.stream()
                .filter(tile -> !dangerousTiles.contains(tile))
                .min(Comparator.comparingInt(playerLocation::distanceTo))
                .orElse(null);
    }

    /** Converts a graphics object to an instanced world point (matches player/walkable-tile coordinates). */
    private WorldPoint toWorldPoint(GraphicsObject graphicsObject) {
        LocalPoint localPoint = graphicsObject.getLocation();
        if (localPoint == null) {
            return null;
        }
        return WorldPoint.fromLocal(Microbot.getClient(), localPoint);
    }


    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        if (!config.enableDefensivePrayer()) {
            return;
        }
        if (!(event.getActor() instanceof NPC)) {
            return;
        }
        NPC npc = (NPC) event.getActor();

        Player localPlayer = Microbot.getClient().getLocalPlayer();
        // React to any demon that is attacking US (its target is the local player), not only the one we are
        // clicking. At the lair there are usually two demons: while we attack (and pray against) one, the
        // other can walk up and melee us — checking "am I its target" instead of "is it my target" means we
        // switch to Protect-from-Melee for that second demon instead of tanking it while praying magic.
        if (localPlayer == null || npc.getInteracting() != localPlayer) {
            return;
        }

        // Map the demon's attack animation directly to the protection prayer for that style and switch
        // immediately. Activating a new overhead auto-disables the previous one, so there's no gap.
        Rs2PrayerEnum protect;
        switch (npc.getAnimation()) {
            case MAGIC_ATTACK_ANIMATION:
                protect = Rs2PrayerEnum.PROTECT_MAGIC;
                break;
            case RANGE_ATTACK_ANIMATION:
                protect = Rs2PrayerEnum.PROTECT_RANGE;
                break;
            case MELEE_ATTACK_ANIMATION:
                protect = Rs2PrayerEnum.PROTECT_MELEE;
                break;
            default:
                return;
        }

        if (!Rs2Prayer.isPrayerActive(protect)) {
            Rs2Prayer.toggle(protect, true);
        }
    }

}

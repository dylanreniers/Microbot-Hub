package net.runelite.client.plugins.custom.golemcrafting.actions;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Durable state for the golem-crafting loop, living across ticks and mutated both by the action
 * pipeline and by the plugin's chat-message handler (fur-count parsing). One instance per run.
 */
@Getter
@Setter
public class GolemContext {

    /** Current phase; the pipeline actions each gate on this. */
    private GolemPhase phase = GolemPhase.SETUP;

    // --- Config snapshot (set in onInitialize) ---
    private int golemsPerTrip = 5;
    private int furItemId = 0;
    private String furName = "";
    private boolean useMonolith = false;
    private boolean bankForFurs = true;
    private boolean useGemBag = false;
    private CraftingMode craftingMode = CraftingMode.LAZY;

    /** Sunstone to mine this trip (golemsTarget * 5); computed when a mining phase begins. */
    private int sunstoneTarget = 0;
    /** Cores/golems to make this trip (capped by inventory space and available furs). */
    private int golemsTarget = 0;
    /** True once a trip's batch size has been computed; cleared at trip end and after banking. */
    private boolean tripActive = false;

    /**
     * Best-known furs left in the open pouch. -1 = unknown (triggers a Check). Parsed from the pouch
     * Check chat message and decremented as golems complete.
     */
    private int furRemaining = -1;
    /** Set when we've clicked Check and are waiting for the chat message to be parsed. */
    private boolean furCheckPending = false;
    /** Consecutive bank trips that came back still empty — guards against an endless bank loop. */
    private int emptyBankCount = 0;

    /** The plinth tile we're currently building on, so every step targets the same object. */
    private net.runelite.api.coords.WorldPoint currentPlinth;
    /** True while a golem is mid-build (between Start-golem and the gem drop). */
    private boolean golemActive = false;
    /** The plinth we last finished, so the next golem prefers a different (already-free) plinth. */
    private net.runelite.api.coords.WorldPoint lastCompletedPlinth;
    /** Standing tiles for the 4 sides in carve order (last = the front, where the core is inserted). */
    private net.runelite.api.coords.WorldPoint[] carveOrder;
    /** Sides carved on the current golem (0..4); advanced by the "finished this angle" chat message. */
    private int sidesCarved = 0;
    /** Signature ("side:action") of the last carve/insert click, so we click each step only once. */
    private String lastClickSig = "";
    /** When we last issued a carve/insert click, to allow a retry if it silently missed. */
    private long lastCarveMs = 0;
    /** Cores in inventory last tick — a drop means a golem just completed (fur consumed). */
    private int lastCoreCount = -1;
    /** Grace deadline to stay put and collect a finished golem's ground drops before the next golem. */
    private long lootDeadlineMs = 0;
    /** Rock tile last mined, so momentum mining can hop to a fresh rock. */
    private net.runelite.api.coords.WorldPoint lastRock;
    /** Index into the fixed momentum rotation ({@code GolemConstants.ROCK_ROTATION}). */
    private int rockIndex = 0;
    /** Sunstone count at the last rotation step, to detect the XP/ore drop that advances the rotation. */
    private int lastMineCount = 0;

    // --- Stats ---
    private final Instant startTime = Instant.now();
    private int golemsCompleted = 0;
    private int gemsLooted = 0;
    private String status = "Starting";

    /**
     * Begins a fresh trip: batch size is the smallest of the configured target, what the inventory
     * holds, and the furs available (each golem needs one). The sunstone to mine accounts for cores
     * already carried ({@code existingCores}) so a pre-stocked inventory isn't over-mined — we need
     * {@code golems*4} bodies plus one sunstone per core still to chisel. Clears per-trip build state.
     */
    public void beginTrip(int maxGolemsThatFit, int furCap, int existingCores) {
        int target = Math.min(golemsPerTrip, maxGolemsThatFit);
        if (furCap >= 0) {
            target = Math.min(target, furCap);
        }
        this.golemsTarget = Math.max(1, target);
        int coresToChisel = Math.max(0, golemsTarget - existingCores);
        this.sunstoneTarget = golemsTarget * 4 + coresToChisel;
        this.currentPlinth = null;
        this.tripActive = true;
    }
}

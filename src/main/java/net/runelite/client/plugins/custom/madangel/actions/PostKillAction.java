package net.runelite.client.plugins.custom.madangel.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameObject;
import net.runelite.client.plugins.custom.madangel.actions.MadAngelContext.PostKillPhase;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.models.RS2Item;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Post-kill loop, driven once the boss dies (a live angel was seen, now gone):
 * <ol>
 *   <li>turn off ALL prayers,</li>
 *   <li>pick up the loot (value threshold + untradeables) until pickups stop,</li>
 *   <li>click the church pew ({@link MadAngelHelpers#PEW_OBJECT_ID}),</li>
 *   <li>wait for the dialogue and click "Yes!" to start the next kill.</li>
 * </ol>
 * Runs early in the pipeline so it owns behaviour between kills; prayer-maintenance actions and the
 * default Protect-from-Melee are suppressed while {@code postKillPhase != NONE} so prayers stay off.
 */
@Slf4j
public class PostKillAction implements MadAngelAction {

    @Override
    public int order() {
        return 100; // before the combat actions (attack/prayers), which idle without a target anyway
    }

    @Override
    public String key() {
        return "post-kill";
    }

    @Override
    public boolean needsExecution(MadAngelState state) {
        if (!state.config().enablePostKill()) {
            return false;
        }
        MadAngelContext ctx = state.context();
        if (ctx.isInPostKill()) {
            // The ENTER phase is owned by EnterArenaAction; PostKillAction handles the rest.
            return ctx.getPostKillPhase() != PostKillPhase.ENTER;
        }
        // Detect the kill ending: we saw a live angel and now none is alive.
        if (MadAngelHelpers.isAngelAlive()) {
            ctx.setSawLiveAngel(true);
            return false;
        }
        return ctx.isSawLiveAngel();
    }

    @Override
    public Object execute(MadAngelState state) {
        MadAngelContext ctx = state.context();
        log.info("post kill sequence");
        // Mark that we're in the post-kill sequence BEFORE anything else, so isInPostKill() is true for
        // the whole (blocking) sequence. Otherwise the phase stays NONE while we loot and the plugin's
        // onGameTick re-enables Protect-from-Melee (and lingering smite flicks re-enable Protect-Magic),
        // which is why prayers came back on during looting. Also clear any reaction flags still armed from
        // the final attacks (e.g. an enrage smite whose flick schedule outlives the kill).
        ctx.setPostKillPhase(PostKillPhase.LOOT);
        ctx.setSmiteActive(false);
        ctx.setSmitePrayerOn(false);
        ctx.setSweepActive(false);
        ctx.setSweepPlanActive(false);
        ctx.setBlastActive(false);
        ctx.setSawLiveAngel(false);

        Rs2Prayer.disableAllPrayers();
        log.info("prayers disabled");
        sleepUntil(() -> lootablePresent());
        log.info("loot present");
        lootStep();
        log.info("[mad-angel] loot done -> heading to church pew");

        GameObject leave = pew(MadAngelHelpers.LEAVE_OBJECT_ID);
        if (leave != null) {
            Rs2Camera.turnTo(leave); // face the pew before clicking it
            Rs2GameObject.interact(leave);
            log.info("leaving");
        }

        sleepUntil(Rs2Dialogue::isInDialogue);
        log.info("needs to click yes");
        Rs2Dialogue.clickOption("Yes!");

        log.info("clicked yes");
        sleepUntil(() -> pew(MadAngelHelpers.LEAVE_OBJECT_ID) == null);
        sleep(1800);
        state.context().setPostKillPhase(PostKillPhase.ENTER);
        return "leaving";

    }

    /** Whether there's currently a drop on the ground within loot range (read directly off the scene). */
    private boolean lootablePresent() {
        return groundItems().length > 0;
    }

    /**
     * Loots the nearest ground item, one per call. Reads the scene directly via {@link Rs2GroundItem#getAll}
     * on the client thread and {@code interact()}s the item — the Zulrah pattern — because the value-based
     * looters rely on the GroundItemsPlugin table / GE-price lookups and silently no-op here. Skips items
     * the inventory can't hold.
     */
    private void lootStep() {
        for (RS2Item item : groundItems()) {
            String name = item.getItem() != null ? item.getItem().getName() : null;
            if (name == null || Rs2Inventory.isFull(name)) {
                continue;
            }
            Rs2GroundItem.interact(item);
            log.info("looting...");
            sleep(Rs2Random.betweenInclusive(1200, 1500));
        }
    }

    /** Nearest church-pew object with the given id within search range, or null. */
    private GameObject pew(int id) {
        return Rs2GameObject.getGameObject(o -> o.getId() == id, MadAngelHelpers.PEW_SEARCH_RANGE);
    }

    /** All ground items in loot range, read on the client thread (empty array on failure). */
    private RS2Item[] groundItems() {
        return Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Rs2GroundItem.getAll(MadAngelHelpers.LOOT_RANGE))
                .orElse(new RS2Item[]{});
    }

    private void enterPhase(MadAngelContext ctx, PostKillPhase phase, long now) {
        ctx.setPostKillPhase(phase);
        ctx.setPostKillPhaseMs(now);
    }

    /** Throttled progress log (~once/sec) so a stuck phase is visible without spamming every 50 ms tick. */
    private long lastHeartbeatMs;

    private void heartbeat(long now, String msg) {
        if (now - lastHeartbeatMs > 1000) {
            lastHeartbeatMs = now;
            log.info("[mad-angel] post-kill {}", msg);
        }
    }
}

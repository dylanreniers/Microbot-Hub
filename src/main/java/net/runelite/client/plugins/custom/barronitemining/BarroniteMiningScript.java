package net.runelite.client.plugins.custom.barronitemining;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.concurrent.TimeUnit;

/**
 * Mines Barronite deposits in Camdozaal, crushes them at the Barronite Crusher for shards, and banks any
 * junk (gems/ore/uncut items) that the crusher doesn't consume. Kept deliberately simple: one blocking
 * decision per scheduler pass, driven by {@code sleepUntil}, no action pipeline.
 *
 * <p>Gems drop while mining, so the state is discriminated by whether raw deposits are still present:
 * while deposits remain we keep mining/crushing; once they're gone the only non-tool items left are the
 * gems, which we bank.
 *
 * <p>Cycle:
 * <ol>
 *   <li>inventory full and holding deposits -> walk to the crusher and Smith all deposits into shards,</li>
 *   <li>no deposits left but gems remain -> walk to the bank and deposit everything except hammer/shards,</li>
 *   <li>otherwise walk to the mining spot and mine the nearest Barronite rock until the inventory fills.</li>
 * </ol>
 */
@Slf4j
public class BarroniteMiningScript extends Script {

    // --- Object ids ---
    private static final Integer[] BARRONITE_ROCKS = {41547, 41548}; // live, minable
    private static final int DEPLETED_ROCK = 41549;                  // "Rocks" (mined-out)
    private static final int BARRONITE_CRUSHER = 41551;

    // --- Item ids ---
    private static final int HAMMER = 2347;
    private static final int BARRONITE_SHARDS = 25676;
    private static final int BARRONITE_DEPOSIT = 25684;

    // --- Menu actions ---
    private static final String ACTION_MINE = "Mine";
    private static final String ACTION_SMITH = "Smith";

    // --- Tiles ---
    private static final WorldPoint MINING_SPOT = new WorldPoint(2930, 5808, 0);
    private static final WorldPoint CRUSHER_SPOT = new WorldPoint(2957, 5807, 0);
    private static final WorldPoint BANK_SPOT = new WorldPoint(2978, 5798, 0);

    @Getter
    private String status = "Starting";

    public boolean run(BarroniteMiningConfig config) {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!super.run() || !Microbot.isLoggedIn()) {
                    return;
                }

                // Full of deposits -> crush them into shards at the crusher.
                if (Rs2Inventory.isFull() && Rs2Inventory.hasItem(BARRONITE_DEPOSIT)) {
                    crushDeposits();
                    return;
                }

                // Gems drop WHILE mining, so we only bank once the deposits are gone (i.e. we've already
                // crushed). While deposits remain we keep mining even with gems in the inventory.
                if (!Rs2Inventory.hasItem(BARRONITE_DEPOSIT) && hasJunk()) {
                    bankJunk();
                    return;
                }

                mine();
            } catch (Exception ex) {
                log.error("[barronite] error during loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    /** Walk to the mining spot and mine the nearest Barronite rock. */
    private void mine() {
        if (Rs2Player.getWorldLocation().distanceTo(MINING_SPOT) > 12) {
            setStatus("Walking to the mine");
            Rs2Walker.walkTo(MINING_SPOT, 6);
            return;
        }

        // Already mining? leave it be.
        if (Rs2Player.isAnimating(1200)) {
            setStatus("Mining");
            return;
        }

        // Barronite rocks are WALL objects, so use getTileObject (getGameObject skips walls). Returns the
        // nearest of the two live-rock ids.
        TileObject rock = Rs2GameObject.getTileObject(BARRONITE_ROCKS);
        if (rock == null) {
            setStatus("Waiting for a Barronite rock");
            sleep(600, 1000);
            return;
        }

        setStatus("Mining nearest Barronite rock");
        if (Rs2GameObject.interact(rock, ACTION_MINE)) {
            // One Mine click per rock: block until the mining animation actually starts (10s covers the
            // walk to the wall). Waiting on the animation rather than on movement stops us re-clicking
            // the rock repeatedly while still walking there. Once mining, let it run until the rock
            // depletes or the inventory fills.
            if (sleepUntil(Rs2Player::isAnimating, 10000)) {
                sleepUntil(() -> !Rs2Player.isAnimating() || Rs2Inventory.isFull(), 60000);
            }
        }
    }

    /** Smith every barronite deposit at the crusher until none remain in the inventory. */
    private void crushDeposits() {
        if (Rs2Player.getWorldLocation().distanceTo(CRUSHER_SPOT) > 8) {
            setStatus("Walking to the crusher");
            Rs2Walker.walkTo(CRUSHER_SPOT, 4);
            return;
        }

        setStatus("Crushing barronite deposits");
        if (Rs2GameObject.interact(BARRONITE_CRUSHER, ACTION_SMITH)) {
            // The crusher chews through the whole stack over several ticks; wait until it's all gone.
            sleepUntil(() -> !Rs2Inventory.hasItem(BARRONITE_DEPOSIT), 120000);
        }
    }

    /** Bank everything except the hammer and barronite shards. */
    private void bankJunk() {
        if (Rs2Player.getWorldLocation().distanceTo(BANK_SPOT) > 8) {
            setStatus("Walking to the bank");
            Rs2Walker.walkTo(BANK_SPOT, 4);
            return;
        }

        if (!Rs2Bank.isOpen()) {
            setStatus("Opening the bank");
            Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen, 8000);
            return;
        }

        setStatus("Banking junk");
        Rs2Bank.depositAllExcept(HAMMER, BARRONITE_SHARDS);
        sleepUntil(() -> !hasJunk(), 5000);
        Rs2Bank.closeBank();
    }

    /**
     * True if the inventory holds anything that isn't the hammer or barronite shards, excluding raw
     * barronite deposits (those are crushed at the crusher, never banked). So this only trips on the
     * gems/ore/uncut junk picked up while mining, which we bank once the deposits have been crushed.
     */
    private boolean hasJunk() {
        return Rs2Inventory.items()
                .anyMatch(i -> i.getId() != HAMMER
                        && i.getId() != BARRONITE_SHARDS
                        && i.getId() != BARRONITE_DEPOSIT);
    }

    private void setStatus(String s) {
        this.status = s;
        Microbot.status = s;
    }
}

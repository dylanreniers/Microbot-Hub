package net.runelite.client.plugins.custom.pyramidplunder;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Automates the Pyramid Plunder minigame (Sophanem / Jalsavrah). Kept deliberately simple: one blocking
 * decision per scheduler pass, driven by {@code sleepUntil}, no action pipeline.
 *
 * <p>Loop:
 * <ol>
 *   <li>not in a game -> talk to the Guardian mummy to (re-)enter,</li>
 *   <li>in a game -> first deactivate the room's entrance spear trap (Pass until "You deactivate the
 *       trap!"), then from {@code lootChestFromRoom} onward search the Grand Gold Chest, then advance to
 *       the next room by trying the four tomb doors (one is real; the 4th always works after 3 fails),</li>
 *   <li>once at the deepest reachable room (Thieving-gated) or with the timer low, loot the remaining
 *       urns, then Quick-leave via the exit door to end the game early and re-enter.</li>
 * </ol>
 * Low-value pottery/stone artefacts are dropped when the inventory is nearly full.
 */
@Slf4j
public class PyramidPlunderScript extends Script {

    // --- Varbits ---
    private static final int VARBIT_TIMER = 2375;  // NTK_PLAYER_TIMER_COUNT; 0 outside a game, counts up in one
    private static final int VARBIT_ROOM = 2377;   // NTK_ROOM_NUMBER
    private static final int GAME_TICKS_TOTAL = 501;

    // --- NPCs ---
    private static final int GUARDIAN_MUMMY = 1779;

    // --- Objects ---
    // Chest and sarcophagus are searched while CLOSED and change id once opened, so we gate on the closed
    // id being present (loot it) vs gone (opened -> move on) rather than a per-room flag.
    private static final int CHEST_CLOSED = 20946;       // NTK_GOLDEN_CHEST_CLOSED (-> 20947 open)
    private static final int SARCOPHAGUS_CLOSED = 21255; // NTK_SARCOPHAGUS (-> 21256 open)
    private static final int SPEARTRAP = 21280;          // NTK_SPEARTRAP_INMOTION (room entrance hazard)
    private static final int EXIT_DOOR = 20931;          // NTK_TOMB_DOOR_EXIT (Quick-leave the pyramid)
    private static final int[] TOMB_DOORS = {26618, 26619, 26620, 26621}; // NTK_TOMB_DOOR1..4
    private static final int LEAVE_TOMB_DOOR = 20932;    // abandoned-room exit back to the entrance chamber

    /**
     * The four tomb-door tiles in each room (by room number from {@link #VARBIT_ROOM}). Targeting the
     * current room's known door tiles — rather than the nearest tomb-door object of any room — is what
     * keeps us from wandering into adjacent rooms' doors. Room 8 has no entry (it's the deepest).
     */
    private static final Map<Integer, WorldPoint[]> ROOM_DOORS = Map.of(
            1, new WorldPoint[]{wp(1924, 4472), wp(1931, 4472), wp(1932, 4466), wp(1923, 4466)},
            2, new WorldPoint[]{wp(1957, 4472), wp(1960, 4468), wp(1959, 4465), wp(1949, 4465)},
            3, new WorldPoint[]{wp(1969, 4460), wp(1980, 4458), wp(1976, 4453), wp(1974, 4453)},
            4, new WorldPoint[]{wp(1932, 4450), wp(1937, 4448), wp(1937, 4459), wp(1941, 4456)},
            5, new WorldPoint[]{wp(1962, 4448), wp(1957, 4444), wp(1953, 4445), wp(1952, 4447)},
            6, new WorldPoint[]{wp(1931, 4432), wp(1923, 4432), wp(1925, 4440), wp(1929, 4440)},
            7, new WorldPoint[]{wp(1946, 4433), wp(1955, 4430), wp(1954, 4424), wp(1949, 4422)}
    );

    private static WorldPoint wp(int x, int y) {
        return new WorldPoint(x, y, 0);
    }
    // The four "An anonymous looking door" entrances (ids 26622-26625, spread around the overworld
    // pyramid); one leads to the Guardian mummy, the rest to abandoned rooms. Matched by name so all four
    // are covered regardless of id.
    private static final String ANON_DOOR_NAME = "An anonymous looking door";
    private static final int[] URNS = {
            26580, 26600, 26601, 26602, 26603, 26604, 26605, 26606, 26607,
            26608, 26609, 26610, 26611, 26612, 26613
    };

    private static final int LOCKPICK = 1523; // kept when banking (helps pick tomb doors)

    // --- Items: low-value artefacts to drop (keep gold tier, ivory comb, sceptre) ---
    private static final int[] JUNK_ITEMS = {
            9030, // Stone scarab
            9032, // Pottery scarab
            9036, // Pottery statuette
            9038, // Stone statuette
            9042  // Stone seal
    };

    // --- Menu actions ---
    private static final String ACTION_ENTER = "Start-minigame";  // Guardian mummy: begin plundering
    private static final String ACTION_SEARCH = "Search";     // chests / urns
    private static final String ACTION_PASS = "Pass";         // spear trap
    private static final String ACTION_PICK_LOCK = "Pick-lock"; // tomb doors
    private static final String ACTION_QUICK_LEAVE = "Quick-leave"; // exit door
    private static final String ACTION_LEAVE_TOMB = "Leave Tomb";   // abandoned-room exit door

    @Getter
    private String status = "Starting";

    /** Tomb-door tiles already tried in the current room, so we cycle through all four. */
    private final Set<WorldPoint> triedDoors = new HashSet<>();
    private int doorRoom = -1;

    /**
     * Spear-trap state for the current room, set from chat by the plugin: {@code null} = not yet resolved
     * this room (keep trying), {@code FALSE} = last attempt failed (retry), {@code TRUE} = "You deactivate
     * the trap!" (proceed). Reset to {@code null} whenever the room changes.
     */
    private volatile Boolean trapDeactivated = null;
    private int trapRoom = -1;

    /** The entrance chamber (where the anonymous doors are), recorded live so we can walk back after banking. */
    private WorldPoint entranceChamber = null;
    /** Anonymous entrance doors already opened this attempt (led to abandoned rooms); cycle through them. */
    private final Set<Integer> triedAnonDoors = new HashSet<>();

    /** Set by the plugin's chat listener when a picked door reports "This door leads to a dead end." */
    private volatile boolean doorDeadEnd = false;
    /** Set when a pick-lock reports "Your attempt fails." — retry the SAME door rather than moving on. */
    private volatile boolean doorAttemptFailed = false;

    public boolean run(PyramidPlunderConfig config) {
        triedDoors.clear();
        doorRoom = -1;
        trapDeactivated = null;
        trapRoom = -1;
        triedAnonDoors.clear();
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!super.run() || !Microbot.isLoggedIn()) {
                    return;
                }

                if (!inGame()) {
                    handleOutOfGame(config);
                    return;
                }

                handleInGame(config);
            } catch (Exception ex) {
                log.error("[pyramidplunder] error during loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    private boolean inGame() {
        return Microbot.getVarbitValue(VARBIT_TIMER) > 0;
    }

    private int currentRoom() {
        return Microbot.getVarbitValue(VARBIT_ROOM);
    }

    private int secondsRemaining() {
        return (int) ((GAME_TICKS_TOTAL - Microbot.getVarbitValue(VARBIT_TIMER)) * 0.6);
    }

    /** Between games: bank if nearly full, otherwise (re-)enter via the anonymous doors. */
    private void handleOutOfGame(PyramidPlunderConfig config) {
        // Whenever the anonymous doors are in view we're in the entrance chamber — remember it so we can
        // walk back here after a bank trip.
        if (anonDoorPresent()) {
            entranceChamber = Rs2Player.getWorldLocation();
        }

        if (config.bankAtSophanem() && Rs2Inventory.getEmptySlots() < 4) {
            bankRun();
            return;
        }

        enterPyramid();
    }

    /** Bank the loot at the Sophanem Dungeon (keeping lockpicks), then head back to the entrance chamber. */
    private void bankRun() {
        if (!Rs2Bank.isOpen()) {
            setStatus("Banking at Sophanem");
            Rs2Bank.walkToBankAndUseBank(BankLocation.SOPHANEM);
            sleepUntil(Rs2Bank::isOpen, 8000);
            return;
        }
        Rs2Bank.depositAllExcept(LOCKPICK);
        sleepUntil(() -> Rs2Inventory.getEmptySlots() >= 4, 4000);
        Rs2Bank.closeBank();
        if (entranceChamber != null) {
            setStatus("Returning to the pyramid");
            Rs2Walker.walkTo(entranceChamber, 4);
            sleepUntil(this::anonDoorPresent, 20000);
        }
    }

    /**
     * (Re-)enters the pyramid: talks to the Guardian mummy if it's in the room, leaves an abandoned room
     * via the "Leave Tomb" door if it isn't, and otherwise tries the anonymous entrance doors one by one.
     */
    private void enterPyramid() {
        if (Rs2Dialogue.isInDialogue()) {
            Rs2Dialogue.clickContinue();
            sleep(600, 1000);
            return;
        }

        // Mummy in the room -> start the minigame.
        if (Rs2Npc.getNpc(GUARDIAN_MUMMY) != null) {
            setStatus("Starting the minigame");
            if (Rs2Npc.interact(GUARDIAN_MUMMY, ACTION_ENTER)) {
                sleepUntil(() -> inGame() || Rs2Dialogue.isInDialogue(), 6000);
                if (inGame()) {
                    triedAnonDoors.clear();
                }
            }
            return;
        }

        // Abandoned room (a Leave-Tomb door but no anonymous doors) -> back out to the chamber.
        if (!anonDoorPresent() && obj(LEAVE_TOMB_DOOR) != null) {
            setStatus("Abandoned room — leaving");
            if (interact(obj(LEAVE_TOMB_DOOR), ACTION_LEAVE_TOMB)) {
                sleepUntil(this::anonDoorPresent, 6000);
            }
            return;
        }

        // Outside at the pyramid -> try an untried anonymous door (walk to it first if it's far, since the
        // four are spread around the perimeter).
        Rs2TileObjectModel anon = nextUntriedAnonDoor();
        if (anon != null) {
            WorldPoint doorLoc = anon.getWorldLocation();
            // Only walk when the door is well away (they ring the pyramid). Use a loose tolerance so we
            // don't target the door's own (unwalkable) tile; clicking the door auto-walks the last steps.
            if (playerDistance(doorLoc) > 8) {
                setStatus("Walking to an anonymous door");
                Rs2Walker.walkTo(doorLoc, 4);
                return;
            }
            setStatus("Searching an anonymous door");
            if (interact(anon, ACTION_SEARCH)) {
                triedAnonDoors.add(anon.getId());
                // Wait until we've actually gone INSIDE (interior chamber/room), not merely started walking
                // to the door — otherwise we'd click the next door while still en route to this one.
                sleepUntil(this::insidePyramid, 10000);
            }
            return;
        }

        // No mummy, no anonymous doors (e.g. just came back from the bank) -> walk to the chamber.
        if (entranceChamber != null) {
            setStatus("Walking to the pyramid entrance");
            Rs2Walker.walkTo(entranceChamber, 4);
            sleepUntil(this::anonDoorPresent, 20000);
        } else {
            setStatus("Waiting near the pyramid entrance");
            sleep(600, 1000);
        }
    }

    /** All loaded anonymous entrance doors (matched by name; scene-wide, they ring the pyramid). */
    private List<Rs2TileObjectModel> anonDoors() {
        return Microbot.getRs2TileObjectCache().query().withName(ANON_DOOR_NAME).toListOnClientThread();
    }

    private boolean anonDoorPresent() {
        return !anonDoors().isEmpty();
    }

    /** The nearest anonymous door we haven't searched yet this attempt, or null if all have been tried. */
    private Rs2TileObjectModel nextUntriedAnonDoor() {
        return anonDoors().stream()
                .filter(o -> !triedAnonDoors.contains(o.getId()))
                .min(Comparator.comparingInt(o -> playerDistance(o.getWorldLocation())))
                .orElseGet(() -> {
                    // All four tried without finding the mummy (shouldn't happen) -> reset and retry.
                    triedAnonDoors.clear();
                    return null;
                });
    }

    private void handleInGame(PyramidPlunderConfig config) {
        int room = currentRoom();

        // New room -> the trap must be re-cleared (chest/sarcophagus gate on their closed-object id, so they
        // need no per-room flag).
        if (room != trapRoom) {
            trapDeactivated = null;
            trapRoom = room;
        }

        // 0. Every room's entrance is guarded by a spear trap: Pass it until the plugin's chat listener
        //    sets trapDeactivated=TRUE ("You deactivate the trap!"). The trap object stays put even once
        //    disarmed, so the chat flag — not the object — is what tells us we're done. Nothing else in
        //    the room is reachable until then, so this takes priority.
        if (trapDeactivated != Boolean.TRUE && spearTrap() != null) {
            passSpearTrap();
            return;
        }

        // Keep space for the good loot by dropping junk when nearly full — but only when there's actually
        // junk to drop, otherwise the branch would spin forever on an inventory full of valuables.
        if (config.dropJunk() && Rs2Inventory.getEmptySlots() <= 2 && Rs2Inventory.hasItem(JUNK_ITEMS)) {
            setStatus("Dropping low-value loot");
            Rs2Inventory.dropAll(JUNK_ITEMS);
            sleep(400, 700);
            return;
        }

        // 1. Sarcophagus first — search it while it's closed; it changes id once opened, so a still-present
        //    closed sarcophagus means it's not looted yet. Poll until it opens (or times out).
        if (room >= config.lootSarcophagusFromRoom() && obj(SARCOPHAGUS_CLOSED) != null) {
            setStatus("Looting the Sarcophagus (room " + room + ")");
            if (interact(obj(SARCOPHAGUS_CLOSED), ACTION_SEARCH)) {
                sleepUntil(() -> obj(SARCOPHAGUS_CLOSED) == null, 10000);
            }
            return;
        }

        // 1b. Grand Gold Chest — same idea: loot while closed, done once the closed chest is gone.
        if (room >= config.lootChestFromRoom() && obj(CHEST_CLOSED) != null) {
            setStatus("Looting the Grand Gold Chest (room " + room + ")");
            if (interact(obj(CHEST_CLOSED), ACTION_SEARCH)) {
                sleepUntil(() -> obj(CHEST_CLOSED) == null, 6000);
            }
            return;
        }

        // 2. Advance while we can still go deeper and the timer is healthy.
        boolean canGoDeeper = room < maxReachableRoom();
        boolean timerHealthy = secondsRemaining() > config.saveLastRoomSeconds();
        if (canGoDeeper && timerHealthy) {
            advanceToNextRoom(room);
            return;
        }

        // 3. Final reachable room (or the timer says stop): loot the remaining urns while there's space,
        //    unless urns are disabled (rush mode — leave immediately once chests/sarcophagi are done).
        if (config.lootUrns() && Rs2Inventory.getEmptySlots() > 0 && obj(URNS) != null) {
            lootUrns();
            return;
        }

        // 4. ...then Quick-leave via the exit door to end the game early and re-enter.
        quickLeave();
    }

    /** The deepest room the player's Thieving level lets them reach (rooms need 21, 31, ... 91; max 8). */
    private int maxReachableRoom() {
        int thieving = Rs2Player.getRealSkillLevel(Skill.THIEVING);
        if (thieving < 21) {
            return 1;
        }
        return Math.min(8, (thieving - 21) / 10 + 1);
    }

    /** Leaves the pyramid immediately via the exit door's Quick-leave option, ending the current game. */
    private void quickLeave() {
        Rs2TileObjectModel exit = obj(EXIT_DOOR);
        if (exit == null) {
            setStatus("Waiting for the game to end");
            sleep(600, 1000);
            return;
        }
        setStatus("Leaving the pyramid");
        if (interact(exit, ACTION_QUICK_LEAVE)) {
            sleepUntil(() -> !inGame(), 8000);
        }
    }

    /**
     * One "Pass" attempt on the room's entrance spear trap. Clears {@link #trapDeactivated} first so the
     * plugin's chat listener writes this attempt's result: TRUE ("You deactivate the trap!") = done, FALSE
     * ("You fail to deactivate the trap.") = retry. We wait for that result, then the scheduler re-enters
     * and either proceeds (TRUE) or attempts again (FALSE).
     */
    private void passSpearTrap() {
        setStatus("Deactivating the spear trap");
        trapDeactivated = null;
        if (interact(spearTrap(), ACTION_PASS)) {
            sleepUntil(() -> trapDeactivated != null, 4000);
            if (trapDeactivated == Boolean.TRUE) {
                // Deactivating walks us into the room; let it settle before the next action so we don't
                // misclick mid-walk.
                sleep(3000, 4000);
            }
        }
    }

    /** Called by the plugin's chat listener with the outcome of a spear-trap "Pass" attempt. */
    public void setTrapDeactivated(boolean deactivated) {
        this.trapDeactivated = deactivated;
    }

    /** Called by the plugin's chat listener when a picked door is a dead end (fake). */
    public void setDoorDeadEnd() {
        this.doorDeadEnd = true;
    }

    /** Called by the plugin's chat listener when a pick-lock attempt fails (retry the same door). */
    public void setDoorAttemptFailed() {
        this.doorAttemptFailed = true;
    }

    /**
     * Cycles through the four tomb doors until one leads onward (one is real; the 4th always works after
     * three fails). Picking a door has three outcomes:
     * <ul>
     *   <li>"Your attempt fails." — the pick-lock roll failed; retry the SAME door,</li>
     *   <li>"This door leads to a dead end." — a fake door; mark it tried and move to the next,</li>
     *   <li>the player is moved into the next room (room number changes) — success.</li>
     * </ul>
     * We wait for one of these before doing anything else, rather than spam-clicking all four doors.
     */
    private void advanceToNextRoom(int room) {
        if (room != doorRoom) {
            triedDoors.clear();
            doorRoom = room;
        }

        WorldPoint[] doors = ROOM_DOORS.get(room);
        if (doors == null) {
            return; // no known doors for this room (deepest room) — nothing to advance through
        }

        // Nearest of THIS room's known door tiles that we haven't confirmed a dead end yet.
        WorldPoint target = Arrays.stream(doors)
                .filter(d -> !triedDoors.contains(d))
                .min(Comparator.comparingInt(this::playerDistance))
                .orElse(null);
        if (target == null) {
            triedDoors.clear(); // all four were dead ends (shouldn't happen); reset and retry
            return;
        }

        Rs2TileObjectModel door = doorAt(target);
        if (door == null) {
            // Door tile not loaded/clickable from here — step towards it.
            setStatus("Walking to a tomb door (room " + room + ")");
            Rs2Walker.walkTo(target, 2);
            return;
        }

        WorldPoint start = Rs2Player.getWorldLocation();
        doorDeadEnd = false;
        doorAttemptFailed = false;
        setStatus("Picking a tomb door (room " + room + ")");
        if (interact(door, ACTION_PICK_LOCK)) {
            sleepUntil(() -> doorDeadEnd || doorAttemptFailed || currentRoom() != room || movedFar(start), 8000);
            if (doorDeadEnd) {
                triedDoors.add(target); // fake door — don't try this tile again this room
            }
            // A failed attempt (or timeout) leaves the door untried, so the next pass retries the same one.
        }
    }

    /** True once the player has moved well clear of {@code start} — i.e. passed through to the next room. */
    private boolean movedFar(WorldPoint start) {
        WorldPoint now = Rs2Player.getWorldLocation();
        return start != null && now != null && start.distanceTo(now) > 4;
    }

    /** The tomb-door object on (or right next to) the given tile, or null if it isn't loaded here. */
    private Rs2TileObjectModel doorAt(WorldPoint tile) {
        return Microbot.getRs2TileObjectCache().query().withIds(TOMB_DOORS).nearestOnClientThread(tile, 2);
    }

    private int playerDistance(WorldPoint tile) {
        WorldPoint me = Rs2Player.getWorldLocation();
        return me == null ? Integer.MAX_VALUE : me.distanceTo(tile);
    }

    /** True when we're inside the pyramid interior (chambers/rooms ~x1900-2010) vs the overworld entrance (~x3288). */
    private boolean insidePyramid() {
        WorldPoint p = Rs2Player.getWorldLocation();
        return p != null && p.getX() < 2500;
    }

    /** Search the nearest unopened urn. */
    private void lootUrns() {
        Rs2TileObjectModel urn = obj(URNS);
        if (urn == null) {
            setStatus("Waiting for the game to end");
            sleep(600, 1000);
            return;
        }
        setStatus("Looting urns (room " + currentRoom() + ")");
        if (interact(urn, ACTION_SEARCH)) {
            sleepUntil(() -> Rs2Player.isAnimating(), 2500);
            sleep(300, 600);
        }
    }

    // --- Tile-object cache helpers (covers game + wall objects uniformly) ---
    // Bounded to the current room: the whole pyramid scene is loaded, so an unbounded nearest() would
    // grab a neighbouring room's trap/door (~50 tiles away) and send us running across the map.
    private static final int ROOM_RANGE = 15;
    // The entrance spear trap is right where we stand, so keep it very tight to never grab another room's.
    private static final int TRAP_RANGE = 5;

    /** Nearest live object with the given id within the current room, or null. */
    private Rs2TileObjectModel obj(int id) {
        return Microbot.getRs2TileObjectCache().query().withId(id).nearestOnClientThread(ROOM_RANGE);
    }

    /** Nearest live object matching any of the given ids within the current room, or null. */
    private Rs2TileObjectModel obj(int... ids) {
        return Microbot.getRs2TileObjectCache().query().withIds(ids).nearestOnClientThread(ROOM_RANGE);
    }

    /** All live objects matching any of the given ids within the current room. */
    private List<Rs2TileObjectModel> objs(int... ids) {
        return Microbot.getRs2TileObjectCache().query().withIds(ids).within(ROOM_RANGE).toListOnClientThread();
    }

    /** The entrance spear trap right next to us (very tight range so we never target another room's), or null. */
    private Rs2TileObjectModel spearTrap() {
        return Microbot.getRs2TileObjectCache().query().withId(SPEARTRAP).nearestOnClientThread(TRAP_RANGE);
    }

    /** Clicks the object with the given action, or returns false if it's gone. */
    private boolean interact(Rs2TileObjectModel o, String action) {
        return o != null && o.click(action);
    }

    private void setStatus(String s) {
        this.status = s;
        Microbot.status = s;
    }
}

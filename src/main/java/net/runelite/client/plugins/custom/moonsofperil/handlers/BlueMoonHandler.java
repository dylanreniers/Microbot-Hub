package net.runelite.client.plugins.custom.moonsofperil.handlers;

import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.custom.moonsofperil.MoonsOfPerilConfig;
import net.runelite.client.plugins.custom.moonsofperil.MoonsOfPerilPlugin;
import net.runelite.client.plugins.custom.moonsofperil.MoonsOfPerilScript;
import net.runelite.client.plugins.custom.moonsofperil.enums.GameObjects;
import net.runelite.client.plugins.custom.moonsofperil.enums.Locations;
import net.runelite.client.plugins.custom.moonsofperil.enums.State;
import net.runelite.client.plugins.custom.moonsofperil.enums.Widgets;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.breakhandler.BreakHandlerScript;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

public class BlueMoonHandler implements BaseHandler {

    private static final String bossName = "Blue Moon";
    private static final int bossHealthBarWidgetID = Widgets.BOSS_HEALTH_BAR.getID();
    private static final int bossStatusWidgetID = Widgets.BLUE_MOON_ID.getID();
    private static final int bossStatueObjectID = GameObjects.BLUE_MOON_STATUE_ID.getID();
    private static final WorldPoint bossLobbyLocation = Locations.BLUE_LOBBY.getWorldPoint();
    private static final WorldPoint bossArenaCenter = Locations.BLUE_ARENA_CENTER.getWorldPoint();
    private static final WorldPoint[] ATTACK_TILES = Locations.blueAttackTiles();
    private static final WorldPoint AFTER_TORNADO = Locations.BLUE_ATTACK_1.getWorldPoint();
    private static final WorldPoint AFTER_GLACIER = Locations.BLUE_ICESHARD_SAFEPOT.getWorldPoint();
    private final Rs2InventorySetup equipmentNormal;
    private final MoonsOfPerilConfig cfg;
    private final boolean enableBoss;
    private final boolean disableGlacierDodge;
    private final BossHandler boss;
    private final boolean debugLogging;

    public BlueMoonHandler(MoonsOfPerilConfig cfg, Rs2InventorySetup equipmentNormal) {
        this.cfg = cfg;
        this.equipmentNormal = equipmentNormal;
        this.enableBoss = cfg.enableBlue();
        this.boss = new BossHandler(cfg);
        this.debugLogging = cfg.debugLogging();
        this.disableGlacierDodge = cfg.DisableGlacierDodge();
    }


    @Override
    public boolean validate() {
        if (!enableBoss) {
            return false;
        }
        return (boss.bossIsAlive(bossName, bossStatusWidgetID));
    }

    @Override
    public State execute() {
        if (!Rs2Widget.isWidgetVisible(bossHealthBarWidgetID)) {
            BreakHandlerScript.setLockState(true);
            boss.walkToBoss(equipmentNormal, bossName, bossLobbyLocation);
            boss.fightPreparation(equipmentNormal);
            boss.changeAttackStyle("Crush");
            boss.enterBossArena(bossName, bossStatueObjectID, bossLobbyLocation);
            sleepUntil(() -> Rs2Widget.isWidgetVisible(bossHealthBarWidgetID), 5_000);
        }
        int bossNpcID = NpcID.PMOON_BOSS_BLUE_MOON_VIS;
        while (Rs2Widget.isWidgetVisible(bossHealthBarWidgetID) || Rs2Npc.getNpc(bossNpcID) != null) {
            if (isSpecialAttack1Sequence()) {
                specialAttack1Sequence();
            } else if (isSpecialAttack2Sequence()) {
                if (!this.disableGlacierDodge) {
                    specialAttack2Sequence(this.cfg);
                } else {
                    specialAttack2IdleSequence();
                }
            } else if (BossHandler.isNormalAttackSequence()) {
                boss.normalAttackSequence(bossNpcID, ATTACK_TILES, equipmentNormal);
            }
            sleep(300);
        }
        if (debugLogging) {
            Microbot.log("The " + bossName + "boss health bar widget is no longer visible, the fight must have ended.");
        }
        Rs2Prayer.disableAllPrayers();
        sleep(2400);
        Rs2Prayer.disableAllPrayers();
        BossHandler.rechargeRunEnergy();
        BreakHandlerScript.setLockState(false);
        return State.IDLE;
    }

    /**
     * Returns True if the tornado NPC is found.
     */
    public boolean isSpecialAttack1Sequence() {
        return (Rs2Npc.getNpc(NpcID.PMOON_BOSS_WINTER_STORM) != null && Rs2Widget.isWidgetVisible(bossHealthBarWidgetID) && MoonsOfPerilScript.sigilNpc.get() == null);
    }

    public void specialAttack1Sequence() {
        sleep(2_400);
        Rs2Prayer.disableAllPrayers();
        if (debugLogging) {
            Microbot.log("Running to safe tile and waiting out the sequence");
        }

        /* var braziers = Microbot.getRs2TileObjectCache()
                .query()
                .withIds(52993, 52992).toListOnClientThread();

        if (braziers.size() == 2) {
            braziers.get(0).click();
            Microbot.log("Clicked brazier");
            sleep(5000);

            boss.eatIfNeeded();
            boss.drinkIfNeeded();

            braziers.get(1).click();
            Microbot.log("Clicked brazier");
            sleep(5000);
        } */

        Rs2Walker.walkFastCanvas(AFTER_TORNADO, true);
        boss.eatIfNeeded();
        boss.drinkIfNeeded();

        sleepUntil(() -> MoonsOfPerilScript.sigilNpc.get() != null || !Rs2Widget.isWidgetVisible(bossHealthBarWidgetID), 35_000);
    }

    public void specialAttack2IdleSequence() {
        sleep(2_400);
        Rs2Prayer.disableAllPrayers();
        if (debugLogging) {
            Microbot.log("Running to safe tile and waiting out the sequence");
        }
        Rs2Walker.walkFastCanvas(AFTER_GLACIER, true);
        boss.eatIfNeeded();
        boss.drinkIfNeeded();
        if (debugLogging) {
            Microbot.log("Sleeping until the special attack sequence is over");
        }
        sleepUntil(() -> MoonsOfPerilScript.sigilNpc.get() != null || !Rs2Widget.isWidgetVisible(bossHealthBarWidgetID), 35_000);
    }


    /**
     * Returns True if the icicle NPC is found.
     */
    public boolean isSpecialAttack2Sequence() {
        Rs2NpcModel icicle = Rs2Npc.getNpc(NpcID.PMOON_BOSS_ICICLE_UNCRACKED);
        var sigil = MoonsOfPerilScript.sigilNpc.get();
        if (icicle != null && sigil == null) {
            if (debugLogging) {
                Microbot.log("An icicle has spawned – We've entered Special Attack 2 Sequence");
            }
            return true;
        }
        return false;
    }

    /**
     * Blue Moon – Special Attack 2  (“weapon-freeze / icicle smash”)
     */
    public void specialAttack2Sequence(MoonsOfPerilConfig cfg) {
        final int ICICLE_NPC_ID = NpcID.PMOON_BOSS_ICICLE_UNCRACKED;
        final int ICICLE_ANIM_ID = AnimationID.VFX_DJINN_BLUE_ICE_BLOCK_IDLE_02;
        final long POLL_TIMEOUT_MS = 5_000;
        final long PHASE_TIMEOUT_MS = 32_000;
        final WorldPoint SAFE_SPOT = new WorldPoint(1440, 9681, 0);

        Rs2Walker.walkFastCanvas(bossArenaCenter, true);

        /* ---------- 1. Identify the animated icicle ----------------------- */
        List<Rs2NpcModel> matches = Collections.emptyList();
        long pollStart = System.currentTimeMillis();

        while (isSpecialAttack2Sequence() && matches.isEmpty() && System.currentTimeMillis() - pollStart < POLL_TIMEOUT_MS) {
            matches = Rs2Npc.getNpcs(n ->
                            n.getId() == ICICLE_NPC_ID &&
                                    n.getAnimation() == ICICLE_ANIM_ID)
                    .collect(Collectors.toList());
            if (matches.isEmpty()) {
                sleep(300);
            }
        }

        if (matches.isEmpty()) {
            return;
        }

        Rs2NpcModel icicle = matches.get(0);

        /* ---------- 2. Attack + dodge loop ------------------------------- */

         long phaseStart = System.currentTimeMillis();

        Rs2Prayer.disableAllPrayers();

        while (phaseStart + PHASE_TIMEOUT_MS > System.currentTimeMillis() && icicle.getAnimation() == ICICLE_ANIM_ID) {
            Microbot.log("starting loop.");
            if (!Rs2Combat.inCombat()) {
                Microbot.log("attacking icicle");
                Rs2Npc.attack(icicle);
            }

            LocalPoint attackTile = Rs2Player.getLocalLocation();
            sleepUntil(() -> MoonsOfPerilPlugin.icicles.contains(attackTile));
            Microbot.log("In danger");
            sleep(1200, 1800);
            var surroundingTiles = Rs2Tile.getWalkableTilesAroundPlayer(5);
            var safeTile = surroundingTiles.stream().filter(tile -> !MoonsOfPerilPlugin.icicles.contains(LocalPoint.fromWorld(Microbot.getClient().getTopLevelWorldView(), tile.getX(), tile.getY()))).findFirst();

            //var safeTiles = Rs2Tile.getSafeTiles(4);
            if (safeTile.isPresent()) {
                Rs2Walker.walkFastCanvas(safeTile.get(), true);
                sleepUntil(() -> Rs2Player.getWorldLocation().equals(safeTile.get()));
                Microbot.log("Safe");
                MoonsOfPerilPlugin.icicles.clear();
            }
        }

        Rs2Walker.walkFastCanvas(SAFE_SPOT, true);
        boss.eatIfNeeded();
        boss.drinkIfNeeded();

        sleepUntil(() -> Rs2Npc.getNpc(ICICLE_NPC_ID) == null);
        MoonsOfPerilPlugin.icicles.clear();
        /* Rs2InventorySetup invSetup = equipmentNormal;
        while (!invSetup.doesEquipmentMatch() &&
                System.currentTimeMillis() - phaseStart < PHASE_TIMEOUT_MS) {
            if (!Rs2Combat.inCombat()) {
                Rs2Npc.attack(icicle);
            }
            WorldPoint attackTile = Rs2Player.getWorldLocation();
            sleepUntil(() -> BossHandler.inDanger(attackTile) || invSetup.doesEquipmentMatch(), 3_000);
            if (invSetup.doesEquipmentMatch()) {
                break;
            }
            if (BossHandler.inDanger(attackTile)) {
                WorldPoint safeTile = Rs2Tile.getSafeTiles(1).get(0);

                if (safeTile != null) {
                    Rs2Walker.walkFastCanvas(safeTile, true);
                    sleepUntil(() -> !BossHandler.inDanger(attackTile));
                }
            }
        }

        Rs2Walker.walkFastCanvas(SAFE_SPOT, true);
        boss.eatIfNeeded();
        boss.drinkIfNeeded();

        sleepUntil(() -> Rs2Npc.getNpc(ICICLE_NPC_ID) == null); */
    }

}

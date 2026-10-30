package net.runelite.client.plugins.custom.cerberus;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.UnderDevelopment;
import net.runelite.client.plugins.custom.cerberus.actions.CerberusContext;
import net.runelite.client.plugins.custom.cerberus.actions.CerberusContext.Soul;
import net.runelite.client.plugins.custom.cerberus.actions.CerberusContext.TripleStage;
import net.runelite.client.plugins.custom.cerberus.constants.CerberusConstants;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

import javax.inject.Inject;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Custom Cerberus killer. Full loop: fight -> loot -> supply check -> (Ferox restock -> house tablet ->
 * Key Master -> free lair) -> fight. Start it anywhere; in a lair room it fights, otherwise it runs the
 * trip first. Protection prayers:
 * <ul>
 *   <li><b>Camp</b> — one configured prayer all fight (the style you're weakest against).</li>
 *   <li><b>Triple attack</b> — attack 1, 11, 21, ... (counted as her animation going -1 -> X while she's
 *       id 5862). Pre-pray Magic before it; projectile 1242 -> Missiles; projectile 1245 -> Melee; her
 *       animation back to -1 -> camp.</li>
 *   <li><b>Summoned souls</b> — sorted west -> east at spawn; they attack 12/14/16 ticks after spawning.</li>
 * </ul>
 * All {@code @Subscribe} handlers run on the client thread and are kept LOG-FREE (Microbot's
 * GameChatAppender can deadlock the game loop when logging there); the pipeline logs instead.
 */
@PluginDescriptor(
        name = "Cerberus (Custom)",
        description = "Custom build: camps a protection prayer, flicks the triple attack and souls, dodges lava",
        tags = {"cerberus", "boss", "slayer", "prayer", "flicker", "microbot", "custom"},
        version = CerberusPlugin.version,
        minClientVersion = "2.0.13",
        cardUrl = "",
        iconUrl = "",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
@UnderDevelopment
public class CerberusPlugin extends Plugin {

    public static final String version = "0.3.6";

    /** Ticks to wait for a clicked prayer to show as active before clicking it again. A click can take two
     *  ticks to land (shared input lock); re-clicking sooner would toggle it back off once both land. */
    private static final int PRAYER_PENDING_TICKS = 2;

    // Client-thread only: the last protection prayer clicked and when.
    private Rs2PrayerEnum pendingPrayer;
    private int pendingPrayerTick = -100;
    /** Client-thread only: soul NPCs already added to a wave (so the per-tick scan doesn't re-add them). */
    private final Set<NPC> knownSouls = new HashSet<>();
    /** Client-thread only: her projectiles already handled (weak, so finished projectiles don't leak). */
    private final Set<Projectile> seenProjectiles = Collections.newSetFromMap(new WeakHashMap<>());
    /** Client-thread only: tick of the last counted attack, and of the current triple's start. */
    private int lastAttackTick = -100;
    private int tripleStartTick = -100;
    private int lastMagicProjectileTick = -100;

    @Inject
    private Client client;
    @Inject
    private CerberusConfig config;
    @Inject
    private CerberusScript script;

    @Provides
    CerberusConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(CerberusConfig.class);
    }

    private CerberusContext ctx() {
        return script.getContext();
    }

    @Override
    protected void startUp() {
        script.run();
    }

    @Override
    protected void shutDown() {
        script.shutdown();
        knownSouls.clear();
    }

    // ---- Boss lifecycle ---------------------------------------------------------------------

    @Subscribe
    public void onNpcSpawned(NpcSpawned event) {
        NPC npc = event.getNpc();
        if (npc.getId() == CerberusConstants.CERBERUS_ATTACKABLE_ID) {
            startFight(npc, true);
        } else if (CerberusConstants.isSoul(npc.getId())) {
            record("soul spawned id=" + npc.getId() + " at " + npc.getWorldLocation());
            onSoulSpawned(npc);
        }
    }

    /** She transforms in place: becoming 5862 starts the fight (attack count 0), leaving it ends it. */
    @Subscribe
    public void onNpcChanged(NpcChanged event) {
        NPC npc = event.getNpc();
        if (npc == ctx().getBoss() || CerberusConstants.isSoul(npc.getId())
                || "Cerberus".equalsIgnoreCase(npc.getName())) {
            record("npc changed " + event.getOld().getId() + " -> " + npc.getId());
        }
        if (npc.getId() == CerberusConstants.CERBERUS_ATTACKABLE_ID) {
            startFight(npc, true);
        } else if (npc == ctx().getBoss()) {
            endFight();
        } else if (CerberusConstants.isSoul(npc.getId()) && !knownSouls.contains(npc)) {
            onSoulSpawned(npc);
        }
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event) {
        NPC npc = event.getNpc();
        if (npc == ctx().getBoss()) {
            endFight();
        } else if (knownSouls.remove(npc)) {
            record("soul despawned id=" + npc.getId());
        }
    }

    /** Hits on us, tick-stamped, to line up soul/triple damage with the prayer switches. */
    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        if (ctx().isFightActive() && event.getActor() == client.getLocalPlayer()) {
            record("player hit " + event.getHitsplat().getAmount()
                    + " (overhead=" + Rs2Prayer.getActiveProtectionPrayer() + ")");
        }
    }

    /** Log-free from the client thread: queued with the tick, logged by DiagnosticsAction. */
    private void record(String event) {
        ctx().getEventLog().offer("t=" + client.getTickCount() + " " + event);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.HOPPING || event.getGameState() == GameState.LOGIN_SCREEN) {
            ctx().reset();
        }
    }

    /**
     * @param synced true when we saw her become 5862 (count starts at 0, so the next attack is the
     *               triple); false when we found her already fighting and can't know the count.
     */
    private void startFight(NPC npc, boolean synced) {
        CerberusContext ctx = ctx();
        if (ctx.getBoss() == npc) {
            return;
        }
        ctx.resetFight();
        ctx.setBoss(npc);
        ctx.setCounterSynced(synced);
        lastAttackTick = -100;
        tripleStartTick = -100;
        lastMagicProjectileTick = -100;
        seenProjectiles.clear();
        ctx.setPrayersOffPending(false);
        ctx.setTripleStage(synced ? TripleStage.MAGIC : TripleStage.NONE); // attack 1 is the triple
        applyProtectionPrayer();
    }

    /** She died (left 5862) or despawned: drop prayers, and loot if we were fighting her. */
    private void endFight() {
        ctx().resetFight();
        ctx().setPrayersOffPending(true);
        // Loot whenever we were in the room for the kill — not only from FIGHT: a stopped loop (failed trip
        // step) still fights her via these handlers, and its drops were being left on the floor.
        CerberusContext.Phase phase = ctx().getPhase();
        if (phase == CerberusContext.Phase.FIGHT || phase == CerberusContext.Phase.STOPPED) {
            ctx().setPhase(CerberusContext.Phase.LOOT);
        }
    }

    // ---- Attack counter + triple attack -----------------------------------------------------

    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        if (event.getActor() instanceof NPC && knownSouls.contains(event.getActor())) {
            NPC soul = (NPC) event.getActor();
            record("soul id=" + soul.getId() + " anim " + soul.getAnimation());
            return;
        }
        if (event.getActor() != ctx().getBoss()) {
            return;
        }
        if (event.getActor().getAnimation() != -1) {
            record("boss anim " + event.getActor().getAnimation());
        }
        observeBossAnimation(event.getActor().getAnimation());
        applyProtectionPrayer();
    }

    /**
     * Tracks her animation for the attacks that have no projectile (melee bite, lava spit) and for the end
     * of the triple. Magic/ranged attacks are counted from their projectile instead: her -1 -> X animation
     * transitions also fire between attacks and over-count. Fed by both AnimationChanged and a per-tick
     * sample; comparing against the last seen value keeps it idempotent.
     */
    private void observeBossAnimation(int animation) {
        CerberusContext ctx = ctx();
        int last = ctx.getLastBossAnimation();
        ctx.setLastBossAnimation(animation);
        if (animation == last) {
            return;
        }

        if (ctx.isTripleInProgress()) {
            // Done once her animation drops after the triple's melee hit (4489 -> 4490 -> 4491 -> -1); the
            // timeout guards against a missed end.
            boolean meleeDone = animation == -1 && ctx.getTripleStage() == TripleStage.MELEE;
            if (meleeDone || client.getTickCount() - tripleStartTick > CerberusConstants.TRIPLE_MAX_TICKS) {
                ctx.setTripleInProgress(false);
                ctx.setTripleStage(TripleStage.NONE);
            }
            return; // the triple's own animations are part of one attack
        }

        if (animation == CerberusConstants.MELEE_ATTACK_ANIMATION) {
            countAttack("melee");
        } else if (animation == CerberusConstants.LAVA_ATTACK_ANIMATION) {
            countAttack("lava");
        }
    }

    /**
     * Counts one Cerberus attack and drives the triple prediction. Each attack produces exactly one counting
     * event (a projectile, the melee/lava animation, or a soul wave); the minimum gap drops duplicates.
     */
    private void countAttack(String kind) {
        CerberusContext ctx = ctx();
        int tick = client.getTickCount();
        if (tick - lastAttackTick < CerberusConstants.MIN_ATTACK_GAP_TICKS) {
            return;
        }
        lastAttackTick = tick;
        int count = ctx.getAttackCount() + 1;
        ctx.setAttackCount(count);
        if (!ctx.isCounterSynced()) {
            record("attack #" + count + " " + kind + " (counter not synced)");
            return;
        }
        int phase = count % CerberusConstants.TRIPLE_ATTACK_INTERVAL;
        record("attack #" + count + " " + kind + (phase == 1 ? " — TRIPLE" : ""));
        if (phase == 1) {
            ctx.setTripleInProgress(true);
            ctx.setTripleStage(TripleStage.MAGIC);
            tripleStartTick = tick;
        } else if (phase == 0) {
            // This attack already launched against the camp prayer; the NEXT one is the triple.
            ctx.setTripleStage(TripleStage.MAGIC);
        }
    }

    /**
     * The triple's signature (magic then ranged within a few ticks) arrived while the count didn't expect
     * one — the count drifted, or the fight was picked up unsynced. The magic projectile was already counted
     * as an attack, so make that attack number 1 (mod 10), mark the counter synced, and finish the triple
     * from its ranged hit: the next one is then predicted correctly.
     */
    private void resyncToTriple(int tick) {
        CerberusContext ctx = ctx();
        int counted = ctx.getAttackCount();
        int interval = CerberusConstants.TRIPLE_ATTACK_INTERVAL;
        int corrected = counted - Math.floorMod(counted - 1, interval);
        if (counted - corrected > interval / 2) {
            corrected += interval; // nearer to the next triple number than the previous one
        }
        ctx.setAttackCount(corrected);
        ctx.setCounterSynced(true); // the triple pins the count, so an unsynced fight is now synced too
        ctx.setTripleInProgress(true);
        ctx.setTripleStage(TripleStage.MELEE);
        tripleStartTick = tick;
        record("triple detected out of sync — attack count " + counted + " -> " + corrected);
    }

    /**
     * Her magic (1242) and ranged (1245) projectiles — normal attacks and the triple use the same ids. Each
     * new one outside a triple counts an attack; inside the triple, magic launched -> pray Missiles and
     * ranged launched -> pray Melee.
     */
    @Subscribe
    public void onProjectileMoved(ProjectileMoved event) {
        CerberusContext ctx = ctx();
        Projectile projectile = event.getProjectile();
        int id = projectile.getId();
        if (!ctx.isFightActive()
                || (id != CerberusConstants.TRIPLE_MAGIC_PROJECTILE && id != CerberusConstants.TRIPLE_RANGED_PROJECTILE)
                || !seenProjectiles.add(projectile)) {
            return; // not one of her attack projectiles, or already handled (ProjectileMoved fires every frame)
        }
        boolean fromBoss = isFromBoss(projectile, ctx.getBoss());
        Actor source = projectile.getSourceActor();
        record("projectile " + id + " src=" + (source == null ? "null" : source.getName())
                + " from=" + projectile.getSourcePoint() + (fromBoss ? "" : " (ignored: not from Cerberus)"));
        if (!fromBoss) {
            return;
        }

        if (!ctx.isTripleInProgress()) {
            int tick = client.getTickCount();
            if (id == CerberusConstants.TRIPLE_RANGED_PROJECTILE
                    && tick - lastMagicProjectileTick <= CerberusConstants.TRIPLE_SIGNATURE_TICKS) {
                // Magic then ranged within a few ticks is the triple itself: the count missed an attack.
                resyncToTriple(tick);
            } else {
                if (id == CerberusConstants.TRIPLE_MAGIC_PROJECTILE) {
                    lastMagicProjectileTick = tick;
                }
                countAttack(id == CerberusConstants.TRIPLE_MAGIC_PROJECTILE ? "magic" : "ranged");
                if (!ctx.isTripleInProgress()) {
                    applyProtectionPrayer(); // may be the pre-pray for the next attack's triple
                    return;
                }
            }
        }

        TripleStage stage = ctx.getTripleStage();
        if (id == CerberusConstants.TRIPLE_MAGIC_PROJECTILE && stage == TripleStage.MAGIC) {
            ctx.setTripleStage(TripleStage.MISSILES);
        } else if (id == CerberusConstants.TRIPLE_RANGED_PROJECTILE
                && (stage == TripleStage.MAGIC || stage == TripleStage.MISSILES)) {
            ctx.setTripleStage(TripleStage.MELEE);
        }
        applyProtectionPrayer();
    }

    /**
     * Whether a projectile was fired by Cerberus. Matched on the source actor when the client resolves one,
     * otherwise on the launch tile lying on/next to her 5x5 body (identity alone rejected every projectile).
     */
    private boolean isFromBoss(Projectile projectile, NPC boss) {
        Actor source = projectile.getSourceActor();
        if (source instanceof NPC) {
            return source == boss || ((NPC) source).getIndex() == boss.getIndex();
        }
        if (source != null) {
            return false;
        }
        WorldPoint from = projectile.getSourcePoint();
        WorldPoint at = boss.getWorldLocation();
        return from != null && at != null && from.distanceTo(at) <= CerberusConstants.BOSS_PROJECTILE_SOURCE_RADIUS;
    }

    // ---- Summoned souls ---------------------------------------------------------------------

    /** The three souls spawn together; a spawn after the previous wave's last attack starts a new wave. */
    private void onSoulSpawned(NPC npc) {
        CerberusContext ctx = ctx();
        int tick = client.getTickCount();
        knownSouls.add(npc);
        int lastOffset = CerberusConstants.SOUL_ATTACK_OFFSETS[CerberusConstants.SOUL_ATTACK_OFFSETS.length - 1];
        if (ctx.getSoulWaveSpawnTick() < 0 || tick - ctx.getSoulWaveSpawnTick() > lastOffset) {
            ctx.getSouls().clear();
            ctx.setSoulWaveSpawnTick(tick);
            countAttack("souls");
        }
        WorldPoint location = npc.getWorldLocation();
        ctx.getSouls().add(new Soul(location == null ? 0 : location.getX(),
                CerberusConstants.soulPrayer(npc.getId())));
    }

    /**
     * The prayer for the soul about to attack, or null outside the souls' attack ticks. Soul i is prayed
     * from {@code offset_i - LEAD} until the next soul's switch; the last one through its attack tick.
     */
    private Rs2PrayerEnum soulPrayerAt(int tick) {
        CerberusContext ctx = ctx();
        if (ctx.getSoulWaveSpawnTick() < 0) {
            return null;
        }
        List<Soul> ordered = ctx.soulsInAttackOrder();
        int sinceSpawn = tick - ctx.getSoulWaveSpawnTick();
        int[] offsets = CerberusConstants.SOUL_ATTACK_OFFSETS;
        int lead = CerberusConstants.SOUL_PRAYER_LEAD_TICKS;
        for (int i = 0; i < offsets.length && i < ordered.size(); i++) {
            int end = i + 1 < offsets.length ? offsets[i + 1] - lead - 1 : offsets[i];
            if (sinceSpawn >= offsets[i] - lead && sinceSpawn <= end) {
                return ordered.get(i).getPrayer();
            }
        }
        return null;
    }

    // ---- Lava ---------------------------------------------------------------------------------

    @Subscribe
    public void onGraphicsObjectCreated(GraphicsObjectCreated event) {
        GraphicsObject go = event.getGraphicsObject();
        if (go.getId() != CerberusConstants.LAVA_GRAPHICS_OBJECT || !ctx().isFightActive()) {
            return;
        }
        LocalPoint lp = go.getLocation();
        if (lp != null) {
            ctx().getLavaPools().put(go, WorldPoint.fromLocal(client, lp));
        }
    }

    // ---- Per tick ----------------------------------------------------------------------------

    @Subscribe
    public void onGameTick(GameTick event) {
        CerberusContext ctx = ctx();
        int tick = client.getTickCount();

        if (ctx.getBoss() == null) {
            bootstrapBoss();
        }
        NPC boss = ctx.getBoss();
        if (boss == null) {
            return;
        }

        // Backstop for a missed AnimationChanged (e.g. -1 -> X -> -1 between events).
        observeBossAnimation(boss.getAnimation());

        Player local = client.getLocalPlayer();
        ctx.setEngaged(local != null && local.getInteracting() == boss);

        int ratio = boss.getHealthRatio();
        int scale = boss.getHealthScale();
        if (ratio >= 0 && scale > 0) {
            ctx.setBossHp(Math.round(CerberusConstants.CERBERUS_MAX_HP * (float) ratio / scale));
        }

        ctx.getLavaPools().keySet().removeIf(GraphicsObject::finished);

        // Fallback for a missed spawn event: any soul in the scene we haven't tracked yet starts/joins a wave.
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && CerberusConstants.isSoul(npc.getId()) && !knownSouls.contains(npc)) {
                record("soul found by scan (no spawn event) id=" + npc.getId() + " at " + npc.getWorldLocation());
                onSoulSpawned(npc);
            }
        }

        int lastOffset = CerberusConstants.SOUL_ATTACK_OFFSETS[CerberusConstants.SOUL_ATTACK_OFFSETS.length - 1];
        if (ctx.getSoulWaveSpawnTick() >= 0 && tick - ctx.getSoulWaveSpawnTick() > lastOffset) {
            ctx.getSouls().clear();
            ctx.setSoulWaveSpawnTick(-1);
        }

        // Prayer clicks share the input lock with the pipeline's mouse gestures; a slow attack/prayer click
        // started just before a flick delays it past the attack. Hold those clicks while a flick is due.
        // Only the ticks around an actual switch count — blocking for the whole soul wave / pre-pray left her
        // un-attacked long enough for the health bar to drop.
        ctx.setPrayerCritical(isFlickImminent(tick));

        applyProtectionPrayer();
    }

    /** Plugin started mid-fight: no spawn/change event fired, so pick her up unsynced. */
    private void bootstrapBoss() {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == CerberusConstants.CERBERUS_ATTACKABLE_ID) {
                startFight(npc, false);
                return;
            }
        }
    }

    // ---- Protection prayer --------------------------------------------------------------------

    /**
     * Whether a protection switch is due within the next couple of ticks, so the pipeline should hold its
     * mouse clicks (they share the input lock with the prayer click). Covers the triple and its pre-pray
     * until Magic is actually up, and the souls from just before the first switch to the last attack.
     */
    private boolean isFlickImminent(int tick) {
        CerberusContext ctx = ctx();
        if (ctx.isTripleInProgress()) {
            return true;
        }
        // Out of prayer, Protect from Magic can never come on — treating the pre-pray as imminent then held
        // every pipeline click (including the prayer drink) until the triple ended.
        if (ctx.getTripleStage() == TripleStage.MAGIC && !Rs2Prayer.isOutOfPrayer()
                && !Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_MAGIC)) {
            return true;
        }
        if (ctx.getSoulWaveSpawnTick() < 0) {
            return false;
        }
        int sinceSpawn = tick - ctx.getSoulWaveSpawnTick();
        int[] offsets = CerberusConstants.SOUL_ATTACK_OFFSETS;
        int firstSwitch = offsets[0] - CerberusConstants.SOUL_PRAYER_LEAD_TICKS;
        return sinceSpawn >= firstSwitch - CerberusConstants.FLICK_CLICK_GUARD_TICKS
                && sinceSpawn <= offsets[offsets.length - 1];
    }

    /** Souls take priority on their attack ticks, then the triple stage, otherwise the camp prayer. */
    private Rs2PrayerEnum desiredProtectionPrayer() {
        Rs2PrayerEnum soul = soulPrayerAt(client.getTickCount());
        if (soul != null) {
            return soul;
        }
        switch (ctx().getTripleStage()) {
            case MAGIC:
                return Rs2PrayerEnum.PROTECT_MAGIC;
            case MISSILES:
                return Rs2PrayerEnum.PROTECT_RANGE;
            case MELEE:
                return Rs2PrayerEnum.PROTECT_MELEE;
            default:
                return config.campPrayer().getPrayer();
        }
    }

    /**
     * Switches the overhead if needed. Always clicks — prayer beats the pipeline's click for this tick.
     * The prayer varbit only updates once the server applies the click, so a second request for the same
     * prayer before then (e.g. a projectile event and onGameTick in one tick) must not click again — that
     * would toggle it back off.
     */
    private void applyProtectionPrayer() {
        if (!ctx().isFightActive() || Rs2Prayer.isOutOfPrayer()) {
            return;
        }
        Rs2PrayerEnum desired = desiredProtectionPrayer();
        int tick = client.getTickCount();
        if (Rs2Prayer.isPrayerActive(desired)
                || (desired == pendingPrayer && tick - pendingPrayerTick <= PRAYER_PENDING_TICKS)) {
            return;
        }
        int sinceSouls = ctx().getSoulWaveSpawnTick() < 0 ? -1 : tick - ctx().getSoulWaveSpawnTick();
        record("pray " + desired + " (triple=" + ctx().getTripleStage() + ", souls+" + sinceSouls
                + ", souls=" + ctx().getSouls().size() + ")");
        Rs2Prayer.toggle(desired, true);
        pendingPrayer = desired;
        pendingPrayerTick = tick;
        ctx().markClick(tick);
    }
}

package net.runelite.client.plugins.custom.abyssalsire;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.abyssalsire.actions.SireAction;
import net.runelite.client.plugins.custom.abyssalsire.actions.SireContext;
import net.runelite.client.plugins.custom.abyssalsire.actions.SirePhase;
import net.runelite.client.plugins.custom.abyssalsire.actions.SireState;
import net.runelite.client.plugins.custom.abyssalsire.constants.SireConstants;
import net.runelite.client.plugins.custom.actions.Action;
import net.runelite.client.plugins.custom.actions.ActionScript;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;

import javax.inject.Inject;

/**
 * Abyssal Sire action-driven script. The generic pipeline wiring lives in {@link ActionScript}; here
 * we build the {@link SireContext} and run the phase state machine off the plugin's events.
 *
 * <p>Phase transitions:
 * <ul>
 *   <li><b>Phase 1</b>: Sire spawns/appears as 5886 (and 5887/5888/5990 mark the fight as started).</li>
 *   <li><b>Phase 2</b>: the Sire CHANGES back to 5886 once fighting — respiratory systems cleared.</li>
 *   <li><b>Phase 3</b>: the Sire's HP drops to/below {@link #PHASE3_HP_THRESHOLD}% (polled in
 *       {@link #advancePhaseByHp()}). Id 5908 only clears the explosion-run flag within phase 3.</li>
 *   <li><b>DEAD</b>: death animation / Sire despawn (LootAction resets for the next kill).</li>
 * </ul>
 * A one-time {@link #bootstrap()} on start/after-kill catches a Sire that was already present (so no
 * spawn event fired); the id-driven transitions flow from {@link #onSireId}.
 */
@Slf4j
public class AbyssalSireScript extends ActionScript<SireState> {

    /** Phase 3 begins once the Sire's HP falls to/below this — simpler and more reliable than tracking
     *  the walk/prep-explosion ids. */
    private static final int PHASE3_HP_THRESHOLD = 50;

    @Inject
    private AbyssalSireConfig config;

    private final SireContext context = new SireContext();

    @Override
    protected Class<? extends Action<SireState>> actionType() {
        return SireAction.class;
    }

    @Override
    protected SireState createState() {
        return new SireState(context);
    }

    @Override
    protected void onInitialize() {
        // Resolve setups LIVE by name (not the config's snapshot) so editing a setup doesn't leave a
        // stale layout — same rationale as the Zulrah plugin.
        String rangeName = config.rangeInventorySetup().getName();
        String meleeName = config.meleeInventorySetup().getName();
        log.info("[sire] setups — range: '{}', melee: '{}'", rangeName, meleeName);
        context.setRangeSetup(new Rs2InventorySetup(rangeName, mainScheduledFuture));
        context.setMeleeSetup(new Rs2InventorySetup(meleeName, mainScheduledFuture));

        context.setEatPercent(config.eatPercent());
        context.setPrayerPercent(config.prayerPercent());
        context.setLootMinValue(config.lootMinValue());
        context.setHopEnabled(config.hopIfPlayerPresent());
        context.setRestockEnabled(config.restockBetweenKills());
        context.setStartKillMinHpPercent(config.startKillMinHpPercent());
        context.setSpecEnabled(config.useSpecialAttacks());
        context.setSpecCostPercent(config.specAttackCostPercent());
        context.reset();
    }

    @Override
    public void tick() {
        // Bootstrap once per fight-start: if a Sire is already present (no spawn event), seed the phase
        // from its live id. Kept true until a Sire is found, so it also catches the post-kill respawn.
        if (context.isNeedsBootstrap()) {
            bootstrap();
        }
        advanceToPhase2IfRespiratoryCleared();
        advancePhaseByHp();
        super.tick();
    }

    /**
     * Phase 2 starts the moment every respiratory system is dead — not only on the Sire's change back
     * to 5886. A late re-barrage can re-stun the Sire to 5888 right as the last system dies, so the
     * 5886 change never arrives and we'd be stuck in phase 1; polling the live count guarantees it.
     * Guarded by having killed at least one, so a transient 0 (before they spawn) can't fire it early.
     */
    private void advanceToPhase2IfRespiratoryCleared() {
        if (context.getPhase() != SirePhase.PHASE1 || !context.isFightStarted()) {
            return;
        }
        Integer countBox = Microbot.getClientThread().invoke(
                () -> Microbot.getRs2NpcCache().query().withId(SireConstants.RESPIRATORY_SYSTEM_ID).count());
        int count = countBox == null ? 0 : countBox;
        if (count > context.getMaxRespiratorySeen()) {
            context.setMaxRespiratorySeen(count);
        }
        // Require having seen ALL FOUR up before treating count==0 as "cleared" — otherwise a transient 0
        // (before they've all spawned, or a miscount) would skip a vent and start phase 2 early. A sire
        // kill always needs all four respiratory systems down.
        int expected = SireConstants.RESPIRATORY_TILES.length;
        if (count == 0 && context.getMaxRespiratorySeen() >= expected) {
            log.info("[sire] all {} respiratory systems seen and cleared — entering phase 2", expected);
            setPhase(SirePhase.PHASE2);
        }
    }

    /**
     * Phase 3 starts when the Sire's HP drops to/below {@link #PHASE3_HP_THRESHOLD}%. HP isn't an event,
     * so we poll it each tick — but only while in phase 2, so it's cheap and can't fire early.
     */
    private void advancePhaseByHp() {
        if (context.getPhase() != SirePhase.PHASE2) {
            return;
        }
        Rs2NpcModel sire = Microbot.getRs2NpcCache().query()
                .withName(SireConstants.SIRE_NAME).nearestOnClientThread();
        if (sire == null) {
            return;
        }
        double hp = sire.getHealthPercentage();
        if (hp >= 0 && hp <= PHASE3_HP_THRESHOLD) {
            log.info("[sire] Sire HP {}% <= {}% — entering phase 3", (int) hp, PHASE3_HP_THRESHOLD);
            setPhase(SirePhase.PHASE3);
        }
    }

    @Override
    public void onShutdown() {
        reset();
    }

    public void reset() {
        context.reset();
    }

    public SireContext context() {
        return context;
    }

    private void bootstrap() {
        Rs2NpcModel sire = Microbot.getClientThread().invoke(
                () -> Microbot.getRs2NpcCache().query().withIds(SireConstants.SIRE_IDS).nearest());
        if (sire == null) {
            return; // no in-progress fight yet; keep the bootstrap armed for when one appears
        }
        context.setNeedsBootstrap(false);
        log.info("[sire] bootstrap: Sire already present as id {}", sire.getId());
        onSireId(sire.getId(), true);
    }

    // ---- Event delegates (called by AbyssalSirePlugin's @Subscribe handlers) ----

    /**
     * The phase state machine. Called for every Sire spawn ({@code isSpawn}) and in-place transform.
     * Transitions are forward-only; DEAD is inert until LootAction resets.
     */
    public void onSireId(int id, boolean isSpawn) {
        // Safety net for a MISSED death: a fresh Sire spawn while we're still past phase 1 (or stuck
        // DEAD) means the arena reset for the next kill — wipe stale state. Restricted to a spawn of the
        // STANDING id (5886), because the Sire's OWN phase transitions also come through as spawns
        // (e.g. the walk to 5889/5890 is a despawn+spawn, not an in-place change) — resetting on those
        // wiped the context mid-fight. A brand-new fight always begins with the Sire standing at 5886.
        SirePhase phase = context.getPhase();
        log.info("[sire] onSireId id={} spawn={} phase={} fightStarted={}", id, isSpawn, phase, context.isFightStarted());
        if (isSpawn && id == SireConstants.SIRE_STANDING
                && (phase == SirePhase.PHASE2 || phase == SirePhase.PHASE3 || phase == SirePhase.DEAD)) {
            log.info("[sire] fresh Sire spawn (5886) during phase {} — resetting context for the next kill", phase);
            context.reset();
        }
        context.setCurrentSireId(id);
        if (context.getPhase() == SirePhase.DEAD) {
            return; // still looting; the next fight starts from the re-armed bootstrap / a fresh spawn
        }
        switch (id) {
            case SireConstants.SIRE_STANDING: // 5886
                if (context.getPhase() == SirePhase.PHASE1 && context.isFightStarted()) {
                    setPhase(SirePhase.PHASE2); // respiratory systems cleared
                } else if (context.getPhase() == SirePhase.IDLE) {
                    setPhase(SirePhase.PHASE1); // initial appearance — fight beginning
                }
                break;
            case SireConstants.SIRE_PHASE1_AWAKE:  // 5887
            case SireConstants.SIRE_PHASE1_ASLEEP: // 5888
                context.setFightStarted(true);
                if (context.getPhase() == SirePhase.IDLE) {
                    setPhase(SirePhase.PHASE1);
                }
                break;
            case SireConstants.SIRE_HEAD_ON: // 5990 (exposed melee window)
                if (context.getPhase() == SirePhase.IDLE) {
                    context.setFightStarted(true);
                    setPhase(SirePhase.PHASE1);
                }
                break;
            case SireConstants.SIRE_POST_EXPLOSION: // 5908
                // Phase 3 is HP-driven now; this id only tells us the explosion is over so we re-engage.
                context.setExploding(false);
                break;
            default:
                break;
        }
    }

    /** The Sire's death animation fired: mark dead (LootAction takes over) and drop all prayers. */
    public void onSireDeath() {
        if (context.getPhase() == SirePhase.DEAD) {
            return; // already handled (death anim + despawn can both fire)
        }
        log.info("[sire] Sire died — disabling prayers, looting the drop");
        setPhase(SirePhase.DEAD);
        context.setDead(true);
        Rs2Prayer.disableAllPrayers();
    }

    /**
     * The Sire NPC despawned. This is NOT a reliable death signal: the Sire despawns+respawns as part
     * of its own phase transitions (e.g. the walk to id 5889/5890 comes through as a despawn+spawn), so
     * treating a despawn as death false-triggered a death (and reset) mid-fight. Death is detected from
     * the death animation (7100) via {@link #onSireDeath()} instead; kept as a no-op for the event wire.
     */
    public void onSireDespawned() {
        // intentionally no-op
    }

    /** The player was knocked back by the explosion (animation 1816): run to the escape tile. */
    public void onExplosionKnockback() {
        log.info("[sire] explosion detected");
        context.setExploding(true);
    }

    /** A barrage landed ("...disorientated temporarily."): the Sire is stunned for 46 ticks. Setting the
     *  stun timer is the signal for phase 1 to stop holding and start ranging the vents. */
    public void onSireStunned() {
        context.setSireStunnedUntilMs(System.currentTimeMillis() + SireConstants.STUN_DURATION_MS);
        log.info("[sire] barrage landed — Sire disoriented for {} ticks (~{}s); ranging vents",
                SireConstants.STUN_DURATION_TICKS, SireConstants.STUN_DURATION_MS / 1000);
    }

    /**
     * A respiratory system despawned during phase 1 — i.e. we killed it. Record its tile so a
     * re-barrage/return knows which are already down and which remain.
     */
    public void onRespiratoryKilled(WorldPoint location) {
        if (context.getPhase() != SirePhase.PHASE1 || location == null) {
            return;
        }
        WorldPoint canonical = canonicalRespiratoryTile(location);
        if (context.getKilledRespiratory().add(canonical)) {
            log.info("[sire] respiratory system down at {} ({} / {})",
                    canonical, context.getKilledRespiratory().size(), SireConstants.RESPIRATORY_TILES.length);
        }
    }

    /** Snap a despawn location to the nearest known respiratory tile (within 2), else keep it as-is. */
    private WorldPoint canonicalRespiratoryTile(WorldPoint location) {
        WorldPoint best = location;
        int bestDist = Integer.MAX_VALUE;
        for (WorldPoint tile : SireConstants.RESPIRATORY_TILES) {
            int dist = tile.distanceTo(location);
            if (dist < bestDist) {
                bestDist = dist;
                best = tile;
            }
        }
        return bestDist <= 2 ? best : location;
    }

    private void setPhase(SirePhase next) {
        SirePhase previous = context.getPhase();
        if (next != previous) {
            log.info("[sire] phase {} -> {}", previous, next);
        }
        context.setPhase(next);
    }
}

package net.runelite.client.plugins.custom.customdemonicgorilla.actions;

import lombok.extern.slf4j.Slf4j;

/**
 * Periodically logs the Phase 1-3 diagnostic tallies collected by the plugin's event handlers. Runs on
 * the script thread (the action pipeline), where logging is safe — the counters themselves are bumped on
 * the client thread, which must stay log-free (GameChatAppender deadlocks there). Lowest priority so it
 * never delays a prayer/dodge reaction, and rate-limited to once every {@link #LOG_INTERVAL_MS}.
 */
@Slf4j
public class DiagnosticsAction implements GorillaAction {

    private static final long LOG_INTERVAL_MS = 15_000;

    @Override
    public int order() {
        return 999; // last — purely observational
    }

    @Override
    public String key() {
        return "diagnostics";
    }

    @Override
    public boolean needsExecution(GorillaState state) {
        GorillaContext ctx = state.context();
        return ctx.isInitialized()
                && System.currentTimeMillis() - ctx.getDiagLastLogMs() >= LOG_INTERVAL_MS;
    }

    @Override
    public Object execute(GorillaState state) {
        GorillaContext ctx = state.context();
        ctx.setDiagLastLogMs(System.currentTimeMillis());
        log.info("[gorilla-diag] cries={} attacks(ok={},wrong={}) meleeTells={} gorProj={} projVetoes={} hits(total={},boulder={}) untilSwitch={} style={}",
                ctx.getDiagCries(),
                ctx.getDiagCorrectPrayerAttacks(),
                ctx.getDiagWrongPrayerAttacks(),
                ctx.getDiagMeleeTells(),
                ctx.getDiagGorillaProjectiles(),
                ctx.getDiagProjectileVetoes(),
                ctx.getDiagHitsTaken(),
                ctx.getDiagBoulderWindowHits(),
                ctx.getAttacksUntilSwitch(),
                ctx.getCurrentAttackStyle());
        return null;
    }
}

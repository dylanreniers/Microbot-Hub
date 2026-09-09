package net.runelite.client.plugins.custom.madangel.actions;

import lombok.extern.slf4j.Slf4j;

/**
 * Diagnostics: drains the angel's animation-change samples (enqueued log-free on the client thread by
 * {@code MadAngelPlugin.onAnimationChanged}) and logs each with the tick delta since the previous one,
 * plus the boss's HP% and phase. Runs on the script thread, where logging is safe (client-thread logging
 * deadlocks via Microbot's GameChatAppender). Purpose: measure the interval between sweep cleaves in the
 * normal vs enrage phase so the dodge can move to a fixed tick schedule instead of reacting per-cleave.
 */
@Slf4j
public class SweepTimingAction implements MadAngelAction {

    /** Enrage begins at ~45% of the boss's health. */
    private static final int ENRAGE_PERCENT = 45;

    /** Tick of the previously logged sample, so we can print the delta. Persists across ticks. */
    private int lastTick = Integer.MIN_VALUE;

    @Override
    public int order() {
        return 10; // early, before the reactions; it only reads/drains diagnostics
    }

    @Override
    public String key() {
        return "sweep-timing";
    }

    @Override
    public boolean needsExecution(MadAngelState state) {
        return state.config().logAnimTiming() && !state.context().getAnimEventLog().isEmpty();
    }

    @Override
    public Object execute(MadAngelState state) {
        MadAngelContext.AnimEvent e;
        while ((e = state.context().getAnimEventLog().poll()) != null) {
            int delta = (lastTick == Integer.MIN_VALUE) ? 0 : e.tick - lastTick;
            lastTick = e.tick;

            String hp;
            String phase;
            if (e.healthScale > 0 && e.healthRatio >= 0) {
                int pct = Math.round(100.0f * e.healthRatio / e.healthScale);
                hp = pct + "%";
                phase = pct <= ENRAGE_PERCENT ? "ENRAGE" : "normal";
            } else {
                hp = "?";
                phase = "?";
            }
            String sec = String.format("%.1f", delta * 0.6f);
            if (e.note != null) {
                // Sweep-plan simulator line: the pre-formatted cleave/side message.
                log.info("[mad-angel][plan] tick={} (+{}t / +{}s) {} [{}]", e.tick, delta, sec, e.note, phase);
            } else if ("player-hit".equals(e.kind)) {
                // For hits the "anim" field carries the damage amount, and health isn't sampled.
                log.info("[mad-angel][anim] tick={} (+{}t / +{}s) *** TOOK {} DAMAGE ***",
                        e.tick, delta, sec, e.anim);
            } else {
                log.info("[mad-angel][anim] tick={} (+{}t / +{}s) anim={} kind={} hp={} [{}]",
                        e.tick, delta, String.format("%.1f", delta * 0.6f), e.anim, e.kind, hp, phase);
            }
        }
        return null;
    }
}

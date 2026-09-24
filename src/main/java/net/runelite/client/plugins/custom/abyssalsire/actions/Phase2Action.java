package net.runelite.client.plugins.custom.abyssalsire.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.custom.abyssalsire.constants.SireConstants;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.Set;

/**
 * Phase 2: melee the standing Sire from the original spot. At the start of the phase, drink the melee
 * stat boosts (super combat / super att-str-def) once, then dump as many special attacks as the spec
 * energy allows, before settling into normal attacks. When a miasma pool lands on the original spot,
 * step two tiles east; return to the original spot only once that pool has DESPAWNED (tracked via the
 * graphics-object lifecycle, so it won't flip back while the pool is still down). Being back on the
 * original spot keeps the Sire's pathing clean when it starts moving to prep phase 3. Ends when the
 * Sire drops to 50% HP (-> phase 3).
 */
@Slf4j
public class Phase2Action implements SireAction {

    /** Upper bound on the phase-2 spec dump, so a non-spec weapon (energy never drops) can't hold us in
     *  spec mode indefinitely. Comfortably covers a full bar of specs. */
    private static final long SPEC_WINDOW_MS = 15_000;

    @Override
    public int order() {
        return 610;
    }

    @Override
    public String key() {
        return "phase2";
    }

    @Override
    public boolean needsExecution(SireState state) {
        // Stand down while a restock trip is armed/running (emergency escape or between-kills).
        return state.context().getPhase() == SirePhase.PHASE2 && !state.context().isPrepPending();
    }

    @Override
    public Object execute(SireState state) {
        SireContext ctx = state.context();
        Set<WorldPoint> pools = SireHelpers.miasmaPools(ctx);

        // Safe attack tile: the original spot, unless a pool is on it — then the dodge tile.
        WorldPoint desired = SireHelpers.miasmaOn(pools, SireConstants.ORIGINAL_POSITION)
                ? SireConstants.PHASE2_MIASMA_DODGE
                : SireConstants.ORIGINAL_POSITION;

        // 0) URGENT: a pool is on OUR CURRENT tile -> drop everything and spam-walk off it immediately
        // (before gear/boosts/spec/eat). Checking our actual tile (not just the original) means we also
        // dodge when the Sire nudged us a tile south, or when a pool lands on the dodge tile.
        WorldPoint me = SireHelpers.playerLocation();
        if (me != null && pools.contains(me)) {
            SireHelpers.spamWalkTo(desired);
            ctx.setAttackAfterMove(true); // the walk breaks the interaction; force a fresh attack on arrival
            return "miasma-dodge";
        }

        if (!SireHelpers.gearReady(ctx)) {
            return "gear-wait";
        }

        // At the start of phase 2, drink the melee stat boosts once — one consume per tick until every
        // melee stat is boosted (or there's nothing left to drink), holding the attack while we do it.
        if (!ctx.isBoostsDrunk()) {
            if (allMeleeBoosted()) {
                ctx.setBoostsDrunk(true);
            } else {
                Rs2ItemModel potion = nextBoostPotion();
                if (potion != null) {
                    log.info("[sire] phase 2: drinking {}", potion.getName());
                    int before = boostedMeleeSum();
                    Rs2Inventory.interact(potion, "Drink");
                    // Wait for the boost to actually register before the next evaluation, so we don't
                    // hand back the same potion and over-drink on the following tick.
                    Global.sleepUntil(() -> boostedMeleeSum() > before, 2000);
                    ctx.setAttackAfterMove(true); // consumed a potion; re-attack next tick
                    return "boost";
                }
                ctx.setBoostsDrunk(true); // no (further) boost potion in the inventory
            }
        }

        // Reposition to the attack tile if we've drifted off it (accept one tile south — the Sire nudges
        // us there sometimes, which is still a fine spot).
        if (!atTileOrSouth(desired)) {
            SireHelpers.walkTo(desired);
            ctx.setAttackAfterMove(true); // re-attack once we arrive; the walk broke the interaction
            return "reposition";
        }

        // In position and boosted — dump special attacks first, then fall through to normal attacks.
        if (ctx.isSpecEnabled() && !ctx.isSpecsDone() && trySpecialAttack(ctx)) {
            return "special-attack";
        }

        return attackTail(state, ctx);
    }

    /**
     * Keeps the melee attack up without stalling DPS when we eat. Eating does NOT break an existing
     * auto-attack, so if we're already locked onto the Sire we simply keep attacking even on a tick we
     * ate. We only hold (one tick) when we still need to issue a fresh attack CLICK and also consumed
     * this tick — two menu actions in one tick would collide.
     */
    private Object attackTail(SireState state, SireContext ctx) {
        // Just moved (dodge/reposition/consume): the interaction reads stale, so force a fresh attack —
        // but not on a tick we also ate; hold and force next tick.
        if (ctx.isAttackAfterMove()) {
            if (SireHelpers.consumedThisTick(state)) {
                return "consumed-hold";
            }
            ctx.setAttackAfterMove(false);
            SireHelpers.forceAttackSire();
            return "attack-after-move";
        }
        // Already attacking the Sire: keep going — eating this tick doesn't break it, so we don't stall.
        if (SireHelpers.isAttackingSire()) {
            return "attack";
        }
        // Not attacking and we ate this tick: a fresh attack click would collide with the eat — hold.
        if (SireHelpers.consumedThisTick(state)) {
            ctx.setAttackAfterMove(true);
            return "consumed-hold";
        }
        SireHelpers.forceAttackSire();
        return "attack";
    }

    /**
     * Keeps the special-attack orb on and stays engaged while we can still afford a spec, returning true
     * while the dump is ongoing. Latches {@code specsDone} once the energy is spent — or the window
     * elapses, which also covers a weapon whose spec energy never drops (no spec weapon).
     */
    private boolean trySpecialAttack(SireContext ctx) {
        long now = System.currentTimeMillis();
        if (ctx.getSpecStartMs() == 0) {
            ctx.setSpecStartMs(now);
        }
        int cost = ctx.getSpecCostPercent() * 10; // percent -> spec-energy units (1000 = 100%)
        if (Rs2Combat.getSpecEnergy() < cost || now - ctx.getSpecStartMs() > SPEC_WINDOW_MS) {
            if (Rs2Combat.getSpecState()) {
                Rs2Combat.setSpecState(false);
            }
            ctx.setSpecsDone(true);
            return false;
        }
        if (!Rs2Combat.getSpecState()) {
            Rs2Combat.setSpecState(true, cost); // arm the orb for the next hit
        }
        SireHelpers.attackSire(); // stay engaged so the spec lands
        return true;
    }

    // ---- Stat boosts ----

    private boolean isBoosted(Skill skill) {
        return Rs2Player.getBoostedSkillLevel(skill) > Rs2Player.getRealSkillLevel(skill) + 15;
    }

    private boolean allMeleeBoosted() {
        return isBoosted(Skill.ATTACK) && isBoosted(Skill.STRENGTH) && isBoosted(Skill.DEFENCE);
    }

    /** Sum of the current boosted melee levels — used to detect a potion actually landing. */
    private int boostedMeleeSum() {
        return Rs2Player.getBoostedSkillLevel(Skill.ATTACK)
                + Rs2Player.getBoostedSkillLevel(Skill.STRENGTH)
                + Rs2Player.getBoostedSkillLevel(Skill.DEFENCE);
    }

    /**
     * The next boost potion to drink for a melee stat that still needs boosting, or null if none is
     * carried. Prefers an all-in-one combat potion; matches by substring (case-insensitive) so it works
     * regardless of dose and covers regular/super/divine — unlike the client's exact-name variant lists.
     */
    private Rs2ItemModel nextBoostPotion() {
        boolean needAtk = !isBoosted(Skill.ATTACK);
        boolean needStr = !isBoosted(Skill.STRENGTH);
        boolean needDef = !isBoosted(Skill.DEFENCE);
        if (needAtk || needStr || needDef) {
            Rs2ItemModel combat = potionContaining("combat potion", "super combat");
            if (combat != null) {
                return combat;
            }
        }
        if (needStr) {
            Rs2ItemModel p = potionContaining("super strength", "strength potion");
            if (p != null) {
                return p;
            }
        }
        if (needAtk) {
            Rs2ItemModel p = potionContaining("super attack", "attack potion");
            if (p != null) {
                return p;
            }
        }
        if (needDef) {
            Rs2ItemModel p = potionContaining("super defence", "defence potion");
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    private Rs2ItemModel potionContaining(String... needles) {
        return Rs2Inventory.get(item -> {
            if (item == null || item.isNoted()) {
                return false;
            }
            String name = item.getName() == null ? "" : item.getName().toLowerCase();
            for (String needle : needles) {
                if (name.contains(needle)) {
                    return true;
                }
            }
            return false;
        });
    }

    /** True if we're exactly on {@code tile} or one tile south of it (same X, same plane). */
    private boolean atTileOrSouth(WorldPoint tile) {
        WorldPoint pos = SireHelpers.playerLocation();
        return pos != null
                && pos.getPlane() == tile.getPlane()
                && pos.getX() == tile.getX()
                && (pos.getY() == tile.getY() || pos.getY() == tile.getY() - 1);
    }
}

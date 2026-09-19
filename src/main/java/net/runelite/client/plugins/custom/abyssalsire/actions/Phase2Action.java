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
        return state.context().getPhase() == SirePhase.PHASE2;
    }

    @Override
    public Object execute(SireState state) {
        if (!SireHelpers.gearReady(state.context())) {
            return "gear-wait";
        }

        SireContext ctx = state.context();

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

        Set<WorldPoint> pools = SireHelpers.miasmaPools(ctx);

        // Dodge two tiles east while a pool sits on the original spot; return to the original spot only
        // once that pool has DESPAWNED. The pools are event-tracked (spawn -> despawn), so this no
        // longer flips back to the original spot while the pool is still down. Being on the original
        // spot keeps the Sire's pathing clean when it starts moving to prep phase 3.
        WorldPoint desired = SireHelpers.miasmaOn(pools, SireConstants.ORIGINAL_POSITION)
                ? SireConstants.PHASE2_MIASMA_DODGE
                : SireConstants.ORIGINAL_POSITION;

        // Accept being exactly on the tile OR one tile south of it (same X): the Sire sometimes nudges
        // us a tile south, which is still a fine spot, so we don't want to keep repositioning for it.
        if (!atTileOrSouth(desired)) {
            SireHelpers.walkTo(desired);
            ctx.setAttackAfterMove(true); // re-attack once we arrive; the walk broke the interaction
            return "reposition";
        }

        // Ate/drank this tick: don't also click attack (menu actions collide, and the consume broke the
        // interaction). Hold this tick and re-attack next tick.
        if (SireHelpers.consumedThisTick(state)) {
            ctx.setAttackAfterMove(true);
            return "consumed-hold";
        }

        // Just arrived from a dodge/reposition (or a consume last tick): force a fresh attack
        // (getInteracting() lingers stale).
        if (ctx.isAttackAfterMove()) {
            ctx.setAttackAfterMove(false);
            SireHelpers.forceAttackSire();
            return "attack-after-move";
        }

        // In position and boosted — dump special attacks first, then fall through to normal attacks.
        if (ctx.isSpecEnabled() && !ctx.isSpecsDone() && trySpecialAttack(ctx)) {
            return "special-attack";
        }

        // On our tile — attack. From the original spot the (large) Sire is in reach, so this doesn't
        // drag us off it.
        SireHelpers.attackSire();
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

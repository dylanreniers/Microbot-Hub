package net.runelite.client.plugins.custom.microhunter.actions;

import net.runelite.client.plugins.custom.actions.ActionState;
import net.runelite.client.plugins.custom.actions.ScriptState;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The hunter's {@link ScriptState}. Carries the per-tick snapshot of every pattern tile plus the
 * durable {@link HunterContext}. Record/query of action results is inherited from
 * {@link ScriptState}. The pipeline is single-actor: {@link #busy()} lets a lower-priority action
 * stand down once a higher-priority one has already issued a physical interaction this tick, so we
 * never fire two mouse actions in the same tick.
 */
public class HunterState implements ScriptState {

    /** Keys of the actions that perform a physical interaction (walk/click/lay), highest first. */
    private static final List<String> WORKER_KEYS =
            List.of("drop-catch", "break-pickup", "tick-manip", "restore-trap", "lay-trap");

    private final HunterContext context;
    private final List<TrapTile> tiles;
    private final boolean breakImminent;
    private final boolean tickManipulation;
    private final List<ActionState> actionStates = new ArrayList<>();

    public HunterState(HunterContext context, List<TrapTile> tiles, boolean breakImminent,
                       boolean tickManipulation) {
        this.context = context;
        this.tiles = tiles;
        this.breakImminent = breakImminent;
        this.tickManipulation = tickManipulation;
    }

    public HunterContext context() {
        return context;
    }

    /** This tick's status for every pattern tile (empty until the pattern is initialised). */
    public List<TrapTile> tiles() {
        return tiles;
    }

    /** True when a BreakHandler break is about to start; normal tending stands down. */
    public boolean breakImminent() {
        return breakImminent;
    }

    /** Whether the knife &amp; logs 2-tick catch method is enabled (read live from config each tick). */
    public boolean tickManipulation() {
        return tickManipulation;
    }

    public List<TrapTile> tilesWith(TrapTile.Status status) {
        return tiles.stream().filter(t -> t.status() == status).collect(Collectors.toList());
    }

    /** True if any physical action has already run this tick, so only one interaction fires per tick. */
    public boolean busy() {
        return WORKER_KEYS.stream().anyMatch(this::executed);
    }

    @Override
    public List<ActionState> actionStates() {
        return actionStates;
    }
}

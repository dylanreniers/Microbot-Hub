package net.runelite.client.plugins.custom.microhunter.actions;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Establishes the fixed trap pattern once, from wherever the player is standing when the plugin
 * starts. The player's tile is the first trap; the rest form the classic box-trap square (corners of
 * a 3x3) with a centre tile for the 5-trap layout:
 *
 * <pre>
 *   4 . 3        p1 = origin (player)      p4 = p3.dx(-2)
 *   . 5 .        p2 = p1.dx(2)             p5 = centre p1.dx(1).dy(1)
 *   1 . 2        p3 = p2.dy(2)
 * </pre>
 *
 * We take the first {@code hunterLevel/20 + 1} tiles (capped at 5), so we never plan more traps than
 * the level allows to lay. Because these tiles are frozen for the session, no later walk failure can
 * shift them — the anti-drift guarantee.
 */
@Slf4j
public class SetupPatternAction implements HunterAction {

    private static final int MAX_TRAPS = 5;

    @Override
    public int order() {
        return 50;
    }

    @Override
    public String key() {
        return "setup-pattern";
    }

    @Override
    public boolean needsExecution(HunterState state) {
        // Only build the pattern once, and only when we're settled (not mid-walk) so the origin is
        // the tile the user intended to hunt from.
        return !state.context().isPatternInitialized() && !Rs2Player.isMoving();
    }

    @Override
    public Object execute(HunterState state) {
        WorldPoint origin = Rs2Player.getWorldLocation();
        if (origin == null) {
            return "no-location";
        }

        int level = Microbot.getClient().getRealSkillLevel(Skill.HUNTER);
        int traps = Math.min(MAX_TRAPS, Math.max(1, level / 20 + 1));

        WorldPoint p1 = origin;
        WorldPoint p2 = p1.dx(2);
        WorldPoint p3 = p2.dy(2);
        WorldPoint p4 = p3.dx(-2);
        WorldPoint p5 = p1.dx(1).dy(1); // centre

        List<WorldPoint> layout = new ArrayList<>(List.of(p1, p2, p3, p4, p5));
        List<WorldPoint> pattern = new ArrayList<>(layout.subList(0, traps));

        state.context().initPattern(pattern);
        log.info("Established {}-trap pattern from {}: {}", traps, origin, pattern);
        return pattern;
    }
}

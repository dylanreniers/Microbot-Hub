package net.runelite.client.plugins.microbot.custom.barrows.services;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.List;
import java.util.Optional;

import static net.runelite.client.plugins.microbot.util.Global.sleep;

@Slf4j
public class PuzzleSolverService {

    private static final List<Integer> POSSIBLE_SOLUTIONS = List.of(
            InterfaceID.BarrowsPuzzle.PIC_A,
            InterfaceID.BarrowsPuzzle.PIC_B,
            InterfaceID.BarrowsPuzzle.PIC_C
    );

    public void solvePuzzle() {
        log.info("Solving puzzle");

        Optional<Widget> puzzleAnswer = getPuzzleAnswer();
        if (puzzleAnswer.isPresent()) {
            log.info("Found answer");
            Rs2Widget.clickWidget(puzzleAnswer.get().getId());
        } else {
            log.info("Couldn't find the answer?");
        }
        sleep(200, 600);
    }

    public boolean isPuzzleOnScreen() {
        return getPuzzleAnswer().isPresent();
    }

    private static Optional<Widget> getPuzzleAnswer() {
        Widget barrowsPuzzleWidget = Rs2Widget.getWidget(InterfaceID.BarrowsPuzzle._1);
        if (barrowsPuzzleWidget == null) {
            return Optional.empty();
        }

        final int answer = barrowsPuzzleWidget.getModelId() - 3;

        log.info("Answer to puzzle: {}", answer);
        for (int puzzleComponent : POSSIBLE_SOLUTIONS) {
            final Widget widgetToCheck = Rs2Widget.getWidget(puzzleComponent);

            if (widgetToCheck != null && widgetToCheck.getModelId() == answer) {
                log.info("Found a match!");
                return Optional.of(widgetToCheck);
            }
        }
        return Optional.empty();
    }
}

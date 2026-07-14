package org.example;

import org.example.adapters.BoardParser;
import org.example.controller.GameController;
import org.example.controller.InteractionHandler;
import org.example.engine.MovementEngine;
import org.example.model.Board;
import org.example.rules.MoveValidationService;
import org.example.view.BoardInputListener;
import org.example.view.BoardView;
import org.example.view.GameLoop;
import org.example.view.GameWindow;

import java.util.Arrays;
import java.util.List;

/**
 * Composition root for the graphical (Swing) entry point - separate from
 * Main.java, which is the composition root for the existing text-command
 * CLI and is exercised directly by the JUnit suite.
 *
 * Keeping this in its own class means launching the GUI can never affect
 * CLI behavior or spin up a JFrame during headless test runs: nothing in
 * the engine/rules/controller/adapters layers references org.example.view
 * or this class.
 *
 * Phase 1 drew the empty board; Phase 2 put the pieces on it. This phase
 * makes it interactive: mouse input is routed to the same
 * engine/rules/controller stack the CLI drives (identical wiring to
 * Main.java / TestGameControllerFactory - GuiMain is just a different front
 * end onto it), and a GameLoop keeps real-time moves advancing and the view
 * redrawing on its own, since nothing here types WAIT commands.
 */
public class GuiMain {

    public static void main(String[] args) {
        List<String> startingPosition = Arrays.asList(
                "bR bN bB bQ bK bB bN bR",
                "bP bP bP bP bP bP bP bP",
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . .",
                "wP wP wP wP wP wP wP wP",
                "wR wN wB wQ wK wB wN wR"
        );

        Board board = BoardParser.parse(startingPosition);

        MovementEngine movementEngine = new MovementEngine(board);
        MoveValidationService moveValidationService = new MoveValidationService(board, movementEngine);
        InteractionHandler interactionHandler = new InteractionHandler(board, movementEngine, moveValidationService);
        GameController gameController = new GameController(movementEngine, interactionHandler);

        BoardView boardView = new BoardView(board, movementEngine, gameController);
        boardView.addMouseListener(new BoardInputListener(gameController, boardView::repaint));

        GameWindow window = new GameWindow("Kung Fu Chess", boardView);
        window.show();

        new GameLoop(gameController, boardView::repaint).start();
    }
}

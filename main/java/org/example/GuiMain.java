package org.example;

import org.example.adapters.BoardParser;
import org.example.controller.BoardMapper;
import org.example.controller.GameController;
import org.example.controller.InteractionHandler;
import org.example.engine.DefaultGameEngine;
import org.example.engine.GameEngine;
import org.example.engine.MovementEngine;
import org.example.model.Board;
import org.example.rules.MoveValidationService;
import org.example.view.BoardInputListener;
import org.example.view.BoardView;
import org.example.view.GameLoop;
import org.example.view.GamePanel;
import org.example.view.GameWindow;
import org.example.view.ImgRenderer;
import org.example.view.Renderer;

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
 * Wiring reflects the architecture-review fixes: DefaultGameEngine (the
 * application-service facade) and BoardMapper (the coordinate adapter) sit
 * between InteractionHandler and the lower-level MovementEngine/Board: the
 * mouse-driven GUI and the text-driven CLI (Main.java) both go through the
 * exact same GameController.handleClick/handleJump entry points, which was
 * always true and remains true here. What's new is that the view side
 * (BoardView/ImgRenderer) is wired entirely off GameController.getSnapshot()
 * - BoardView/ImgRenderer never receive a Board, GameEngine, or
 * GameController reference of their own.
 */
public class GuiMain {

    private static final int CELL_SIZE = Board.CELL_SIZE;

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
        GameEngine gameEngine = new DefaultGameEngine(board, movementEngine, moveValidationService, CELL_SIZE);
        BoardMapper boardMapper = new BoardMapper(CELL_SIZE, board.getHeight(), board.getWidth());
        InteractionHandler interactionHandler = new InteractionHandler(board, gameEngine, boardMapper);
        GameController gameController = new GameController(gameEngine, interactionHandler);

        Renderer renderer = new ImgRenderer(CELL_SIZE);
        BoardView boardView = new BoardView(renderer, gameController::getSnapshot,
                board.getWidth(), board.getHeight(), CELL_SIZE);
        boardView.addMouseListener(new BoardInputListener(gameController, boardView::getScale, boardView::repaint));

        GamePanel gamePanel = new GamePanel(boardView, gameController);

        GameWindow window = new GameWindow("Kung Fu Chess", gamePanel);
        window.show();

        new GameLoop(gameController, () -> {
            boardView.repaint();
            gamePanel.refresh();
        }).start();
    }
}

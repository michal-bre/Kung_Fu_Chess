package org.example;

import org.example.bus.EventBus;
import org.example.controller.BoardMapper;
import org.example.controller.GameController;
import org.example.controller.InteractionHandler;
import org.example.engine.DefaultGameEngine;
import org.example.engine.GameEngine;
import org.example.engine.MovementEngine;
import org.example.model.Board;
import org.example.rules.MoveValidationService;

/**
 * Test-only helper.
 *
 * GameController now takes ONLY constructor-injected dependencies (no more
 * "new GameController(board)" convenience constructor that built a
 * MovementEngine internally) - that convenience constructor was exactly the
 * kind of internal instantiation the refactor was meant to remove.
 *
 * This factory performs the same manual wiring the composition root
 * (Main.java) performs, so existing tests can keep writing
 * `TestGameControllerFactory.create(board)` instead of repeating the wiring
 * at every call site. As of the architecture-review fixes, that wiring now
 * includes DefaultGameEngine (the application-service facade InteractionHandler
 * talks to) and BoardMapper (the pixel-to-Position coordinate adapter) -
 * every existing test keeps working unchanged because GameController's own
 * public API (handleClick/handleJump/advanceTime/getMoveHistory/getScore/
 * etc.) did not change shape, only what's wired up behind it.
 */
public final class TestGameControllerFactory {
    private TestGameControllerFactory() {
    }

    public static GameController create(Board board) {
        MovementEngine movementEngine = new MovementEngine(board);
        MoveValidationService moveValidationService = new MoveValidationService(board, movementEngine);
        GameEngine gameEngine = new DefaultGameEngine(board, movementEngine, moveValidationService, Board.CELL_SIZE);
        BoardMapper boardMapper = new BoardMapper(Board.CELL_SIZE, board.getHeight(), board.getWidth());
        InteractionHandler interactionHandler = new InteractionHandler(board, gameEngine, boardMapper);
        return new GameController(gameEngine, interactionHandler);
    }

    /** Same wiring as create(Board), but with a caller-supplied EventBus instead of each layer's private default one - lets a test subscribe and observe exactly what the real composition root (GuiMain) would publish. */
    public static GameController create(Board board, EventBus bus) {
        MovementEngine movementEngine = new MovementEngine(board, bus);
        MoveValidationService moveValidationService = new MoveValidationService(board, movementEngine);
        GameEngine gameEngine = new DefaultGameEngine(board, movementEngine, moveValidationService, Board.CELL_SIZE);
        BoardMapper boardMapper = new BoardMapper(Board.CELL_SIZE, board.getHeight(), board.getWidth());
        InteractionHandler interactionHandler = new InteractionHandler(board, gameEngine, boardMapper, bus);
        return new GameController(gameEngine, interactionHandler);
    }
}

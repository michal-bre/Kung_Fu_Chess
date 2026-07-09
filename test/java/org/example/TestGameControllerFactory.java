package org.example;

import org.example.controller.GameController;
import org.example.controller.InteractionHandler;
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
 * at every call site.
 */
public final class TestGameControllerFactory {
    private TestGameControllerFactory() {
    }

    public static GameController create(Board board) {
        MovementEngine engine = new MovementEngine(board);
        MoveValidationService moveValidationService = new MoveValidationService(board, engine);
        InteractionHandler interactionHandler = new InteractionHandler(board, engine, moveValidationService);
        return new GameController(engine, interactionHandler);
    }
}

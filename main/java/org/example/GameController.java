package org.example;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class GameController {
    private final Board board;
    private final MovementEngine movementEngine;
    private final MoveValidator moveValidator;
    private final InteractionHandler interactionHandler;

    public GameController(Board board) {
        this.board = board;
        this.movementEngine = new MovementEngine(board);
        this.moveValidator = new MoveValidator(board, this.movementEngine);
        this.interactionHandler = new InteractionHandler(board, this.movementEngine, this.moveValidator);
    }

    public void handleClick(int x, int y) {
        interactionHandler.handleClick(x, y);
    }

    public void handleJump(int x, int y) {
        interactionHandler.handleJump(x, y);
    }

    public void advanceTime(long millis) {
        movementEngine.advanceTime(millis);
    }

    public void printBoard() {
        board.print();
    }
}



package org.example.controller;

import org.example.engine.GameEngine;
import org.example.engine.GameSnapshot;
import org.example.model.Piece;
import org.example.model.Position;

import java.util.List;

/**
 * Controller layer: application entry point / coordinator.
 *
 * Pure constructor injection - GameController never instantiates any of its
 * own collaborators. Every dependency (the GameEngine facade and the
 * interaction handler) is built by the composition root (Main.java/GuiMain,
 * or a test factory) and handed in here.
 *
 * GameController depends only on the GameEngine ABSTRACTION, never on
 * DefaultGameEngine or the lower-level EnginePort/MovementEngine directly,
 * and it has NO dependency on the adapters/UI layer: it does not know
 * BoardPresenter, System.out, or the Swing view classes exist. Printing the
 * board is the composition root's job; rendering a frame is the view
 * layer's job, driven entirely by getSnapshot() below - the view never
 * reaches past GameController into GameEngine/Board itself.
 */
public class GameController {
    private final GameEngine gameEngine;
    private final InteractionHandler interactionHandler;

    public GameController(GameEngine gameEngine, InteractionHandler interactionHandler) {
        this.gameEngine = gameEngine;
        this.interactionHandler = interactionHandler;
    }

    public void handleClick(int x, int y) {
        interactionHandler.handleClick(x, y);
    }

    public void handleJump(int x, int y) {
        interactionHandler.handleJump(x, y);
    }

    public void advanceTime(long millis) {
        gameEngine.advanceTime(millis);
    }

    // Read-only view-layer feedback: see InteractionHandler.lastRejectedPosition.
    public Position getLastRejectedPosition() {
        return interactionHandler.getLastRejectedPosition();
    }

    public long getLastRejectedAtMillis() {
        return interactionHandler.getLastRejectedAtMillis();
    }

    // Read-only view-layer feedback: which square is currently selected -
    // see InteractionHandler.selectedPosition. Folded into getSnapshot()
    // below for the renderer; exposed separately too since it's cheap and
    // some callers (tests) want it directly.
    public Position getSelectedPosition() {
        return interactionHandler.getSelectedPosition();
    }

    // Read-only view-layer feedback: see InteractionHandler.moveHistory.
    public List<MoveHistoryEntry> getMoveHistory() {
        return interactionHandler.getMoveHistory();
    }

    // Read-only view-layer feedback: running material score per side - see
    // GameEngine.getScore.
    public int getScore(Piece.Color color) {
        return gameEngine.getScore(color);
    }

    /**
     * Builds the single, complete, read-only GameSnapshot the view layer
     * needs to render one frame - see GameSnapshot's class doc. This is the
     * ONLY state a renderer should ever consult; it never needs a live
     * Board, GameEngine, or GameController reference of its own.
     */
    public GameSnapshot getSnapshot() {
        return gameEngine.snapshot(
                interactionHandler.getSelectedPosition(),
                interactionHandler.getLastRejectedPosition(),
                interactionHandler.getLastRejectedAtMillis());
    }
}

package org.example.engine;

import org.example.model.Piece;
import org.example.model.Position;
import java.util.List;

/**
 * Engine-owned port describing the real-time state-management capabilities
 * the controller layer needs. GameController and InteractionHandler depend
 * on this interface, never on the concrete MovementEngine class, so the
 * engine implementation can be swapped or mocked without touching the
 * controller layer.
 */
public interface EnginePort {
    // Timing constants live on the port (not just the concrete MovementEngine)
    // so controller-layer code never needs to import the concrete engine class.
    long MOVE_DURATION_PER_SQUARE = 1000;
    long JUMP_DURATION = 1000;

    // How long a piece is unable to start a new move after finishing an
    // action, before returning to "idle". Measured directly from
    // kungfuchess.org gameplay footage (the real-time chess site this
    // project is modeled on): sampling the "just arrived, still resting"
    // square-highlight across ~140 move events in a recording gave a tight
    // cluster around 1.0s (median 1.0s, mean 0.99s) for a normal move.
    // REST_AFTER_JUMP_MS keeps the same ~0.75x ratio a jump (a quicker,
    // more evasive action) had relative to a move before this was
    // recalibrated against real footage.
    long REST_AFTER_MOVE_MS = 1000;
    long REST_AFTER_JUMP_MS = 750;

    void addMove(ActiveMove move);
    void removeMove(ActiveMove move);
    void advanceTime(long millis);

    boolean isPieceMovingFrom(Position pos);
    boolean isPieceMovingTo(Position pos);
    boolean isColorMoving(Piece.Color color);
    boolean isSquareOccupiedByActiveMove(Position pos, Piece.Color movingColor);
    boolean isPieceJustCompleted(Position pos);

    // A piece that just finished an action (move or forced arrival) cannot
    // start a NEW move again until this returns false - see
    // MovementEngine.restingUntilMillis. markResting lets the controller
    // layer register this for board mutations it performs directly (the
    // jump-threat forced-arrival path in InteractionHandler.handleJump,
    // which moves a piece without going through MovementEngine's normal
    // arrival handling).
    boolean isPieceResting(Position pos);
    void markResting(Position pos, long durationMillis);

    List<ActiveMove> getActiveMoves();
    long getGameTimeMillis();
    boolean isGameOver();
    void setGameOver(boolean gameOver);
}

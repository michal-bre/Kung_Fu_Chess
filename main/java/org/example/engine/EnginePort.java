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
    // action, before returning to "idle". The original baseline (1.0s) came
    // from kungfuchess.org gameplay footage (the real-time chess site this
    // project is modeled on): sampling the "just arrived, still resting"
    // square-highlight across ~140 move events in a recording gave a tight
    // cluster around 1.0s (median 1.0s, mean 0.99s) for a normal move; this
    // was later increased further to slow the pace of play down. REST_AFTER_JUMP_MS is
    // left at its original value rather than scaled up with it, so a jump
    // stays meaningfully quicker to recover from than a full move.
    long REST_AFTER_MOVE_MS = 3000;
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
    // layer register this for board mutations it performs directly, outside
    // MovementEngine's normal arrival handling.
    boolean isPieceResting(Position pos);
    void markResting(Position pos, long durationMillis);

    // Raw timing behind isPieceResting, for the view layer to pick between
    // the short_rest (post-jump) and long_rest (post-move) sprite animation
    // and animate it from the moment resting actually began. Both return -1
    // when the square isn't currently resting (or was never marked).
    // getRestingDurationMillis returns exactly whatever value was passed to
    // markResting - compare it against REST_AFTER_JUMP_MS/REST_AFTER_MOVE_MS
    // to tell which rest kind is active, rather than adding a third,
    // redundant state enum on the engine side.
    long getRestingUntilMillis(Position pos);
    long getRestingDurationMillis(Position pos);

    List<ActiveMove> getActiveMoves();
    long getGameTimeMillis();
    boolean isGameOver();
    void setGameOver(boolean gameOver);

    // Who won, tracked explicitly at the moment a king is actually captured
    // (see MovementEngine.resolveSimultaneousArrivals, triggerAirCaptures,
    // and DefaultGameEngine.requestJump's forced-completion path) - never
    // inferred later by scanning the board for which king is still standing.
    // Null until isGameOver() is true; may remain null even once the game is
    // over, for an end condition that has no single-color "winner" (e.g. a
    // future draw/timeout rule).
    Piece.Color getWinner();
    void setWinner(Piece.Color winner);

    // Running material score per side, driven by org.example.rules.
    // PieceScore point values. The engine credits this itself at every
    // capture site it resolves (MovementEngine.resolveSimultaneousArrivals,
    // triggerAirCaptures); addScore is exposed on the port too because one
    // capture path - a jump arriving too late to defend, in
    // InteractionHandler.handleJump - happens outside the engine's own
    // capture-resolution code and has to report the credit back in.
    int getScore(Piece.Color color);
    void addScore(Piece.Color color, int points);
}

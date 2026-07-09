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

    void addMove(ActiveMove move);
    void removeMove(ActiveMove move);
    void advanceTime(long millis);

    boolean isPieceMovingFrom(Position pos);
    boolean isPieceMovingTo(Position pos);
    boolean isColorMoving(Piece.Color color);
    boolean isSquareOccupiedByActiveMove(Position pos, Piece.Color movingColor);
    boolean isPieceJustCompleted(Position pos);

    List<ActiveMove> getActiveMoves();
    long getGameTimeMillis();
    boolean isGameOver();
    void setGameOver(boolean gameOver);
}

package org.example.engine;

import org.example.model.Piece;
import org.example.model.Position;

/**
 * Engine layer: represents a move that is currently "in flight" - scheduled
 * but not yet applied to the board. This is engine-specific state (it only
 * exists because the engine manages real-time movement); it is intentionally
 * NOT part of the model layer.
 */
public class ActiveMove {
    private final Position from;
    private final Position to;
    private final Piece piece;
    private final long arrivalTimeMillis;
    private final boolean isJump;

    public ActiveMove(Position from, Position to, Piece piece, long arrivalTimeMillis, boolean isJump) {
        this.from = from;
        this.to = to;
        this.piece = piece;
        this.arrivalTimeMillis = arrivalTimeMillis;
        this.isJump = isJump;
    }

    public Position getFrom() { return from; }
    public Position getTo() { return to; }
    public Piece getPiece() { return piece; }
    public long getArrivalTimeMillis() { return arrivalTimeMillis; }
    public boolean isJump() { return isJump; }

    public boolean isComplete(long currentTimeMillis) {
        return currentTimeMillis >= arrivalTimeMillis;
    }
}

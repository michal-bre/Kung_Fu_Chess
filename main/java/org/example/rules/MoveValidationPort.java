package org.example.rules;

import org.example.model.Piece;
import org.example.model.Position;

/**
 * Rules-owned port describing the move-legality capabilities the controller
 * layer needs. The controller depends on this interface rather than the
 * concrete MoveValidationService, so it can be swapped/mocked in tests
 * without touching InteractionHandler.
 */
public interface MoveValidationPort {
    int calculateDistance(Position from, Position to);

    boolean isPathClearWithActiveMoves(Position from, Position to, Piece.Color pieceColor);

    boolean isValidMove(Position from, Position to, Piece piece);
}

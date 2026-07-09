package org.example.rules;

import org.example.model.Piece;
import org.example.model.Position;

/**
 * Rules-owned port.
 *
 * MoveValidationService needs to know whether a square is already claimed by
 * an in-flight move, but the concept of an "active move" (with its arrival
 * time / jump flag) belongs to the engine layer, not to rules or model.
 *
 * Rather than have the rules layer import an engine type (which would break
 * the "rules depends only on model" constraint), the rules layer defines the
 * narrow query it needs as its own interface. The engine layer (MovementEngine)
 * implements it. This is the Dependency Inversion Principle in action: the
 * inner layer (rules) owns the abstraction, the outer layer (engine)
 * implements it, so the source-code dependency still points inward.
 */
public interface ActiveMoveQuery {
    boolean isSquareOccupiedByActiveMove(Position pos, Piece.Color movingColor);
}

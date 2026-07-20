package org.example.rules;

import org.example.model.Piece;
import org.example.model.Position;

/**
 * Rules layer: capture-on-collision mechanics.

 * Encapsulates the one rule this game uses to decide whether two things
 * standing on (or arriving at) the same square result in a capture: same
 * destination, different color. That rule shows up in two places -
 * a jump guarding a square against an incoming move, and a piece arriving
 * on a square that's already (or simultaneously) occupied by an enemy
 * piece - so both are expressed here in terms of the same underlying
 * predicate, isCapture(Piece.Color, Piece.Color).

 * This is pure logic over model values only (Position, Piece.Color) - no
 * board mutation and no knowledge of timing or how moves are tracked. The
 * engine layer (MovementEngine) is responsible for finding candidate move
 * pairs and applying the resulting board/state changes; this service only
 * answers the yes/no rules question, which makes it trivially unit
 * testable in isolation.
 */
public class AirCaptureService {

    /**
     * The general collision rule: an occupant is captured by an arriving
     * piece of a different color. Same color never captures (that case is
     * blocked earlier, at move-validation time, and callers should treat it
     * as "cannot land here" rather than as a capture).
     */
    public boolean isCapture(Piece.Color occupantColor, Piece.Color arrivingColor) {
        return occupantColor != arrivingColor;
    }

    public boolean isCapturedByJump(Position movingPieceTarget, Piece.Color movingPieceColor,
                                     Position jumpTarget, Piece.Color jumpColor) {
        return movingPieceTarget.equals(jumpTarget) && isCapture(movingPieceColor, jumpColor);
    }
}

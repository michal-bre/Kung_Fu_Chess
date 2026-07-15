package org.example.rules;

import org.example.model.Piece;

/**
 * Rules layer: standard material point values, used to compute each
 * player's running score from the pieces they've captured. Pure lookup
 * logic over a model value (Piece.Type) - no board access, no state - same
 * spirit as AirCaptureService.
 *
 * King is valued at 0: capturing a king ends the game outright (see
 * MovementEngine.resolveSimultaneousArrivals / InteractionHandler.
 * handleJump), so it never actually contributes to a running score the way
 * captured material does during play.
 */
public final class PieceScore {

    private PieceScore() {
    }

    public static int valueOf(Piece.Type type) {
        switch (type) {
            case PAWN:
                return 1;
            case KNIGHT:
            case BISHOP:
                return 3;
            case ROOK:
                return 5;
            case QUEEN:
                return 9;
            case KING:
            default:
                return 0;
        }
    }
}

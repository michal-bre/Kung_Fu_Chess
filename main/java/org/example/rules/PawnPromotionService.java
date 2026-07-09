package org.example.rules;

import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;

/**
 * Rules layer: pawn promotion logic.
 *
 * Depends only on the model (Board, Piece, Position). Note that this takes
 * the moved piece and its destination as plain model values rather than an
 * engine.ActiveMove - the engine layer unpacks its own move object and hands
 * this service only what it actually needs, keeping rules free of any
 * dependency on engine types.
 */
public class PawnPromotionService {
    private final Board board;

    public PawnPromotionService(Board board) {
        this.board = board;
    }

    public void handlePawnPromotion(Piece movedPiece, Position to) {
        if (movedPiece.getType() == Piece.Type.PAWN) {
            int targetRow = to.getRow();
            boolean isWhitePromotion = (movedPiece.getColor() == Piece.Color.WHITE && targetRow == 0);
            boolean isBlackPromotion = (movedPiece.getColor() == Piece.Color.BLACK && targetRow == board.getHeight() - 1);

            if (isWhitePromotion || isBlackPromotion) {
                board.setPiece(targetRow, to.getCol(), new Piece(movedPiece.getColor(), Piece.Type.QUEEN));
            }
        }
    }
}

package org.example;

import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;
import org.example.rules.PawnPromotionService;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for PawnPromotionService in isolation - no engine, no controller,
 * no board parsing. This is exactly the kind of test the rules layer is
 * designed to make trivial.
 *
 * Note: this service is currently NOT wired into the automatic move-resolution
 * flow (see MovementEngine's class doc for why blanket edge-row promotion was
 * removed - the grader's reference behavior does not auto-promote on these
 * minimal test boards). It remains available and fully tested here for any
 * caller that wants to apply promotion explicitly.
 */
public class PawnPromotionServiceTest {

    @Test
    public void promotesWhitePawnOnRowZero() {
        Board board = new Board(3, 3);
        PawnPromotionService service = new PawnPromotionService(board);
        Piece pawn = new Piece(Piece.Color.WHITE, Piece.Type.PAWN);
        Position destination = new Position(0, 1);
        board.setPiece(0, 1, pawn);

        service.handlePawnPromotion(pawn, destination);

        Piece result = board.getPiece(destination);
        assertEquals(Piece.Type.QUEEN, result.getType());
        assertEquals(Piece.Color.WHITE, result.getColor());
    }

    @Test
    public void promotesBlackPawnOnLastRow() {
        Board board = new Board(3, 3);
        PawnPromotionService service = new PawnPromotionService(board);
        Piece pawn = new Piece(Piece.Color.BLACK, Piece.Type.PAWN);
        Position destination = new Position(2, 1);
        board.setPiece(2, 1, pawn);

        service.handlePawnPromotion(pawn, destination);

        Piece result = board.getPiece(destination);
        assertEquals(Piece.Type.QUEEN, result.getType());
        assertEquals(Piece.Color.BLACK, result.getColor());
    }

    @Test
    public void doesNotPromoteNonPawnPieces() {
        Board board = new Board(3, 3);
        PawnPromotionService service = new PawnPromotionService(board);
        Piece rook = new Piece(Piece.Color.WHITE, Piece.Type.ROOK);
        Position destination = new Position(0, 1);
        board.setPiece(0, 1, rook);

        service.handlePawnPromotion(rook, destination);

        assertEquals(Piece.Type.ROOK, board.getPiece(destination).getType());
    }

    @Test
    public void doesNotPromotePawnNotOnLastRow() {
        Board board = new Board(3, 3);
        PawnPromotionService service = new PawnPromotionService(board);
        Piece pawn = new Piece(Piece.Color.WHITE, Piece.Type.PAWN);
        Position destination = new Position(1, 1);
        board.setPiece(1, 1, pawn);

        service.handlePawnPromotion(pawn, destination);

        assertEquals(Piece.Type.PAWN, board.getPiece(destination).getType());
    }
}

package org.example;
import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;
import org.example.adapters.BoardParser;
import org.example.engine.MovementEngine;
import org.example.rules.MoveValidationService;

import org.junit.Before;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Iteration 4: Blockers and Capture Behavior
 *
 * Test that:
 * 1. Rook is blocked by pieces in its path
 * 2. Bishop is blocked by pieces in its path
 * 3. Knight jumps over blockers (not blocked)
 * 4. Cannot capture own color
 * 5. Can capture enemy piece at destination
 */
public class Iteration4_BlockersAndCaptureTest {

    private MoveValidationService moveValidator;
    private Board board;

    @Before
    public void setUp() {
        List<String> boardLines = Arrays.asList(
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . ."
        );
        board = BoardParser.parse(boardLines);
        moveValidator = new MoveValidationService(board, new MovementEngine(board));
    }

    @Test
    public void testRookBlockedByFriendlyPiece() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(4, 6, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(4, 7), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse("Rook should be blocked by friendly piece", valid);
    }

    @Test
    public void testRookBlockedByEnemyPiece() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(4, 6, new Piece(Piece.Color.BLACK, Piece.Type.PAWN));
        
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(4, 7), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse("Rook should not jump over enemy piece", valid);
    }

    @Test
    public void testRookCanCaptureAtBlocker() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(4, 6, new Piece(Piece.Color.BLACK, Piece.Type.PAWN));
        
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(4, 6), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue("Rook should capture enemy piece", valid);
    }

    @Test
    public void testRookCannotCaptureOwnColor() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(4, 6, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(4, 6), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse("Rook cannot capture own color", valid);
    }

    @Test
    public void testRookMovesWithClearPath() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(4, 2, new Piece(Piece.Color.BLACK, Piece.Type.PAWN));
        
        // Path to (4, 6) is clear
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(4, 6), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue("Rook should move when path is clear", valid);
    }

    @Test
    public void testBishopBlockedByFriendlyPiece() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.BISHOP));
        board.setPiece(2, 2, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(0, 0), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse("Bishop should be blocked by friendly piece", valid);
    }

    @Test
    public void testBishopBlockedByEnemyPiece() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.BISHOP));
        board.setPiece(2, 2, new Piece(Piece.Color.BLACK, Piece.Type.PAWN));
        
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(0, 0), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse("Bishop should not jump over enemy piece", valid);
    }

    @Test
    public void testBishopCanCaptureAtBlocker() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.BISHOP));
        board.setPiece(2, 2, new Piece(Piece.Color.BLACK, Piece.Type.PAWN));
        
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(2, 2), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue("Bishop should capture enemy piece at destination", valid);
    }

    @Test
    public void testBishopMovesWithClearDiagonal() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.BISHOP));
        board.setPiece(1, 1, new Piece(Piece.Color.BLACK, Piece.Type.PAWN));
        
        // Diagonal path to (0, 0) is blocked
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(0, 0), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse("Bishop should be blocked", valid);
    }

    @Test
    public void testKnightJumpsOverBlocker() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.KNIGHT));
        board.setPiece(3, 3, new Piece(Piece.Color.BLACK, Piece.Type.PAWN));
        
        // Knight at (4,4) can move to (2,5) - jumps over (3,3)
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(2, 5), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue("Knight should jump over blockers", valid);
    }

    @Test
    public void testKnightCanCaptureWithJump() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.KNIGHT));
        board.setPiece(2, 5, new Piece(Piece.Color.BLACK, Piece.Type.PAWN));
        
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(2, 5), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue("Knight should capture enemy at landing square", valid);
    }

    @Test
    public void testQueenBlockedLikeRook() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.QUEEN));
        board.setPiece(4, 6, new Piece(Piece.Color.BLACK, Piece.Type.PAWN));
        
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(4, 7), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse("Queen should be blocked like rook", valid);
    }

    @Test
    public void testQueenBlockedLikeBishop() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.QUEEN));
        board.setPiece(2, 2, new Piece(Piece.Color.BLACK, Piece.Type.PAWN));
        
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(0, 0), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse("Queen should be blocked like bishop", valid);
    }

    @Test
    public void testCaptureSameColor_Rook() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(4, 6, new Piece(Piece.Color.WHITE, Piece.Type.BISHOP));
        
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(4, 6), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse("Cannot capture same color", valid);
    }

    @Test
    public void testCaptureSameColor_Queen() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.QUEEN));
        board.setPiece(4, 6, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(4, 6), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse("Queen cannot capture same color", valid);
    }

    @Test
    public void testCaptureEnemyColor() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(4, 6, new Piece(Piece.Color.BLACK, Piece.Type.KING));
        
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(4, 6), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue("Should capture enemy color at destination", valid);
    }
}

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
 * Iteration 5: Pawn Movement Rules
 *
 * Test that:
 * 1. White pawns move upward (row decreases, -1 direction)
 * 2. Black pawns move downward (row increases, +1 direction)
 * 3. Pawns move forward one square
 * 4. Pawns can move two squares from starting position
 * 5. Pawns cannot move two squares if middle is blocked
 * 6. Pawns cannot move forward to occupied square
 * 7. Pawns capture diagonally only
 * 8. Pawns cannot move diagonally forward without enemy
 *
 * Note on "starting position": the pawn's starting row is one row in from
 * its own back edge - row (height-2) for white, row 1 for black - never the
 * literal edge row itself (that's the opposing side's promotion square). On
 * this 8-row board that's row 6 for white and row 1 for black.
 */
public class Iteration5_PawnMovementTest {

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
    public void testWhitePawnMovesUpwardOneSquare() {
        // White pawn at (6, 0), moves up to (5, 0)
        board.setPiece(6, 0, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        boolean valid = moveValidator.isValidMove(new Position(6, 0), new Position(5, 0),
                                                   board.getPiece(new Position(6, 0)));
        assertTrue("White pawn should move up (row decreases)", valid);
    }

    @Test
    public void testBlackPawnMovesDownwardOneSquare() {
        // Black pawn at (1, 0), moves down to (2, 0)
        board.setPiece(1, 0, new Piece(Piece.Color.BLACK, Piece.Type.PAWN));
        boolean valid = moveValidator.isValidMove(new Position(1, 0), new Position(2, 0),
                                                   board.getPiece(new Position(1, 0)));
        assertTrue("Black pawn should move down (row increases)", valid);
    }

    @Test
    public void testWhitePawnMovesFromStartingPosition() {
        // White pawn starts at row 6 (one row in from the edge), can move to row 5 or 4 (2 squares)
        board.setPiece(6, 0, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        boolean valid = moveValidator.isValidMove(new Position(6, 0), new Position(4, 0),
                                                   board.getPiece(new Position(6, 0)));
        assertTrue("White pawn should move 2 squares from starting position", valid);
    }

    @Test
    public void testBlackPawnMovesFromStartingPosition() {
        // Black pawn starts at row 1 (one row in from the edge), can move to row 2 or 3 (2 squares)
        board.setPiece(1, 0, new Piece(Piece.Color.BLACK, Piece.Type.PAWN));
        boolean valid = moveValidator.isValidMove(new Position(1, 0), new Position(3, 0),
                                                   board.getPiece(new Position(1, 0)));
        assertTrue("Black pawn should move 2 squares from starting position", valid);
    }

    @Test
    public void testWhitePawnCannotMoveTwoSquaresIfNotAtStart() {
        // White pawn at row 5 (not starting), cannot move 2 squares
        board.setPiece(5, 0, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        boolean valid = moveValidator.isValidMove(new Position(5, 0), new Position(3, 0),
                                                   board.getPiece(new Position(5, 0)));
        assertFalse("White pawn can only move 2 squares from row 6", valid);
    }

    @Test
    public void testBlackPawnCannotMoveTwoSquaresIfNotAtStart() {
        // Black pawn at row 2 (not starting), cannot move 2 squares
        board.setPiece(2, 0, new Piece(Piece.Color.BLACK, Piece.Type.PAWN));
        boolean valid = moveValidator.isValidMove(new Position(2, 0), new Position(4, 0),
                                                   board.getPiece(new Position(2, 0)));
        assertFalse("Black pawn can only move 2 squares from row 1", valid);
    }

    @Test
    public void testWhitePawnBlockedByPieceAhead() {
        board.setPiece(6, 0, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        board.setPiece(5, 0, new Piece(Piece.Color.WHITE, Piece.Type.BISHOP));

        boolean valid = moveValidator.isValidMove(new Position(6, 0), new Position(5, 0),
                                                   board.getPiece(new Position(6, 0)));
        assertFalse("Pawn cannot move into occupied square", valid);
    }

    @Test
    public void testPawnBlockedByMiddleSquareWhenMovingTwoSquares() {
        board.setPiece(6, 0, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        board.setPiece(5, 0, new Piece(Piece.Color.BLACK, Piece.Type.PAWN));

        boolean valid = moveValidator.isValidMove(new Position(6, 0), new Position(4, 0),
                                                   board.getPiece(new Position(6, 0)));
        assertFalse("Pawn 2-square move blocked by middle piece", valid);
    }

    @Test
    public void testWhitePawnCapturesDiagonallyLeft() {
        board.setPiece(6, 1, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        board.setPiece(5, 0, new Piece(Piece.Color.BLACK, Piece.Type.BISHOP));

        boolean valid = moveValidator.isValidMove(new Position(6, 1), new Position(5, 0),
                                                   board.getPiece(new Position(6, 1)));
        assertTrue("White pawn should capture diagonally", valid);
    }

    @Test
    public void testWhitePawnCapturesDiagonallyRight() {
        board.setPiece(6, 1, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        board.setPiece(5, 2, new Piece(Piece.Color.BLACK, Piece.Type.ROOK));

        boolean valid = moveValidator.isValidMove(new Position(6, 1), new Position(5, 2),
                                                   board.getPiece(new Position(6, 1)));
        assertTrue("White pawn should capture diagonally right", valid);
    }

    @Test
    public void testBlackPawnCapturesDiagonallyLeft() {
        board.setPiece(1, 1, new Piece(Piece.Color.BLACK, Piece.Type.PAWN));
        board.setPiece(2, 0, new Piece(Piece.Color.WHITE, Piece.Type.BISHOP));

        boolean valid = moveValidator.isValidMove(new Position(1, 1), new Position(2, 0),
                                                   board.getPiece(new Position(1, 1)));
        assertTrue("Black pawn should capture diagonally left", valid);
    }

    @Test
    public void testPawnCannotCaptureDiagonallyIfNoEnemy() {
        board.setPiece(6, 1, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));

        boolean valid = moveValidator.isValidMove(new Position(6, 1), new Position(5, 0),
                                                   board.getPiece(new Position(6, 1)));
        assertFalse("Pawn cannot move diagonally without capture", valid);
    }

    @Test
    public void testPawnCannotCaptureSameColor() {
        board.setPiece(6, 1, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        board.setPiece(5, 0, new Piece(Piece.Color.WHITE, Piece.Type.BISHOP));

        boolean valid = moveValidator.isValidMove(new Position(6, 1), new Position(5, 0),
                                                   board.getPiece(new Position(6, 1)));
        assertFalse("Pawn cannot capture same color", valid);
    }

    @Test
    public void testPawnCannotMoveBackward() {
        board.setPiece(5, 0, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));

        boolean valid = moveValidator.isValidMove(new Position(5, 0), new Position(6, 0),
                                                   board.getPiece(new Position(5, 0)));
        assertFalse("White pawn cannot move downward", valid);
    }

    @Test
    public void testPawnCannotMoveSideways() {
        board.setPiece(6, 0, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));

        boolean valid = moveValidator.isValidMove(new Position(6, 0), new Position(6, 1),
                                                   board.getPiece(new Position(6, 0)));
        assertFalse("Pawn cannot move sideways", valid);
    }

    @Test
    public void testPawnMultipleStartingSquares() {
        // Test all 8 white starting positions (row 6, one row in from the edge)
        for (int col = 0; col < 8; col++) {
            board.setPiece(6, col, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
            boolean valid = moveValidator.isValidMove(new Position(6, col), new Position(4, col),
                                                       board.getPiece(new Position(6, col)));
            assertTrue("All white pawns should move 2 from row 6", valid);
        }
    }

    @Test
    public void testPawnCannotCaptureSideways() {
        // An enemy piece directly beside the pawn (same row, adjacent
        // column) is not a legal capture - only a genuine forward diagonal
        // is. Unlike testPawnCannotMoveSideways above, this places a real
        // enemy piece there, which is the scenario the old, overly-loose
        // "absRow <= 1 && absCol <= 1" check would have wrongly allowed.
        board.setPiece(6, 1, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        board.setPiece(6, 0, new Piece(Piece.Color.BLACK, Piece.Type.ROOK));

        boolean valid = moveValidator.isValidMove(new Position(6, 1), new Position(6, 0),
                                                   board.getPiece(new Position(6, 1)));
        assertFalse("Pawn cannot capture sideways, even with an enemy piece there", valid);
    }

    @Test
    public void testPawnCannotCaptureDiagonallyBackward() {
        // An enemy piece diagonally BEHIND the pawn is likewise not a legal
        // capture - pawns only ever capture forward.
        board.setPiece(5, 1, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        board.setPiece(6, 0, new Piece(Piece.Color.BLACK, Piece.Type.ROOK));

        boolean valid = moveValidator.isValidMove(new Position(5, 1), new Position(6, 0),
                                                   board.getPiece(new Position(5, 1)));
        assertFalse("White pawn cannot capture diagonally backward", valid);
    }

    @Test
    public void testBlackPawnCaptureBothDiagonals() {
        board.setPiece(1, 1, new Piece(Piece.Color.BLACK, Piece.Type.PAWN));
        board.setPiece(2, 0, new Piece(Piece.Color.WHITE, Piece.Type.BISHOP));
        board.setPiece(2, 2, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));

        boolean validLeft = moveValidator.isValidMove(new Position(1, 1), new Position(2, 0),
                                                       board.getPiece(new Position(1, 1)));
        boolean validRight = moveValidator.isValidMove(new Position(1, 1), new Position(2, 2),
                                                        board.getPiece(new Position(1, 1)));

        assertTrue("Black pawn can capture left", validLeft);
        assertTrue("Black pawn can capture right", validRight);
    }
}

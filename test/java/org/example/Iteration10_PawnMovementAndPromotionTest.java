package org.example;

import org.junit.Before;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Iteration 10: Pawn double-move, path-clear, and promotion
 *
 * Tests:
 * 1. Pawn may move 2 cells forward from its start row
 * 2. The path must be clear (blocked by piece)
 * 3. The path must be clear (blocked by friendly active reservation)
 * 4. Pawn reaching last row becomes a queen
 */
public class Iteration10_PawnMovementAndPromotionTest {

    private GameController gameController;
    private Board board;

    @Before
    public void setUp() {
        List<String> boardLines = Arrays.asList(
            "wK . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . bK"
        );
        board = BoardParser.parse(boardLines);
        gameController = new GameController(board);
    }

    @Test
    public void testPawnDoubleMoveFromStartRow() {
        // Place white pawn at its starting row (6,0)
        board.setPiece(6, 0, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        GameController gc = new GameController(board);

        // Select pawn (col 0,row6) and double-move to row4 (two squares)
        gc.handleClick(50, 650);  // select (6,0)
        gc.handleClick(50, 450);  // move to (4,0)

        // Arrival should be 2 * 1000 = 2000ms
        gc.advanceTime(1000);
        assertNotNull("Pawn should still be at origin at 1000ms", board.getPiece(new Position(6, 0)));

        gc.advanceTime(1000);
        assertNull("Pawn origin should be empty after arrival", board.getPiece(new Position(6, 0)));
        assertNotNull("Pawn should arrive at (4,0)", board.getPiece(new Position(4, 0)));
    }

    @Test
    public void testPawnDoubleMoveBlockedByPieceInPath() {
        // Place pawn at (6,0) and a blocking piece at middle (5,0)
        board.setPiece(6, 0, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        board.setPiece(5, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));

        GameController gc = new GameController(board);

        // Attempt double move
        gc.handleClick(50, 650); // select pawn
        gc.handleClick(50, 450); // attempt move to (4,0)

        // Even after waiting, pawn should not have moved because path blocked
        gc.advanceTime(2000);
        assertNotNull(board.getPiece(new Position(6, 0)));
        assertNotNull(board.getPiece(new Position(5, 0)));
        assertNull(board.getPiece(new Position(4, 0)));
    }

    @Test
    public void testPawnDoubleMoveBlockedByActiveReservation() {
        // Pawn at (6,0)
        board.setPiece(6, 0, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        // Friendly rook at (5,2) that will reserve middle (5,0)
        board.setPiece(5, 2, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));

        GameController gc = new GameController(board);

        // Rook moves from (5,2) to (5,0), reserving middle square (arrival in 2s)
        gc.handleClick(250, 550); // select rook at (5,2)
        gc.handleClick(50, 550);  // move to (5,0) (reservation)

        // Now attempt pawn double move which must check active reservations
        gc.handleClick(50, 650);  // select pawn at (6,0)
        gc.handleClick(50, 450);  // attempt double-move to (4,0)

        // After enough time for rook to arrive, pawn should not have been allowed to double-move
        gc.advanceTime(2000);

        assertNotNull("Rook should have arrived at middle (5,0)", board.getPiece(new Position(5, 0)));
        assertNotNull("Pawn should remain at origin because reservation blocked double-move", board.getPiece(new Position(6, 0)));
    }

    @Test
    public void testPawnPromotionToQueenOnLastRow() {
        // Ensure the last row target is empty, then place white pawn at row 1 and move to row 0 to promote
        board.setPiece(0, 0, null); // clear any existing piece (e.g., initial white king)
        board.setPiece(1, 0, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));

        GameController gc = new GameController(board);

        gc.handleClick(50, 150); // select pawn at (1,0)
        gc.handleClick(50, 50);  // move to (0,0)

        gc.advanceTime(1000);

        Piece promoted = board.getPiece(new Position(0, 0));
        assertNotNull("Pawn should arrive at last row and be present", promoted);
        assertEquals("Promoted piece must be QUEEN", Piece.Type.QUEEN, promoted.getType());
        assertEquals("Promoted piece must retain color WHITE", Piece.Color.WHITE, promoted.getColor());
    }
}



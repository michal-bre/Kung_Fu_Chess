package org.example;
import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;
import org.example.adapters.BoardParser;
import org.example.controller.GameController;

import org.junit.Before;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Iteration 10: Pawn double-move and path-clear behavior
 *
 * Tests:
 * 1. Pawn may move 2 cells forward from its start row (row 7 for white - the
 *    board's edge row, not one row in from it; see MoveValidationService).
 * 2. The path must be clear (blocked by piece)
 * 3. The path must be clear (blocked by friendly active reservation)
 * 4. A pawn reaching the last row via a completed move auto-promotes to a
 *    queen (see MovementEngine.resolveSimultaneousArrivals, which checks
 *    promotion right when a piece is placed on the board - never as a
 *    periodic board-wide scan, so a pawn merely sitting on the edge row
 *    without having just moved there stays a pawn; see
 *    PawnPromotionServiceTest for the underlying promotion logic)
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
        gameController = TestGameControllerFactory.create(board);
    }

    @Test
    public void testPawnDoubleMoveFromStartRow() {
        // Place white pawn at its starting row (7,0) - the board edge
        board.setPiece(7, 0, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        GameController gc = TestGameControllerFactory.create(board);

        // Select pawn (col 0,row7) and double-move to row5 (two squares)
        gc.handleClick(50, 750);  // select (7,0)
        gc.handleClick(50, 550);  // move to (5,0)

        // Arrival should be 2 * 1000 = 2000ms
        gc.advanceTime(1000);
        assertNotNull("Pawn should still be at origin at 1000ms", board.getPiece(new Position(7, 0)));

        gc.advanceTime(1000);
        assertNull("Pawn origin should be empty after arrival", board.getPiece(new Position(7, 0)));
        assertNotNull("Pawn should arrive at (5,0)", board.getPiece(new Position(5, 0)));
    }

    @Test
    public void testPawnDoubleMoveBlockedByPieceInPath() {
        // Place pawn at (7,0) and a blocking piece at middle (6,0)
        board.setPiece(7, 0, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        board.setPiece(6, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));

        GameController gc = TestGameControllerFactory.create(board);

        // Attempt double move
        gc.handleClick(50, 750); // select pawn
        gc.handleClick(50, 550); // attempt move to (5,0)

        // Even after waiting, pawn should not have moved because path blocked
        gc.advanceTime(2000);
        assertNotNull(board.getPiece(new Position(7, 0)));
        assertNotNull(board.getPiece(new Position(6, 0)));
        assertNull(board.getPiece(new Position(5, 0)));
    }

    @Test
    public void testPawnDoubleMoveBlockedByActiveReservation() {
        // Pawn at (7,0)
        board.setPiece(7, 0, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));
        // Friendly rook at (6,2) that will reserve middle (6,0)
        board.setPiece(6, 2, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));

        GameController gc = TestGameControllerFactory.create(board);

        // Rook moves from (6,2) to (6,0), reserving middle square (arrival in 2s)
        gc.handleClick(250, 650); // select rook at (6,2)
        gc.handleClick(50, 650);  // move to (6,0) (reservation)

        // Now attempt pawn double move which must check active reservations
        // (same-color reservation - not affected by the opponent-move-blocking rule,
        // which only restricts the OPPOSING color)
        gc.handleClick(50, 750);  // select pawn at (7,0)
        gc.handleClick(50, 550);  // attempt double-move to (5,0)

        // After enough time for rook to arrive, pawn should not have been allowed to double-move
        gc.advanceTime(2000);

        assertNotNull("Rook should have arrived at middle (6,0)", board.getPiece(new Position(6, 0)));
        assertNotNull("Pawn should remain at origin because reservation blocked double-move", board.getPiece(new Position(7, 0)));
    }

    @Test
    public void testPawnAutoPromotesOnLastRow() {
        // A pawn that completes a move landing on the last row auto-promotes
        // to a queen (see MovementEngine.resolveSimultaneousArrivals).
        board.setPiece(0, 0, null); // clear any existing piece (e.g., initial white king)
        board.setPiece(1, 0, new Piece(Piece.Color.WHITE, Piece.Type.PAWN));

        GameController gc = TestGameControllerFactory.create(board);

        gc.handleClick(50, 150); // select pawn at (1,0)
        gc.handleClick(50, 50);  // move to (0,0)

        gc.advanceTime(1000);

        Piece arrived = board.getPiece(new Position(0, 0));
        assertNotNull("Pawn should arrive at the last row", arrived);
        assertEquals("Pawn must auto-promote to a queen", Piece.Type.QUEEN, arrived.getType());
        assertEquals("Color should remain WHITE", Piece.Color.WHITE, arrived.getColor());
    }
}

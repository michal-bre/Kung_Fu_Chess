package org.example;
import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;
import org.example.adapters.BoardParser;
import org.example.controller.GameController;
import org.example.engine.EnginePort;

import org.junit.Before;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Iteration 6: Movement Over Time
 *
 * Test that:
 * 1. Before move arrival time, piece shows at original position
 * 2. After arrival time, piece shows at destination
 * 3. Multiple pieces can move simultaneously
 * 4. Piece position updates only after wait() brings time past arrival
 */
public class Iteration6_MovementOverTimeTest {

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
    public void testPieceStaysAtOriginalPositionBeforeArrival() {
        // Select white king at (0, 0)
        gameController.handleClick(50, 50);

        // Move to (0, 1) which is pixel (100, 50) - King can only move 1 square!
        gameController.handleClick(100, 50);

        // Distance is 1 cell, so arrival time = 1000ms
        // Before that, piece should still be visible at original
        gameController.advanceTime(500);

        Piece originalPos = board.getPiece(new Position(0, 0));
        Piece destPos = board.getPiece(new Position(0, 1));

        assertNotNull("Piece should still be at original position before arrival", originalPos);
        assertNull("Piece should not be at destination yet", destPos);
    }

    @Test
    public void testPieceArrivesAtDestinationAfterArrivalTime() {
        // Select white king at (0, 0)
        gameController.handleClick(50, 50);

        // Move to (0, 1) which is pixel (100, 50) - 1 square distance
        gameController.handleClick(100, 50);

        // Distance is 1 cell, so arrival time = 1000ms
        gameController.advanceTime(1000);

        Piece originalPos = board.getPiece(new Position(0, 0));
        Piece destPos = board.getPiece(new Position(0, 1));

        assertNull("Piece should leave original position after arrival", originalPos);
        assertNotNull("Piece should arrive at destination", destPos);
    }

    @Test
    public void testPieceArrivesWhenTimeExceedsArrivalTime() {
        // Select white king
        gameController.handleClick(50, 50);

        // Move to (0, 1) - 1 square
        gameController.handleClick(100, 50);

        // Wait more than needed (1000ms is needed, wait 1500ms)
        gameController.advanceTime(1500);

        Piece destPos = board.getPiece(new Position(0, 1));
        assertNotNull("Piece should arrive even if we waited longer", destPos);
    }

    @Test
    public void testSameColorPiecesMovingSimultaneously() {
        // Add a second white piece so both movers are the SAME color - only one
        // color may have an active move at a time (a different color's move
        // attempt would be blocked at creation; see Iteration7/8's opponent-move
        // -blocking tests), but two pieces of the same color can move at once.
        board.setPiece(7, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));

        GameController gc = TestGameControllerFactory.create(board);

        // Move white king from (0, 0) to (0, 1)
        gc.handleClick(50, 50);
        gc.handleClick(100, 50);

        // Move white rook from (7, 0) to (7, 1)
        gc.handleClick(50, 750);
        gc.handleClick(150, 750);

        // Both pieces are moving (distance = 1, arrival = 1000ms)
        gc.advanceTime(500);

        // Neither should have arrived yet
        assertNotNull(board.getPiece(new Position(0, 0)));
        assertNotNull(board.getPiece(new Position(7, 0)));

        // Wait more
        gc.advanceTime(600);

        // Both should have arrived
        assertNotNull(board.getPiece(new Position(0, 1)));
        assertNotNull(board.getPiece(new Position(7, 1)));
    }

    @Test
    public void testPieceMovesCorrectDistanceInTime() {
        // Select king, move 1 square (only distance king can move)
        gameController.handleClick(50, 50);      // King at (0,0)
        gameController.handleClick(100, 50);     // Move to (0,1)

        // Arrival time = 1 * 1000 = 1000ms
        gameController.advanceTime(500);

        // Not there yet
        Piece dest = board.getPiece(new Position(0, 1));
        assertNull("Should not have arrived at 500ms", dest);

        gameController.advanceTime(500);

        // Now arrived
        dest = board.getPiece(new Position(0, 1));
        assertNotNull("Should arrive at 1000ms", dest);
    }

    @Test
    public void testIncrementalWaitCalls() {
        gameController.handleClick(50, 50);      // Select king
        gameController.handleClick(100, 50);     // Move 1 square

        // Arrival time = 1000ms
        // Wait in chunks
        gameController.advanceTime(300);
        Piece dest = board.getPiece(new Position(0, 1));
        assertNull("After 300ms, should not arrive", dest);

        gameController.advanceTime(300);
        dest = board.getPiece(new Position(0, 1));
        assertNull("After 600ms, should not arrive", dest);

        gameController.advanceTime(300);
        dest = board.getPiece(new Position(0, 1));
        assertNull("After 900ms, should not arrive", dest);

        gameController.advanceTime(100);
        dest = board.getPiece(new Position(0, 1));
        assertNotNull("After 1000ms, should arrive", dest);
    }

    @Test
    public void testPieceCanMoveAgainAfterArrivalAndRest() {
        // First move: king to (0, 1)
        gameController.handleClick(50, 50);
        gameController.handleClick(100, 50);
        gameController.advanceTime(1000);

        // Piece should be at (0, 1)
        assertNotNull(board.getPiece(new Position(0, 1)));

        // A piece rests briefly after a move before it's eligible to move
        // again (move -> long_rest -> idle; see EnginePort.REST_AFTER_MOVE_MS
        // and Iteration7_BlockedRedirectsTest's dedicated cooldown tests).
        gameController.advanceTime(EnginePort.REST_AFTER_MOVE_MS);

        // Second move: from (0, 1) to (0, 2) - one more diagonal step
        gameController.handleClick(100, 50);    // Select king at (0, 1)
        gameController.handleClick(250, 50);    // Move to (0, 2)
        gameController.advanceTime(1000);

        // Piece should now be at (0, 2)
        assertNotNull(board.getPiece(new Position(0, 2)));
    }

    @Test
    public void testRookMovesLongDistance() {
        // Place rook
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));

        GameController gc = TestGameControllerFactory.create(board);

        // Move rook 2 squares horizontally
        // Pixels: 50=0, 150=1, 250=2, 350=3
        gc.handleClick(50, 50);        // Select rook at (0, 0)
        gc.handleClick(250, 50);       // Move to (0, 2)

        // Distance = 2, arrival = 2000ms
        gc.advanceTime(1000);
        assertNull(board.getPiece(new Position(0, 2)));

        gc.advanceTime(1000);
        assertNotNull(board.getPiece(new Position(0, 2)));
    }

    @Test
    public void testKnightMovesDiagonalDistance() {
        // Knight movement is L-shape, distance calculated as max(deltaRow, deltaCol)
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.KNIGHT));

        GameController gc = TestGameControllerFactory.create(board);

        // Move knight from (4,4) to (5,6)
        // This is a valid knight L-move: deltaRow=1, deltaCol=2, distance=2
        // Pixel: x=450 (col 4), x=650 (col 6); y=450 (row 4), y=550 (row 5)
        gc.handleClick(450, 450);      // Select knight at (4,4)
        gc.handleClick(650, 550);      // Move to (5,6)

        // Arrival = 2 * 1000 = 2000ms
        gc.advanceTime(1000);
        assertNull(board.getPiece(new Position(5, 6)));

        gc.advanceTime(1000);
        assertNotNull(board.getPiece(new Position(5, 6)));
    }

    @Test
    public void testPieceRemovesFromOriginalWhenComplete() {
        gameController.handleClick(50, 50);      // Select king
        gameController.handleClick(100, 50);     // Move 1 square

        gameController.advanceTime(1000);

        Piece original = board.getPiece(new Position(0, 0));
        assertNull("Original position should be empty after move completes", original);
    }
}

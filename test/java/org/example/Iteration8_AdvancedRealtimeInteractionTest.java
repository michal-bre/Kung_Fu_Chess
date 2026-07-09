package org.example;

import org.junit.Before;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Iteration 8: Advanced real-time interaction cases
 *
 * Tests included:
 * 1. Air-capture (enemy collision) — a prepared jump captures an incoming move created at the same start time
 * 2. Invalid premove — trying to premove into a square already reserved by a friendly active move is rejected
 * 3. Friendly-piece landing — cannot move onto a square already occupied by a friendly piece
 * 4. Jump after move started — jumping after an enemy move has already started causes the enemy move to be executed immediately
 * 5. Simultaneous conflicting moves to same square (different colors) — both origins cleared and the destination is occupied by one piece
 */
public class Iteration8_AdvancedRealtimeInteractionTest {

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
    public void testAirCaptureByPreparedJump() {
        // Place a black defender on the target square (0,1)
        board.setPiece(0, 1, new Piece(Piece.Color.BLACK, Piece.Type.ROOK));

        GameController gc = new GameController(board);

        // Black prepares a jump on (0,1)
        gc.handleJump(100, 50); // row=0,col=1 -> defender creates a jump at that square

        // White starts a move from (0,0) to (0,1) at the same logical start time
        gc.handleClick(50, 50);   // select white king at (0,0)
        gc.handleClick(100, 50);  // attempt to move to (0,1)

        // triggerAirCaptures runs during addMove: the white move should be captured in-air
        // The white piece should be removed from its origin immediately and should NOT arrive at (0,1)
        assertNull("White origin should be cleared when captured in air", board.getPiece(new Position(0, 0)));
        assertNotNull("Defender should remain on the defended square", board.getPiece(new Position(0, 1)));
    }

    @Test
    public void testInvalidPremoveBlockedByFriendlyReservation() {
        // Place two white pieces: one at (0,0) and one at (0,2)
        board.setPiece(0, 2, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));

        GameController gc = new GameController(board);

        // Reserve (0,1) by moving the rook at (0,2) to (0,1)
        gc.handleClick(250, 50); // select rook at (0,2)
        gc.handleClick(150, 50); // move to (0,1)

        // Now attempt to premove the rook at (0,0) to the same square (0,1) — should be rejected
        gc.handleClick(50, 50);  // select king (we'll pretend king tries to move there)
        gc.handleClick(150, 50); // try move to (0,1)

        // Advance time so the first reserved move completes
        gc.advanceTime(1000);

        // The piece that reserved (0,1) should have arrived there, the other should not have moved
        assertNotNull(board.getPiece(new Position(0, 1)));
        assertNotNull(board.getPiece(new Position(0, 0)));
    }

    @Test
    public void testCannotLandOnFriendlyPiece() {
        // Place a friendly piece at (0,1)
        board.setPiece(0, 1, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));

        GameController gc = new GameController(board);

        // Try to move white king from (0,0) to (0,1) where a friendly rook stands
        gc.handleClick(50, 50);   // select king
        gc.handleClick(100, 50);  // attempt to move onto (0,1)

        // Advance time - nothing should have happened because move was invalid
        gc.advanceTime(1000);

        assertNotNull("Friendly piece must remain at (0,1)", board.getPiece(new Position(0, 1)));
        assertNotNull("Original piece must remain at (0,0)", board.getPiece(new Position(0, 0)));
    }

    @Test
    public void testJumpAfterMoveStartedExecutesImmediateArrival() {
        // Place a black defender that will jump after white starts moving
        board.setPiece(0, 1, new Piece(Piece.Color.BLACK, Piece.Type.ROOK));

        GameController gc = new GameController(board);

        // White starts moving from (0,0) to (0,1)
        gc.handleClick(50, 50);
        gc.handleClick(100, 50);

        // Advance a tiny amount so the move is considered "already started"
        gc.advanceTime(1);

        // Now black jumps on (0,1) — according to rules this should cause the threatening white move to be executed immediately
        gc.handleJump(100, 50);

        // The white piece should already have been moved to (0,1) by the immediate resolution
        assertNotNull("White should arrive immediately when jump happens after move start", board.getPiece(new Position(0, 1)));
        assertNull("Original white source should be cleared when moved immediately", board.getPiece(new Position(0, 0)));
    }

    @Test
    public void testSimultaneousDifferentColorToSameSquare() {
        // Place a white rook at (0,0) and a black rook at (0,2)
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(0, 2, new Piece(Piece.Color.BLACK, Piece.Type.ROOK));

        GameController gc = new GameController(board);

        // Both attempt to move to (0,1) simultaneously
        gc.handleClick(50, 50);   // select white rook at (0,0)
        gc.handleClick(150, 50);  // move to (0,1)

        gc.handleClick(250, 50);  // select black rook at (0,2)
        gc.handleClick(150, 50);  // move to (0,1)

        // Advance time until arrival
        gc.advanceTime(1000);

        // Both origins should be cleared
        assertNull(board.getPiece(new Position(0, 0)));
        assertNull(board.getPiece(new Position(0, 2)));

        // Destination should be occupied by one piece of some color (race resolved deterministically by insertion order)
        assertNotNull("Destination must be occupied by one of the movers", board.getPiece(new Position(0, 1)));
    }
}


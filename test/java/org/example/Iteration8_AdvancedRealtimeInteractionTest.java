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
 * Iteration 8: Advanced real-time interaction cases
 *
 * Tests included:
 * 1. Air-capture (enemy collision) — a prepared jump captures an incoming move created at the same start time
 * 2. Invalid premove — trying to premove into a square already reserved by a friendly active move is rejected
 * 3. Friendly-piece landing — cannot move onto a square already occupied by a friendly piece
 * 4. Jump defends within a reaction window — a jump defeats an incoming attacker if created within JUMP_DEFENSE_WINDOW_MS of the attack starting; created later, the attacker's move is instead forced to complete immediately, capturing the piece that tried to jump
 * 5. Both colors can have a move in flight at the same time — real-time chess has no turns, so white and black moving concurrently (to different squares) is expected, not blocked
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
        gameController = TestGameControllerFactory.create(board);
    }

    @Test
    public void testAirCaptureByPreparedJump() {
        // Place a black defender on the target square (0,1)
        board.setPiece(0, 1, new Piece(Piece.Color.BLACK, Piece.Type.ROOK));

        GameController gc = TestGameControllerFactory.create(board);

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

        GameController gc = TestGameControllerFactory.create(board);

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

        GameController gc = TestGameControllerFactory.create(board);

        // Try to move white king from (0,0) to (0,1) where a friendly rook stands
        gc.handleClick(50, 50);   // select king
        gc.handleClick(100, 50);  // attempt to move onto (0,1)

        // Advance time - nothing should have happened because move was invalid
        gc.advanceTime(1000);

        assertNotNull("Friendly piece must remain at (0,1)", board.getPiece(new Position(0, 1)));
        assertNotNull("Original piece must remain at (0,0)", board.getPiece(new Position(0, 0)));
    }

    @Test
    public void testJumpDefeatsAttackerWithinReactionWindow() {
        // Place a black defender that will jump after white starts moving
        board.setPiece(0, 1, new Piece(Piece.Color.BLACK, Piece.Type.ROOK));

        GameController gc = TestGameControllerFactory.create(board);

        // White starts moving from (0,0) to (0,1)
        gc.handleClick(50, 50);
        gc.handleClick(100, 50);

        // Time passes before black reacts, but still within the reaction
        // window (JUMP_DEFENSE_WINDOW_MS = 800ms) - the jump should still
        // successfully defend.
        gc.advanceTime(500);

        // Black jumps on (0,1) in response to the incoming attack.
        gc.handleJump(100, 50);

        // The incoming white move is captured in-air: its origin is cleared
        // and it never reaches (0,1) - the black defender remains there.
        assertNull("White origin should be cleared when captured in air", board.getPiece(new Position(0, 0)));
        assertNotNull("Defender should remain on the defended square", board.getPiece(new Position(0, 1)));
        assertEquals(Piece.Color.BLACK, board.getPiece(new Position(0, 1)).getColor());
    }

    @Test
    public void testJumpTooLateLetsAttackerLand() {
        // Place a black defender that will react too slowly to white's attack
        board.setPiece(0, 1, new Piece(Piece.Color.BLACK, Piece.Type.ROOK));

        GameController gc = TestGameControllerFactory.create(board);

        // White starts moving from (0,0) to (0,1) - a 1000ms move.
        gc.handleClick(50, 50);
        gc.handleClick(100, 50);

        // Time passes beyond the reaction window (JUMP_DEFENSE_WINDOW_MS =
        // 800ms), but before white's move would naturally complete (1000ms).
        gc.advanceTime(900);

        // Black jumps on (0,1) - too late to defend.
        gc.handleJump(100, 50);

        // White's attack is forced to complete immediately instead: it
        // captures the black defender and occupies the square, rather than
        // being defeated by the late jump.
        assertNull("White origin should be cleared once its move is forced to complete", board.getPiece(new Position(0, 0)));
        assertNotNull("White should occupy the square, having captured the defender", board.getPiece(new Position(0, 1)));
        assertEquals(Piece.Color.WHITE, board.getPiece(new Position(0, 1)).getColor());
    }

    @Test
    public void testBothColorsCanMoveSimultaneously() {
        // Place a white rook at (0,0) and a black rook at (0,4), each heading to
        // its own separate, uncontested destination.
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(0, 4, new Piece(Piece.Color.BLACK, Piece.Type.ROOK));

        GameController gc = TestGameControllerFactory.create(board);

        // White starts moving to (0,1) first.
        gc.handleClick(50, 50);   // select white rook at (0,0)
        gc.handleClick(150, 50);  // move to (0,1)

        // Black starts moving to (0,3) - a different square - while white's move
        // is still in flight. Real-time chess has no turns: both colors may have
        // a move in flight at once, so this should be accepted immediately, not
        // blocked until white's move resolves.
        gc.handleClick(450, 50);  // select black rook at (0,4)
        gc.handleClick(350, 50);  // move to (0,3)

        // Advance time until both moves arrive.
        gc.advanceTime(1000);

        // White's origin is cleared and it occupies its destination.
        assertNull("White origin should be cleared once its move completes", board.getPiece(new Position(0, 0)));
        assertNotNull("White should occupy its destination", board.getPiece(new Position(0, 1)));
        assertEquals(Piece.Color.WHITE, board.getPiece(new Position(0, 1)).getColor());

        // Black's origin is cleared and it occupies its own destination too - its
        // move was created and completed concurrently with white's, not blocked.
        assertNull("Black origin should be cleared once its move completes", board.getPiece(new Position(0, 4)));
        assertNotNull("Black should occupy its destination", board.getPiece(new Position(0, 3)));
        assertEquals(Piece.Color.BLACK, board.getPiece(new Position(0, 3)).getColor());
    }
}

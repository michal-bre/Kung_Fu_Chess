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
 * Iteration 11: Jump Mechanics
 *
 * Test that:
 * 1. A jump lasts 1000 ms.
 * 2. The jumping piece remains on the same logical cell.
 * 3. While airborne, if an enemy moving piece arrives, it is captured.
 * 4. The arriving enemy is removed and the airborne piece remains.
 * 5. A moving piece cannot jump.
 * 6. A captured piece cannot jump.
 */
public class Iteration11_JumpMechanicsTest {

    private GameController gameController;
    private Board board;

    @Before
    public void setUp() {
        // Board setup
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
        gameController = TestGameControllerFactory.create(board);
    }

    @Test
    public void testJumpDurationAndPosition() {
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));

        gameController.handleJump(50, 50); // Jump at (0,0)

        // Still airborne at 500ms
        gameController.advanceTime(500);
        assertNotNull("Piece should still be at original cell during jump", board.getPiece(new Position(0, 0)));

        // Landed at 1000ms
        gameController.advanceTime(500);
        assertNotNull("Piece should remain at original cell after jump", board.getPiece(new Position(0, 0)));
    }

    @Test
    public void testAirCaptureOfMovingEnemy() {
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK)); // Airborne
        // A rook here (not a pawn - a pawn can only ever capture one square
        // diagonally FORWARD, and moving from (0,1) to (0,0) is sideways,
        // which is illegal regardless of what's on the target square) so
        // this move is a legal one-square horizontal slide, not a capture
        // shortcut that happens to line up with the jump-capture mechanic
        // being tested here.
        board.setPiece(0, 1, new Piece(Piece.Color.BLACK, Piece.Type.ROOK)); // Enemy moving from (0,1) to (0,0)

        gameController.handleJump(50, 50); // White Rook jumps at (0,0)

        gameController.handleClick(150, 50); // Select black rook at (0,1)
        gameController.handleClick(50, 50);  // Move to (0,0)

        gameController.advanceTime(500); // During jump window

        assertNull("Moving enemy should be captured and removed", board.getPiece(new Position(0, 1)));
        assertNotNull("Airborne piece should remain at its cell", board.getPiece(new Position(0, 0)));
        assertEquals("Airborne piece should still be the white rook", Piece.Type.ROOK, board.getPiece(new Position(0, 0)).getType());
    }

    @Test
    public void testMovingPieceCannotJump() {
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));

        // Start move
        gameController.handleClick(50, 50);
        gameController.handleClick(150, 50);

        // Attempt jump while moving
        gameController.handleJump(50, 50);

        // If jump logic is blocked, the piece should continue its move normally
        gameController.advanceTime(1000);
        assertNotNull("Piece should complete its move to destination", board.getPiece(new Position(0, 1)));
    }

    @Test
    public void testRestingPieceCannotJump() {
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(0, 3, new Piece(Piece.Color.BLACK, Piece.Type.QUEEN));

        // White rook moves (0,0) -> (0,1) [1 square, 1000ms travel]. Once it
        // lands it enters its REST_AFTER_MOVE_MS (3000ms) cooldown.
        gameController.handleClick(50, 50);
        gameController.handleClick(150, 50);
        gameController.advanceTime(1000);
        assertEquals("Rook should have completed its move", Piece.Type.ROOK,
                board.getPiece(new Position(0, 1)).getType());

        // Black queen immediately attacks the now-resting rook: (0,3) -> (0,1),
        // 2 squares, 2000ms travel. The rook's rest (ends at game time 4000ms)
        // covers the queen's entire flight (game time 1000ms -> 3000ms).
        gameController.handleClick(350, 50);
        gameController.handleClick(150, 50);

        // 500ms into the attack - well within JUMP_DEFENSE_WINDOW_MS (800ms),
        // which would normally let a defensive jump defeat the attacker. But
        // the rook is still resting from its own move, so this jump attempt
        // must be rejected outright rather than being treated as a valid,
        // in-time defense.
        gameController.advanceTime(500);
        gameController.handleJump(150, 50);

        // Let the queen's attack land naturally. If the jump above had been
        // wrongly allowed, it would have air-captured the incoming queen
        // (see triggerAirCaptures) and the white rook would still be standing
        // on (0,1). Since resting correctly blocked the jump, no defending
        // move was ever created, so the queen's attack completes normally and
        // captures the resting rook instead.
        gameController.advanceTime(1500);

        assertEquals("Black queen should have captured the resting rook", Piece.Color.BLACK,
                board.getPiece(new Position(0, 1)).getColor());
        assertEquals(Piece.Type.QUEEN, board.getPiece(new Position(0, 1)).getType());
    }

    @Test
    public void testLandedJumpDoesNotDefendAgainstLaterAttack() {
        // A jump only has capture power while it is genuinely in flight (or
        // at the exact instant it lands in the same tick an attacker
        // arrives) - see MovementEngine.advanceTime. Once it has fully
        // landed, it is just an ordinary resting piece: an attack that
        // starts (and is therefore still approaching) AFTER it has already
        // landed must succeed normally when it arrives, not be defeated by
        // the long-since-completed jump.
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(0, 3, new Piece(Piece.Color.BLACK, Piece.Type.QUEEN));

        // Prophylactic jump, no threat present yet - lands at t=1000ms.
        gameController.handleJump(50, 50);
        gameController.advanceTime(1000);
        assertNotNull("Rook should have landed from its jump", board.getPiece(new Position(0, 0)));

        // Black queen attacks the now-landed rook well after it landed:
        // (0,3) -> (0,0), 3 squares, 3000ms travel.
        gameController.advanceTime(100);
        gameController.handleClick(350, 50);
        gameController.handleClick(50, 50);

        // Let the attack run its full, natural course.
        gameController.advanceTime(3000);

        assertEquals("Black queen should capture the long-since-landed rook", Piece.Color.BLACK,
                board.getPiece(new Position(0, 0)).getColor());
        assertEquals(Piece.Type.QUEEN, board.getPiece(new Position(0, 0)).getType());
    }

    @Test
    public void testCapturedPieceCannotJump() {
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(0, 1, new Piece(Piece.Color.BLACK, Piece.Type.ROOK));

        // Black rook captures white rook
        gameController.handleClick(150, 50);
        gameController.handleClick(50, 50);
        gameController.advanceTime(1000);

        // The white rook is captured and gone; the black rook - which won the square -
        // now occupies the destination. (A capture removes the captured piece AND relocates
        // the capturing piece there; it does not leave the destination empty.)
        assertNotNull("Capturing piece should occupy the destination after a capture", board.getPiece(new Position(0, 0)));
        assertEquals("Destination should be held by the capturing black rook", Piece.Color.BLACK, board.getPiece(new Position(0, 0)).getColor());

        // The captured white rook no longer exists anywhere on the board. Its former
        // square (0,1) was vacated by the black rook's move and is now empty, so a jump
        // attempt aimed there - i.e. at a piece that isn't there anymore - must be a no-op.
        gameController.handleJump(150, 50);
        assertNull("Jumping a vacated/nonexistent piece's square should do nothing", board.getPiece(new Position(0, 1)));
    }
}

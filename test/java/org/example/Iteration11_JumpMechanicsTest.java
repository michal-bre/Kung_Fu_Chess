package org.example;

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
        gameController = new GameController(board);
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
        board.setPiece(0, 1, new Piece(Piece.Color.BLACK, Piece.Type.PAWN)); // Enemy moving from (0,1) to (0,0)

        gameController.handleJump(50, 50); // White Rook jumps at (0,0)

        gameController.handleClick(150, 50); // Select black pawn at (0,1)
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
    public void testCapturedPieceCannotJump() {
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(0, 1, new Piece(Piece.Color.BLACK, Piece.Type.ROOK));

        // Black rook captures white rook
        gameController.handleClick(150, 50);
        gameController.handleClick(50, 50);
        gameController.advanceTime(1000);

        // White rook is removed
        assertNull(board.getPiece(new Position(0, 0)));

        // Attempt jump with the removed piece (should be ignored)
        gameController.handleJump(50, 50);
        assertNull("Removed piece cannot initiate a jump", board.getPiece(new Position(0, 0)));
    }
}
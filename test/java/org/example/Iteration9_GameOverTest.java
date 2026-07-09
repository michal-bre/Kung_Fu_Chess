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
 * Iteration 9: Game-over behavior
 *
 * Tests that:
 * 1. Capturing the enemy king ends the game (no further moves allowed)
 * 2. After game over, later move/jump commands are ignored
 */
public class Iteration9_GameOverTest {

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
    public void testCaptureKingEndsGameAndIgnoresLaterMoves() {
        // Place a white rook next to the black king at (7,6)
        board.setPiece(7, 6, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));

        GameController gc = TestGameControllerFactory.create(board);

        // Move rook from (7,6) to (7,7) to capture the black king
        gc.handleClick(650, 750); // select rook at (7,6)
        gc.handleClick(750, 750); // target (7,7)

        // Advance until arrival
        gc.advanceTime(1000);

        // The destination should now hold the white rook (king captured)
        Piece atDest = board.getPiece(new Position(7, 7));
        assertNotNull("Destination should be occupied after capture", atDest);
        assertEquals("Capturing piece should be white", Piece.Color.WHITE, atDest.getColor());

        // Now attempt to move another piece after game over: select white king and try to move
        gc.handleClick(50, 50);   // select white king at (0,0)
        gc.handleClick(100, 50);  // try move to (0,1)
        gc.advanceTime(1000);

        // Move should be ignored because game is over: king should remain at original
        assertNotNull("After game over, further moves should be ignored (origin remains)", board.getPiece(new Position(0, 0)));
        assertNull("After game over, destination should remain empty", board.getPiece(new Position(0, 1)));
    }

    @Test
    public void testCommandsIgnoredAfterGameOverIncludingJumps() {
        // Place a white rook next to the black king at (7,6)
        board.setPiece(7, 6, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));

        GameController gc = TestGameControllerFactory.create(board);

        // Capture king as before
        gc.handleClick(650, 750);
        gc.handleClick(750, 750);
        gc.advanceTime(1000);

        // Ensure game over condition resulted in capture
        assertNotNull(board.getPiece(new Position(7, 7)));

        // Attempt a jump command after game over; should be ignored
        gc.handleJump(50, 50); // try to jump with white king
        gc.advanceTime(1000);

        // Nothing should have changed for the white king (still at 0,0)
        assertNotNull(board.getPiece(new Position(0, 0)));
    }
}


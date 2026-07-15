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
 * Iteration 7: Blocked Redirects During Movement
 *
 * Test that:
 * 1. Piece cannot be redirected while already moving
 * 2. After a piece arrives, it must rest (EnginePort.REST_AFTER_MOVE_MS)
 *    before it can move again - attempting immediately is silently rejected,
 *    and moving again succeeds only once that rest period has elapsed
 * 3. Clicking on moving piece is ignored
 * 4. Clicking on occupied destination square during movement is ignored
 */
public class Iteration7_BlockedRedirectsTest {

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
    public void testCannotClickMovingPiece() {
        // Start king move from (0, 0) to (0, 1)
        gameController.handleClick(50, 50);        // Select king
        gameController.handleClick(100, 50);       // Move 1 square

        // Piece is now moving (arrival at 1000ms)
        // Try to click the moving piece (at original location before it moves visually)
        gameController.handleClick(50, 50);        // Try to select again

        // Wait for original move to complete
        gameController.advanceTime(1000);

        // King should be at (0, 1), not at (0, 0)
        assertNull(board.getPiece(new Position(0, 0)));
        assertNotNull(board.getPiece(new Position(0, 1)));
    }

    @Test
    public void testCannotRedirectMovingPiece() {
        // Start king move from (0, 0) to (0, 1)
        gameController.handleClick(50, 50);        // Select king
        gameController.handleClick(100, 50);       // Move 1 square

        // Try to redirect: click different square while moving
        gameController.handleClick(150, 50);       // Try to move to (0, 2)

        // Wait for original move
        gameController.advanceTime(1000);

        // King should complete original move to (0, 1)
        assertNull(board.getPiece(new Position(0, 2)));
        assertNotNull(board.getPiece(new Position(0, 1)));
    }

    @Test
    public void testMovingPieceNotReselectable() {
        // Move king
        gameController.handleClick(50, 50);        // Select
        gameController.handleClick(100, 50);       // Move 1 square

        // Try to interact with it while moving
        gameController.handleClick(50, 50);        // Try to click source
        gameController.handleClick(150, 50);       // Try to click destination mid-move

        // Original move should complete
        gameController.advanceTime(1000);

        Piece dest = board.getPiece(new Position(0, 1));
        assertNotNull("Original move should complete", dest);
    }

    @Test
    public void testCanMoveAgainOnceRestElapsesAfterArrival() {
        // First move: (0, 0) to (0, 1)
        gameController.handleClick(50, 50);        // Select king
        gameController.handleClick(100, 50);       // Move 1 square

        gameController.advanceTime(1000);          // Wait for arrival

        // King should now be at (0, 1)
        assertNotNull(board.getPiece(new Position(0, 1)));

        // A piece cannot start a new move again until its rest period
        // elapses (move -> long_rest -> idle).
        gameController.advanceTime(EnginePort.REST_AFTER_MOVE_MS);

        // Second move once rested: (0, 1) to (0, 2)
        gameController.handleClick(100, 50);       // Select king again
        gameController.handleClick(250, 50);       // Move 1 square

        gameController.advanceTime(1000);

        // King should be at (0, 2)
        assertNull(board.getPiece(new Position(0, 1)));
        assertNotNull(board.getPiece(new Position(0, 2)));
    }

    @Test
    public void testCooldownBlocksImmediateReselectThenAllowsAfterRest() {
        // Move 1: short distance
        gameController.handleClick(50, 50);        // Select
        gameController.handleClick(100, 50);       // 1 square
        gameController.advanceTime(1000);

        // Move 2 attempted immediately, with no delay: the king is still
        // resting, so it cannot be reselected and this whole attempt is a
        // silent no-op.
        gameController.handleClick(100, 50);       // Select at new position - rejected, still resting
        gameController.handleClick(250, 50);       // No piece was selected, so this is also a no-op
        gameController.advanceTime(1);

        assertNotNull("King should still be at its first destination", board.getPiece(new Position(0, 1)));
        assertNull("King must not have moved a second time while still resting", board.getPiece(new Position(0, 2)));

        // Once the rest period elapses, the same move succeeds.
        gameController.advanceTime(EnginePort.REST_AFTER_MOVE_MS);
        gameController.handleClick(100, 50);       // Select at new position
        gameController.handleClick(250, 50);       // 1 square
        gameController.advanceTime(1000);

        // Should be at (0, 2)
        assertNotNull(board.getPiece(new Position(0, 2)));
    }

    @Test
    public void testOtherPieceCannotBeLandedOnDuringMove() {
        // Place second white piece
        board.setPiece(0, 1, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));

        GameController gc = TestGameControllerFactory.create(board);

        // Try to move king to occupied square
        gc.handleClick(50, 50);        // Select king
        gc.handleClick(100, 50);       // Try to move to (0, 1) where rook is

        // This should be invalid move, so king stays at (0, 0)
        gc.advanceTime(1000);

        assertNotNull(board.getPiece(new Position(0, 0)));
        assertNotNull(board.getPiece(new Position(0, 1)));
    }

    @Test
    public void testSequentialMovesEachWaitingOutRest() {
        // Sequence of moves, each one waiting out the previous move's rest
        // period before the next is allowed to begin.
        for (int i = 0; i < 3; i++) {
            gameController.handleClick(50 + (i * 100), 50);     // Select
            gameController.handleClick(50 + ((i + 1) * 100), 50); // Move 1 square
            gameController.advanceTime(1000);
            gameController.advanceTime(EnginePort.REST_AFTER_MOVE_MS);
        }

        // After 3 moves, king should be at (0, 3)
        assertNotNull(board.getPiece(new Position(0, 3)));
        assertNull(board.getPiece(new Position(0, 0)));
    }

    @Test
    public void testClickOnMovingPieceBeforeArrival() {
        // Start move with 1 square distance = 1000ms arrival
        gameController.handleClick(50, 50);        // Select king at (0, 0)
        gameController.handleClick(100, 50);       // Move to (0, 1)

        // Try to click at 300ms (before arrival at 1000ms)
        gameController.advanceTime(300);

        // Try to interact with piece (click at old position)
        gameController.handleClick(50, 50);

        gameController.advanceTime(700);          // Finish movement

        // Should complete original move
        assertNotNull(board.getPiece(new Position(0, 1)));
    }

    @Test
    public void testBothColorsCanMoveSimultaneously() {
        // Create second piece
        board.setPiece(7, 7, new Piece(Piece.Color.BLACK, Piece.Type.KING));

        GameController gc = TestGameControllerFactory.create(board);

        // Move white king: (0, 0) to (0, 1) = 1000ms
        gc.handleClick(50, 50);
        gc.handleClick(100, 50);

        // Black starts moving while white's move is still in flight. Real-time
        // chess has no turns: both colors may have a move in flight at once, so
        // this is accepted immediately rather than blocked until white finishes.
        gc.handleClick(750, 750);
        gc.handleClick(650, 750);

        gc.advanceTime(1000);

        // White's move completed normally.
        assertNull(board.getPiece(new Position(0, 0)));
        assertNotNull(board.getPiece(new Position(0, 1)));

        // Black's move completed too, concurrently with white's.
        assertNull("Black king should have moved concurrently with white", board.getPiece(new Position(7, 7)));
        assertNotNull(board.getPiece(new Position(7, 6)));
    }

    @Test
    public void testCannotInterruptMove() {
        // Start move: 1 square = 1000ms
        gameController.handleClick(50, 50);        // King at (0, 0)
        gameController.handleClick(100, 50);       // To (0, 1)

        // Try interruptions at different times
        gameController.advanceTime(300);
        gameController.handleClick(150, 50);       // Try to redirect

        gameController.advanceTime(700);

        // Should have completed original move to (0, 1)
        assertNotNull(board.getPiece(new Position(0, 1)));
        assertNull(board.getPiece(new Position(0, 0)));
    }

    @Test
    public void testPieceBlockedFromMovingSourceIgnored() {
        // King moving from (0, 0)
        gameController.handleClick(50, 50);        // Select king
        gameController.handleClick(100, 50);       // Move to (0, 1)

        // During move, king cannot be selected from original position
        gameController.handleClick(50, 50);

        // And nothing should happen when trying to "unselect"
        gameController.advanceTime(1000);

        assertNotNull(board.getPiece(new Position(0, 1)));
    }

    @Test
    public void testSecondPieceCanMoveWhileFirstIsMoving() {
        board.setPiece(1, 1, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));

        GameController gc = TestGameControllerFactory.create(board);

        // Start king move: 1 square
        gc.handleClick(50, 50);        // Select king
        gc.handleClick(100, 50);       // Move 1 square

        // While king is moving, move rook
        gc.handleClick(100, 100);      // Select rook at (1, 1)
        gc.handleClick(250, 100);      // Move 1 square

        gc.advanceTime(1000);          // Rook arrives (and king also arrives)

        assertNotNull(board.getPiece(new Position(1, 2)));
        assertNull(board.getPiece(new Position(1, 1)));

        assertNotNull(board.getPiece(new Position(0, 1)));
    }
}

package org.example;

import org.example.bus.EventBus;
import org.example.bus.GameEndedEvent;
import org.example.bus.GameStartedEvent;
import org.example.bus.JumpPerformedEvent;
import org.example.bus.MoveLoggedEvent;
import org.example.bus.ScoreUpdatedEvent;
import org.example.controller.GameController;
import org.example.engine.MovementEngine;
import org.example.model.Board;
import org.example.model.Piece;
import org.example.adapters.BoardParser;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Locks in the CTD 26 server spec's "BUS" slide (implement pub/sub, use it
 * for score updates / move logs / sound / game start-end) end to end,
 * through the real production call paths (MovementEngine, InteractionHandler,
 * DefaultGameEngine) - not by publishing test events directly, since the
 * whole point of Phase 1 was wiring the ENGINE to publish these, not just
 * proving EventBus's own publish/subscribe mechanics work in isolation
 * (that part is trivial enough it doesn't need its own test here).
 */
public class EventBusIntegrationTest {

    private Board board;
    private EventBus bus;

    @Before
    public void setUp() {
        List<String> emptyBoard = Arrays.asList(
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . ."
        );
        board = BoardParser.parse(emptyBoard);
        bus = new EventBus();
    }

    @Test
    public void gameStartedEventFiresOnceOnEngineConstruction() {
        List<GameStartedEvent> received = new ArrayList<>();
        bus.subscribe(GameStartedEvent.class, received::add);

        new MovementEngine(board, bus);

        assertEquals(1, received.size());
    }

    @Test
    public void scoreUpdatedEventFiresWithRunningTotalOnCapture() {
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(0, 2, new Piece(Piece.Color.BLACK, Piece.Type.ROOK));
        GameController gameController = TestGameControllerFactory.create(board, bus);

        List<ScoreUpdatedEvent> received = new ArrayList<>();
        bus.subscribe(ScoreUpdatedEvent.class, received::add);

        // (0,0) -> (0,2): a 2-square slide straight onto the black rook -
        // an "existing occupant captured on arrival" in
        // MovementEngine.resolveSimultaneousArrivals, worth 5 points
        // (PieceScore.valueOf(ROOK)).
        gameController.handleClick(50, 50);
        gameController.handleClick(250, 50);
        gameController.advanceTime(2000);

        assertEquals(1, received.size());
        assertEquals(Piece.Color.WHITE, received.get(0).getColor());
        assertEquals(5, received.get(0).getNewScore());
        assertEquals(5, gameController.getScore(Piece.Color.WHITE));
    }

    @Test
    public void moveLoggedEventIsNotFlaggedAsCaptureForAPlainMove() {
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        GameController gameController = TestGameControllerFactory.create(board, bus);

        List<MoveLoggedEvent> received = new ArrayList<>();
        bus.subscribe(MoveLoggedEvent.class, received::add);

        // MoveLoggedEvent fires the instant InteractionHandler accepts the
        // move (see its handleClick) - not when it later lands - so no
        // advanceTime is needed here at all.
        gameController.handleClick(50, 50);   // select (0,0)
        gameController.handleClick(150, 50);  // (0,0) -> (1,0), empty square

        assertEquals(1, received.size());
        assertFalse("a move onto an empty square must not be flagged as a capture",
                received.get(0).isCapture());
        assertEquals(Piece.Color.WHITE, received.get(0).getEntry().getColor());
    }

    @Test
    public void moveLoggedEventIsFlaggedAsCaptureWhenDestinationIsOccupied() {
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(0, 2, new Piece(Piece.Color.BLACK, Piece.Type.ROOK));
        GameController gameController = TestGameControllerFactory.create(board, bus);

        List<MoveLoggedEvent> received = new ArrayList<>();
        bus.subscribe(MoveLoggedEvent.class, received::add);

        gameController.handleClick(50, 50);   // select (0,0)
        gameController.handleClick(250, 50);  // (0,0) -> (0,2), onto the black rook

        assertEquals(1, received.size());
        assertTrue("a move onto an enemy-occupied square must be flagged as a capture",
                received.get(0).isCapture());
        assertEquals("Rxc8", received.get(0).getEntry().getNotation());
    }

    @Test
    public void jumpPerformedEventFiresOnSuccessfulJump() {
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.KNIGHT));
        GameController gameController = TestGameControllerFactory.create(board, bus);

        List<JumpPerformedEvent> received = new ArrayList<>();
        bus.subscribe(JumpPerformedEvent.class, received::add);

        gameController.handleJump(50, 50);

        assertEquals(1, received.size());
        assertEquals(Piece.Color.WHITE, received.get(0).getColor());
    }

    @Test
    public void gameEndedEventFiresWithWinnerOnKingCapture() {
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(0, 2, new Piece(Piece.Color.BLACK, Piece.Type.KING));
        GameController gameController = TestGameControllerFactory.create(board, bus);

        List<GameEndedEvent> received = new ArrayList<>();
        bus.subscribe(GameEndedEvent.class, received::add);

        gameController.handleClick(50, 50);
        gameController.handleClick(250, 50);
        gameController.advanceTime(2000);

        assertEquals(1, received.size());
        assertEquals(Piece.Color.WHITE, received.get(0).getWinner());
        assertTrue(gameController.getSnapshot().isGameOver());
    }
}

package org.example;

import org.example.controller.GameController;
import org.example.engine.GameSnapshot;
import org.example.engine.PieceSnapshot;
import org.example.model.Board;
import org.example.model.Piece;
import org.example.adapters.BoardParser;
import org.example.view.BoardInputListener;
import org.example.view.ImgRenderer;
import org.example.view.Renderer;

import org.junit.Before;
import org.junit.Test;

import javax.swing.JPanel;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for the UI boundary the architecture review flagged as untested:
 *
 * 1. GameSnapshot carries the pixel position a renderer needs, computed once
 *    by the engine layer (not re-derived by the view).
 * 2. Rendering a snapshot is read-only - it never mutates game state.
 * 3. A mouse click/right-click is forwarded to the controller with the
 *    correct (scale-corrected) coordinates - i.e. BoardInputListener really
 *    does route straight to GameController and nothing else.
 */
public class UiBoundaryTest {

    private Board board;

    @Before
    public void setUp() {
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
    }

    @Test
    public void snapshotContainsMovingPieceInterpolatedPixelPosition() {
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        GameController gc = TestGameControllerFactory.create(board);

        // (0,0) -> (0,2): 2 squares, 2000ms total travel.
        gc.handleClick(50, 50);
        gc.handleClick(250, 50);

        // 500ms into a 2000ms move is exactly 25% of the way there:
        // pixelX = 0 + (200 - 0) * 0.25 = 50.0
        gc.advanceTime(500);

        GameSnapshot snapshot = gc.getSnapshot();
        PieceSnapshot rook = snapshot.getPieces().stream()
                .filter(p -> p.getType() == Piece.Type.ROOK)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Rook not found in snapshot"));

        assertEquals(50.0, rook.getPixelX(), 0.001);
        assertEquals(0.0, rook.getPixelY(), 0.001);
    }

    @Test
    public void renderingSnapshotDoesNotMutateGameState() {
        board.setPiece(0, 0, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        board.setPiece(0, 1, new Piece(Piece.Color.BLACK, Piece.Type.ROOK));
        GameController gc = TestGameControllerFactory.create(board);

        // A capture, so score/history are non-trivial too.
        gc.handleClick(50, 50);
        gc.handleClick(150, 50);
        gc.advanceTime(1000);

        GameSnapshot before = gc.getSnapshot();
        int whiteScoreBefore = gc.getScore(Piece.Color.WHITE);
        int blackScoreBefore = gc.getScore(Piece.Color.BLACK);
        int historySizeBefore = gc.getMoveHistory().size();

        Renderer renderer = new ImgRenderer(Board.CELL_SIZE);
        renderer.render(before);
        renderer.render(before); // rendering twice must be equally harmless

        GameSnapshot after = gc.getSnapshot();
        assertEquals(whiteScoreBefore, gc.getScore(Piece.Color.WHITE));
        assertEquals(blackScoreBefore, gc.getScore(Piece.Color.BLACK));
        assertEquals(historySizeBefore, gc.getMoveHistory().size());
        assertEquals(before.getGameTimeMillis(), after.getGameTimeMillis());
        assertEquals(before.isGameOver(), after.isGameOver());
        assertEquals(before.getPieces().size(), after.getPieces().size());
        for (int i = 0; i < before.getPieces().size(); i++) {
            assertEquals(before.getPieces().get(i).getPixelX(), after.getPieces().get(i).getPixelX(), 0.001);
            assertEquals(before.getPieces().get(i).getPixelY(), after.getPieces().get(i).getPixelY(), 0.001);
        }
    }

    @Test
    public void mouseLeftClickIsForwardedToControllerHandleClick() {
        RecordingGameController controller = new RecordingGameController();
        boolean[] interacted = {false};
        BoardInputListener listener = new BoardInputListener(controller, () -> 1.0, () -> interacted[0] = true);

        listener.mousePressed(mouseEventAt(new JPanel(), 150, 50, MouseEvent.BUTTON1));

        assertEquals(150, controller.lastClickX);
        assertEquals(50, controller.lastClickY);
        assertEquals(-1, controller.lastJumpX);
        assertTrue("onInteraction should run after a click", interacted[0]);
    }

    @Test
    public void mouseRightClickIsForwardedToControllerHandleJump() {
        RecordingGameController controller = new RecordingGameController();
        BoardInputListener listener = new BoardInputListener(controller, () -> 1.0, () -> {});

        listener.mousePressed(mouseEventAt(new JPanel(), 250, 150, MouseEvent.BUTTON3));

        assertEquals(250, controller.lastJumpX);
        assertEquals(150, controller.lastJumpY);
        assertEquals(-1, controller.lastClickX);
    }

    @Test
    public void mouseClickCoordinatesAreDividedByCurrentDisplayScale() {
        RecordingGameController controller = new RecordingGameController();
        // A 0.5 display scale means the board is drawn at half size on
        // screen - a raw on-screen click at (100,60) must be converted back
        // to logical board coordinates (200,120) before reaching the
        // controller, exactly like BoardView draws at that scale.
        BoardInputListener listener = new BoardInputListener(controller, () -> 0.5, () -> {});

        listener.mousePressed(mouseEventAt(new JPanel(), 100, 60, MouseEvent.BUTTON1));

        assertEquals(200, controller.lastClickX);
        assertEquals(120, controller.lastClickY);
    }

    private static MouseEvent mouseEventAt(JPanel source, int x, int y, int button) {
        return new MouseEvent(source, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                0, x, y, 1, false, button);
    }

    /**
     * A GameController that records the coordinates it was called with
     * instead of doing anything real - lets this test verify BoardInputListener's
     * routing/scaling in total isolation from GameEngine/InteractionHandler.
     * Safe to construct with null collaborators because handleClick/handleJump
     * are fully overridden and never touch them.
     */
    private static final class RecordingGameController extends GameController {
        int lastClickX = -1;
        int lastClickY = -1;
        int lastJumpX = -1;
        int lastJumpY = -1;

        RecordingGameController() {
            super(null, null);
        }

        @Override
        public void handleClick(int x, int y) {
            lastClickX = x;
            lastClickY = y;
        }

        @Override
        public void handleJump(int x, int y) {
            lastJumpX = x;
            lastJumpY = y;
        }
    }
}

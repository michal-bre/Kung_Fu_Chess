package org.example.view;

import org.example.controller.GameController;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.DoubleSupplier;

import static javax.swing.SwingUtilities.isRightMouseButton;

/**
 * View layer: routes raw Swing mouse input on the board straight to the
 * controller layer, then asks the view to redraw so the result (a new
 * selection, a newly created move, a resolved jump) is visible immediately
 * instead of waiting for the next animation tick.
 *
 * Left-click maps to GameController.handleClick (select a piece / attempt a
 * move onto the clicked square) and right-click maps to
 * GameController.handleJump - the same two actions the CLI already exposes
 * as separate CLICK/JUMP commands, now driven by mouse buttons instead of
 * typed text.
 *
 * Pixel coordinates are divided by BoardView's display scale before being
 * forwarded: BoardView renders the board in a fixed LOGICAL pixel space
 * (board.getWidth()*CELL_SIZE x board.getHeight()*CELL_SIZE) and then
 * shrinks that to fit the screen if needed (see BoardView's class doc), so
 * a raw MouseEvent coordinate is in the SCALED, on-screen space - it has to
 * be converted back to logical space here before it matches the
 * row = y / CELL_SIZE, col = x / CELL_SIZE math InteractionHandler uses.
 * When the board fits the screen at native size, scale is 1.0 and this is a
 * no-op.
 *
 * The scale is read via a DoubleSupplier - boardView::getScale - rather than
 * captured as a fixed value at construction time, because BoardView's scale
 * can still change AFTER this listener is built: GameWindow's second pack()
 * pass (see its class doc) may call BoardView.constrainToContentArea and
 * shrink it further once the frame's real chrome size is known, which
 * normally happens after this listener already exists. Reading the scale
 * fresh on every click means it always reflects BoardView's final, real
 * on-screen size, not whatever it happened to be during construction.
 */
public class BoardInputListener extends MouseAdapter {

    private final GameController gameController;
    private final DoubleSupplier scale;
    private final Runnable onInteraction;

    public BoardInputListener(GameController gameController, DoubleSupplier scale, Runnable onInteraction) {
        this.gameController = gameController;
        this.scale = scale;
        this.onInteraction = onInteraction;
    }

    @Override
    public void mousePressed(MouseEvent e) {
        double currentScale = scale.getAsDouble();
        int logicalX = (int) Math.round(e.getX() / currentScale);
        int logicalY = (int) Math.round(e.getY() / currentScale);

        if (isRightMouseButton(e)) {
            gameController.handleJump(logicalX, logicalY);
        } else {
            gameController.handleClick(logicalX, logicalY);
        }
        onInteraction.run();
    }
}

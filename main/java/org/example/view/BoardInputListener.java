package org.example.view;

import org.example.controller.GameController;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

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
 * Pixel coordinates are passed straight through unmodified: BoardView
 * already renders the board stretched to exactly
 * board.getWidth()*CELL_SIZE x board.getHeight()*CELL_SIZE, matching the
 * row = y / CELL_SIZE, col = x / CELL_SIZE math InteractionHandler uses, so
 * no translation is needed here.
 */
public class BoardInputListener extends MouseAdapter {

    private final GameController gameController;
    private final Runnable onInteraction;

    public BoardInputListener(GameController gameController, Runnable onInteraction) {
        this.gameController = gameController;
        this.onInteraction = onInteraction;
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (isRightMouseButton(e)) {
            gameController.handleJump(e.getX(), e.getY());
        } else {
            gameController.handleClick(e.getX(), e.getY());
        }
        onInteraction.run();
    }
}

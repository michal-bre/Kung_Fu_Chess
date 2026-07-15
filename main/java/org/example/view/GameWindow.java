package org.example.view;

import javax.swing.*;
import java.awt.*;

/**
 * View layer: the top-level window that hosts the game's view components
 * (currently just BoardView; future phases add overlays without changing
 * this class).
 *
 * Kept separate from BoardView on purpose: BoardView only knows how to draw
 * the board itself, while GameWindow only knows how to host a component in
 * a native window (title, close behavior, sizing, centering). Splitting
 * "what to draw" from "how to present a window" keeps each class doing one
 * job, matching the rest of this codebase's layering.
 *
 * Sizing is a two-pass process. pack() sizes the frame from the content's
 * getPreferredSize(), but a content component picks that preferred size
 * BEFORE any native window exists - it can only guess how much extra room
 * the title bar, borders, and OS taskbar/dock will need, and that guess
 * varies by OS, theme, and DPI scaling. The first pack() below forces the
 * native peer to exist, which makes frame.getInsets() return the frame's
 * REAL chrome size; combined with the screen's real work area (via
 * Toolkit's screen insets, which already account for the taskbar/dock),
 * that gives an exact answer for how much space the content may actually
 * occupy. If the content implements ScreenFittable, it's given a chance to
 * shrink to that exact number and the frame is packed a second time - so a
 * content component that started out too big to fit the screen ends up
 * fitting for real, not just by estimate.
 */
public class GameWindow {

    private final JFrame frame;

    public GameWindow(String title, JComponent content) {
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.getContentPane().add(content);
        frame.pack();

        if (content instanceof ScreenFittable) {
            Rectangle available = resolveAvailableContentArea(frame);
            if (available != null) {
                ((ScreenFittable) content).constrainToContentArea(available.width, available.height);
                frame.pack();
            }
        }

        frame.setLocationRelativeTo(null);
    }

    /**
     * The real pixel area the frame's content may occupy: the screen's work
     * area (already excluding the taskbar/dock) minus this specific frame's
     * real insets (title bar, borders) - both only knowable after pack()
     * has created the native peer. Returns null rather than throwing if run
     * headless (e.g. under a test harness with no display), matching
     * BoardView's own headless fallback.
     */
    private static Rectangle resolveAvailableContentArea(JFrame frame) {
        try {
            GraphicsConfiguration gc = frame.getGraphicsConfiguration();
            if (gc == null) return null;

            Rectangle screenBounds = gc.getBounds();
            Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            Insets frameInsets = frame.getInsets();

            int availableWidth = screenBounds.width - screenInsets.left - screenInsets.right
                    - frameInsets.left - frameInsets.right;
            int availableHeight = screenBounds.height - screenInsets.top - screenInsets.bottom
                    - frameInsets.top - frameInsets.bottom;

            return new Rectangle(0, 0, Math.max(0, availableWidth), Math.max(0, availableHeight));
        } catch (HeadlessException e) {
            return null;
        }
    }

    public void show() {
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }
}

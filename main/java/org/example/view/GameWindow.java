package org.example.view;

import javax.swing.*;

/**
 * View layer: the top-level window that hosts the game's view components
 * (currently just BoardView; future phases add overlays such as selection
 * highlights without changing this class).
 *
 * Kept separate from BoardView on purpose: BoardView only knows how to draw
 * the board itself, while GameWindow only knows how to host a component in
 * a native window (title, close behavior, sizing, centering). Splitting
 * "what to draw" from "how to present a window" keeps each class doing one
 * job, matching the rest of this codebase's layering.
 */
public class GameWindow {

    private final JFrame frame;

    public GameWindow(String title, JComponent content) {
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.getContentPane().add(content);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    public void show() {
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }
}

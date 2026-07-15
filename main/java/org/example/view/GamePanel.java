package org.example.view;

import org.example.controller.GameController;
import org.example.controller.MoveHistoryEntry;
import org.example.model.Piece;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * View layer: top-level composite that arranges the board together with a
 * running per-side score and a move-history table on either side, matching
 * a standard two-player game-window layout (score above/below the board,
 * one side's move log to its left, the other's to its right).
 *
 * Kept separate from BoardView on purpose, same reasoning as BoardView vs.
 * GameWindow: BoardView only knows how to draw the board itself, and this
 * class only knows how to arrange peripheral panels around it - neither one
 * has to know about the other's internals.
 *
 * Score and move history are both purely derived, read-only views over
 * GameController state (GameController.getScore, GameController.
 * getMoveHistory) - this class never mutates game state, and never touches
 * GameEngine/EnginePort/Board directly, only GameController - and only
 * refreshes its own display from it once per GameLoop tick (see refresh(),
 * called alongside boardView.repaint() from GuiMain).
 */
public class GamePanel extends JPanel implements ScreenFittable {

    private static final int HISTORY_PANEL_WIDTH = 220;
    private static final int SCORE_LABEL_HEIGHT = 34;
    private static final Font SCORE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 16);

    private final BoardView boardView;
    private final GameController gameController;

    private final JLabel blackScoreLabel = new JLabel("Black — Score: 0", SwingConstants.CENTER);
    private final JLabel whiteScoreLabel = new JLabel("White — Score: 0", SwingConstants.CENTER);

    private final DefaultTableModel blackHistoryModel = newHistoryModel();
    private final DefaultTableModel whiteHistoryModel = newHistoryModel();

    // How many of GameController.getMoveHistory()'s entries (for each color)
    // have already been appended to the corresponding table - so refresh()
    // only ever appends new rows instead of rebuilding the table from
    // scratch every tick.
    private int blackHistoryRowsShown = 0;
    private int whiteHistoryRowsShown = 0;

    public GamePanel(BoardView boardView, GameController gameController) {
        this.boardView = boardView;
        this.gameController = gameController;

        setLayout(new BorderLayout());
        setOpaque(true);
        setBackground(Color.DARK_GRAY);

        blackScoreLabel.setFont(SCORE_FONT);
        whiteScoreLabel.setFont(SCORE_FONT);
        blackScoreLabel.setForeground(Color.WHITE);
        whiteScoreLabel.setForeground(Color.WHITE);
        blackScoreLabel.setPreferredSize(new Dimension(10, SCORE_LABEL_HEIGHT));
        whiteScoreLabel.setPreferredSize(new Dimension(10, SCORE_LABEL_HEIGHT));

        add(blackScoreLabel, BorderLayout.NORTH);
        add(whiteScoreLabel, BorderLayout.SOUTH);
        add(boardView, BorderLayout.CENTER);
        add(historyPanel("Black", blackHistoryModel), BorderLayout.WEST);
        add(historyPanel("White", whiteHistoryModel), BorderLayout.EAST);
    }

    private static DefaultTableModel newHistoryModel() {
        return new DefaultTableModel(new Object[]{"Time", "Move"}, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
    }

    private static JComponent historyPanel(String title, DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowSelectionAllowed(false);
        table.setEnabled(false);

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(HISTORY_PANEL_WIDTH, 10));

        JPanel panel = new JPanel(new BorderLayout());
        JLabel heading = new JLabel(title, SwingConstants.CENTER);
        heading.setFont(SCORE_FONT);
        panel.add(heading, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Refreshes the score labels and appends any move-history rows created
     * since the last call. Cheap to call every GameLoop tick: the score
     * labels are just text updates, and history only ever grows by
     * appending rows for moves not already shown, never rebuilding the
     * whole table.
     */
    public void refresh() {
        blackScoreLabel.setText("Black — Score: " + gameController.getScore(Piece.Color.BLACK));
        whiteScoreLabel.setText("White — Score: " + gameController.getScore(Piece.Color.WHITE));

        List<MoveHistoryEntry> history = gameController.getMoveHistory();
        int blackSeen = 0;
        int whiteSeen = 0;
        for (MoveHistoryEntry entry : history) {
            if (entry.getColor() == Piece.Color.BLACK) {
                blackSeen++;
                if (blackSeen > blackHistoryRowsShown) {
                    blackHistoryModel.addRow(new Object[]{formatTime(entry.getTimeMillis()), entry.getNotation()});
                }
            } else {
                whiteSeen++;
                if (whiteSeen > whiteHistoryRowsShown) {
                    whiteHistoryModel.addRow(new Object[]{formatTime(entry.getTimeMillis()), entry.getNotation()});
                }
            }
        }
        blackHistoryRowsShown = blackSeen;
        whiteHistoryRowsShown = whiteSeen;
    }

    private static String formatTime(long millis) {
        long totalMillis = Math.max(0, millis);
        long minutes = totalMillis / 60_000;
        long seconds = (totalMillis / 1000) % 60;
        long fraction = totalMillis % 1000;
        return String.format("%02d:%02d.%03d", minutes, seconds, fraction);
    }

    /**
     * Delegates to boardView.constrainToContentArea with the side panels'
     * and score labels' own space subtracted out first, so the BOARD itself
     * - not the whole window, which BorderLayout will still happily grow
     * past the given bounds - is what actually ends up fitting the screen.
     * See BoardView's and GameWindow's class docs for why this two-pass
     * fitting exists at all.
     */
    @Override
    public void constrainToContentArea(int maxWidth, int maxHeight) {
        int reservedWidth = 2 * HISTORY_PANEL_WIDTH;
        int reservedHeight = 2 * SCORE_LABEL_HEIGHT;
        boardView.constrainToContentArea(
                Math.max(0, maxWidth - reservedWidth),
                Math.max(0, maxHeight - reservedHeight));
    }
}

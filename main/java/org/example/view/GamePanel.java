package org.example.view;

import org.example.controller.GameController;
import org.example.controller.MoveHistoryEntry;
import org.example.model.Piece;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
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
 *
 * Visual language: a dark "card" theme with a warm gold accent for White
 * and a cool blue accent for Black, so each side's score bar and history
 * panel are identifiable at a glance without relying on piece color alone
 * (useful since the board itself is a checkerboard, not a colored panel).
 */
public class GamePanel extends JPanel implements ScreenFittable {

    private static final int HISTORY_PANEL_WIDTH = 220;
    private static final int SCORE_LABEL_HEIGHT = 40;

    private static final Color BG_DARK = new Color(24, 24, 28);
    private static final Color CARD_BG = new Color(34, 34, 40);
    private static final Color HEADER_BG = new Color(46, 46, 54);
    private static final Color ROW_EVEN = new Color(34, 34, 40);
    private static final Color ROW_ODD = new Color(40, 40, 47);
    private static final Color TEXT_PRIMARY = new Color(235, 235, 240);
    private static final Color TEXT_MUTED = new Color(150, 150, 160);
    private static final Color WHITE_ACCENT = new Color(230, 200, 120);
    private static final Color BLACK_ACCENT = new Color(120, 170, 230);

    private static final Font SCORE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 17);
    private static final Font HEADING_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 14);
    private static final Font TABLE_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    private static final Font HEADER_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);

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
        setBackground(BG_DARK);

        add(scoreBar(blackScoreLabel, BLACK_ACCENT, false), BorderLayout.NORTH);
        add(scoreBar(whiteScoreLabel, WHITE_ACCENT, true), BorderLayout.SOUTH);
        add(boardView, BorderLayout.CENTER);
        add(historyCard("Black", blackHistoryModel, BLACK_ACCENT), BorderLayout.WEST);
        add(historyCard("White", whiteHistoryModel, WHITE_ACCENT), BorderLayout.EAST);
    }

    private static DefaultTableModel newHistoryModel() {
        return new DefaultTableModel(new Object[]{"Time", "Move"}, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
    }

    /**
     * A full-width bar for one side's running score, with a thin accent
     * border on the edge facing the board - so the accent color reads as
     * "belonging" to the board between the two bars rather than just
     * floating decoration.
     */
    private static JPanel scoreBar(JLabel label, Color accent, boolean accentOnTop) {
        label.setFont(SCORE_FONT);
        label.setForeground(TEXT_PRIMARY);
        label.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(HEADER_BG);
        bar.setPreferredSize(new Dimension(10, SCORE_LABEL_HEIGHT));
        bar.setBorder(accentOnTop
                ? BorderFactory.createMatteBorder(3, 0, 0, 0, accent)
                : BorderFactory.createMatteBorder(0, 0, 3, 0, accent));
        bar.add(label, BorderLayout.CENTER);
        return bar;
    }

    /**
     * One side's move-history table, wrapped in a dark card with a
     * colored, all-caps heading and a striped/borderless table - built
     * fresh per side (rather than shared static styling helpers) so each
     * side's accent color can be baked into its own header/heading
     * renderers.
     */
    private static JComponent historyCard(String title, DefaultTableModel model, Color accent) {
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowSelectionAllowed(false);
        table.setEnabled(false);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setRowHeight(24);
        table.setFont(TABLE_FONT);
        table.setBackground(CARD_BG);
        table.setForeground(TEXT_PRIMARY);
        table.setDefaultRenderer(Object.class, new StripedCellRenderer());

        JTableHeader header = table.getTableHeader();
        header.setDefaultRenderer(new HistoryHeaderRenderer(accent));
        header.setPreferredSize(new Dimension(10, 26));
        header.setReorderingAllowed(false);
        header.setResizingAllowed(false);

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(HISTORY_PANEL_WIDTH, 10));
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(CARD_BG);

        JLabel heading = new JLabel(title.toUpperCase(), SwingConstants.CENTER);
        heading.setFont(HEADING_FONT);
        heading.setForeground(accent);
        heading.setBorder(BorderFactory.createEmptyBorder(10, 8, 8, 8));

        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(CARD_BG);
        card.add(heading, BorderLayout.NORTH);
        card.add(scrollPane, BorderLayout.CENTER);

        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(BG_DARK);
        outer.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        outer.add(card, BorderLayout.CENTER);
        return outer;
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

    /** Alternating row shading, muted "Time" column, brighter left-aligned "Move" column - a plain default JTable renderer would otherwise paint every cell the same flat white. */
    private static final class StripedCellRenderer extends DefaultTableCellRenderer {
        StripedCellRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                         boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            label.setBackground(row % 2 == 0 ? ROW_EVEN : ROW_ODD);
            label.setForeground(column == 0 ? TEXT_MUTED : TEXT_PRIMARY);
            label.setHorizontalAlignment(column == 0 ? SwingConstants.CENTER : SwingConstants.LEFT);
            label.setBorder(BorderFactory.createEmptyBorder(0, column == 0 ? 0 : 10, 0, 6));
            return label;
        }
    }

    /** Table header painted in the card's dark palette with a bottom accent rule in the side's own color, instead of the default light-gray Swing header that would otherwise clash with the dark card around it. */
    private static final class HistoryHeaderRenderer extends DefaultTableCellRenderer {
        private final Color accent;

        HistoryHeaderRenderer(Color accent) {
            this.accent = accent;
            setHorizontalAlignment(SwingConstants.CENTER);
            setFont(HEADER_FONT);
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                         boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            label.setBackground(HEADER_BG);
            label.setForeground(TEXT_PRIMARY);
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, accent),
                    BorderFactory.createEmptyBorder(4, 4, 4, 4)));
            return label;
        }
    }
}

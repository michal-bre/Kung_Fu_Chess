package org.example.net.client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * A live, dark-themed list of everyone currently in this player's room and
 * their role - "Alice — White", "Bob — Black", "Carol — Viewer" - answering
 * exactly what was asked for: not just "what am I", but who everyone else in
 * the room is too. Fed entirely by GameClient.getRoster()/setRosterListener
 * (see Room.buildRoster on the server side for where this data actually
 * comes from), the same "listener fires, caller re-reads the latest snapshot"
 * pattern RoomLobbyDialog already uses for the room list.
 *
 * Network-client-only, deliberately not part of GamePanel: local hot-seat
 * mode has no concept of remote spectators or room membership at all, and
 * GamePanel's own class doc is explicit about staying decoupled from
 * anything net.client-specific - see NetworkGameLayout, which composes this
 * panel alongside an unmodified GamePanel instead.
 */
public final class RosterPanel extends JPanel {

    private static final Color BG_DARK = new Color(24, 24, 28);
    private static final Color CARD_BG = new Color(34, 34, 40);
    private static final Color TEXT_PRIMARY = new Color(235, 235, 240);
    private static final Color TEXT_MUTED = new Color(150, 150, 160);
    private static final Color WHITE_ACCENT = new Color(230, 200, 120);
    private static final Color BLACK_ACCENT = new Color(120, 170, 230);
    private static final Color DISCONNECTED_ACCENT = new Color(220, 110, 110);

    private static final int PANEL_WIDTH = 200;

    private final JPanel rowsContainer = new JPanel();

    public RosterPanel() {
        setLayout(new BorderLayout());
        setBackground(BG_DARK);
        setBorder(new EmptyBorder(8, 8, 8, 8));

        JLabel heading = new JLabel("IN THIS ROOM", SwingConstants.CENTER);
        heading.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        heading.setForeground(TEXT_PRIMARY);
        heading.setBorder(new EmptyBorder(10, 8, 8, 8));
        heading.setBackground(CARD_BG);
        heading.setOpaque(true);

        rowsContainer.setLayout(new BoxLayout(rowsContainer, BoxLayout.Y_AXIS));
        rowsContainer.setBackground(CARD_BG);

        JScrollPane scrollPane = new JScrollPane(rowsContainer);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(CARD_BG);
        scrollPane.setPreferredSize(new Dimension(PANEL_WIDTH, 10));

        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(CARD_BG);
        card.add(heading, BorderLayout.NORTH);
        card.add(scrollPane, BorderLayout.CENTER);

        add(card, BorderLayout.CENTER);
        showEmptyState();
    }

    /** How much horizontal space this panel needs, including its own border - see NetworkGameLayout.constrainToContentArea, which subtracts exactly this from the board's available width. */
    public int getReservedWidth() {
        return PANEL_WIDTH + 16;
    }

    /** Rebuilds the roster rows from scratch - safe to call from any thread (e.g. directly from a GameClient network callback); hops to the EDT itself rather than requiring the caller to remember to. */
    public void refresh(List<GameClient.RosterEntry> entries) {
        SwingUtilities.invokeLater(() -> {
            rowsContainer.removeAll();
            if (entries.isEmpty()) {
                showEmptyState();
            } else {
                for (GameClient.RosterEntry entry : entries) {
                    rowsContainer.add(row(entry));
                }
            }
            rowsContainer.revalidate();
            rowsContainer.repaint();
        });
    }

    private void showEmptyState() {
        JLabel placeholder = new JLabel("Nobody here yet");
        placeholder.setForeground(TEXT_MUTED);
        placeholder.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        placeholder.setBorder(new EmptyBorder(8, 8, 8, 8));
        rowsContainer.add(placeholder);
    }

    private JComponent row(GameClient.RosterEntry entry) {
        Color accent;
        String roleText;
        switch (entry.getRole()) {
            case "WHITE":
                accent = WHITE_ACCENT;
                roleText = "White";
                break;
            case "BLACK":
                accent = BLACK_ACCENT;
                roleText = "Black";
                break;
            default:
                accent = TEXT_MUTED;
                roleText = "Viewer";
        }
        String suffix = entry.isDisconnected() ? " (reconnecting…)" : "";
        if (entry.isDisconnected()) {
            accent = DISCONNECTED_ACCENT;
        }

        JLabel label = new JLabel(entry.getUsername() + " — " + roleText + suffix);
        label.setForeground(accent);
        label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        label.setBorder(new EmptyBorder(6, 10, 6, 10));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }
}

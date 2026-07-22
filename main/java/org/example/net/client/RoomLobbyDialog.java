package org.example.net.client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * The Create/Join/Cancel room lobby - a modal, dark-themed dialog shown
 * after shell login and before the game window opens (Phase 5, CTD 26 spec
 * slide 5). Dark theme deliberately matches GamePanel's established
 * palette (dark "card" background, gold accent) rather than introducing a
 * new visual language, per the user's explicit "dark-themed" styling choice
 * made when this phase was originally planned.
 *
 * Replaces Phase 4's silent auto-queue-on-LOGIN behavior: a player now
 * explicitly creates a new room (becoming its White seat) or joins an
 * existing one from a live list (taking the open seat, or spectating if
 * both are already taken - see Room's class doc). The room list is kept
 * current via GameClient.setRoomListListener, which fires on every
 * ROOM_LIST the server pushes (on connect, and after anything that changes
 * a room's status).
 *
 * Phase 7 adds the CTD 26 spec's other Home-screen option (slide 6): a
 * "Play" button alongside Room, not instead of it - clicking it starts an
 * ELO +-100 quick-match search (see GameServer.handleFindMatch) rather than
 * the manual create/join flow. While searching, Create/Join/the room table
 * are disabled and a "Cancel Search" button takes Play's place; a match
 * found this way still ends in the exact same COLOR_ASSIGNED message a
 * CREATE_ROOM/JOIN_ROOM match would, so the poller below that already
 * closes this dialog on a color assignment needed no changes at all to
 * handle it - Play and Room both funnel into the same "you're now seated"
 * signal.
 *
 * This class has no state of its own beyond the dialog widgets themselves -
 * every fact it displays or acts on (the room list, whether a room was
 * actually joined, the last server error) already lives on GameClient; this
 * is purely a view over it, matching how BoardView/GamePanel relate to
 * GameSnapshot/EventBus elsewhere in the networked client.
 */
public final class RoomLobbyDialog {

    private static final Color BG_DARK = new Color(24, 24, 28);
    private static final Color CARD_BG = new Color(34, 34, 40);
    private static final Color HEADER_BG = new Color(46, 46, 54);
    private static final Color TEXT_PRIMARY = new Color(235, 235, 240);
    private static final Color TEXT_MUTED = new Color(150, 150, 160);
    private static final Color GOLD_ACCENT = new Color(230, 200, 120);
    private static final Color BLUE_ACCENT = new Color(120, 170, 230);
    private static final Color ERROR_ACCENT = new Color(220, 110, 110);

    private RoomLobbyDialog() {
    }

    /**
     * Shows the lobby dialog and blocks the calling thread until the player
     * either successfully creates/joins a room (detected by polling
     * gameClient.getMyColor()/getConnectionStatus() - see the poller Timer
     * below) or clicks Cancel / closes the window. Returns true if a room
     * was joined (the caller should proceed to open the game window), false
     * if the player cancelled (the caller should exit).
     */
    public static boolean show(GameClient gameClient, String username) {
        JDialog dialog = new JDialog((Frame) null, "Kung Fu Chess — Lobby", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.getContentPane().setBackground(BG_DARK);
        dialog.setLayout(new BorderLayout());

        boolean[] joined = {false};

        JLabel heading = new JLabel("WELCOME, " + username.toUpperCase());
        heading.setForeground(GOLD_ACCENT);
        heading.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        heading.setBorder(new EmptyBorder(14, 16, 4, 16));
        dialog.add(heading, BorderLayout.NORTH);

        DefaultTableModel model = new DefaultTableModel(new Object[]{"Room", "Status", "Spectators"}, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
        JTable table = new JTable(model);
        table.setBackground(CARD_BG);
        table.setForeground(TEXT_PRIMARY);
        table.setSelectionBackground(HEADER_BG);
        table.setSelectionForeground(GOLD_ACCENT);
        table.setGridColor(HEADER_BG);
        table.setRowHeight(24);
        table.getTableHeader().setBackground(HEADER_BG);
        table.getTableHeader().setForeground(TEXT_PRIMARY);

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.getViewport().setBackground(CARD_BG);
        scrollPane.setPreferredSize(new Dimension(420, 200));
        scrollPane.setBorder(BorderFactory.createLineBorder(HEADER_BG));

        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(BG_DARK);
        center.setBorder(new EmptyBorder(6, 16, 8, 16));
        center.add(scrollPane, BorderLayout.CENTER);
        dialog.add(center, BorderLayout.CENTER);

        JTextField nameField = new JTextField();
        nameField.setBackground(CARD_BG);
        nameField.setForeground(TEXT_PRIMARY);
        nameField.setCaretColor(TEXT_PRIMARY);
        nameField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(HEADER_BG), new EmptyBorder(5, 8, 5, 8)));

        JButton createButton = darkButton("Create Room", GOLD_ACCENT);
        JButton joinButton = darkButton("Join Selected", BLUE_ACCENT);
        JButton cancelButton = darkButton("Cancel", TEXT_MUTED);
        JButton playButton = darkButton("Play (Quick Match)", GOLD_ACCENT);
        JButton cancelSearchButton = darkButton("Cancel Search", ERROR_ACCENT);
        joinButton.setEnabled(false);
        cancelSearchButton.setVisible(false);

        JLabel statusLabel = new JLabel(" ");
        statusLabel.setForeground(ERROR_ACCENT);
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));

        table.getSelectionModel().addListSelectionListener(e -> joinButton.setEnabled(table.getSelectedRow() >= 0));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    joinButton.doClick();
                }
            }
        });

        JPanel quickMatchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        quickMatchRow.setBackground(BG_DARK);
        quickMatchRow.add(playButton);
        quickMatchRow.add(cancelSearchButton);

        JPanel createRow = new JPanel(new BorderLayout(8, 0));
        createRow.setBackground(BG_DARK);
        createRow.add(nameField, BorderLayout.CENTER);
        createRow.add(createButton, BorderLayout.EAST);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttonRow.setBackground(BG_DARK);
        buttonRow.add(joinButton);
        buttonRow.add(cancelButton);

        JPanel south = new JPanel(new GridLayout(4, 1, 0, 6));
        south.setBackground(BG_DARK);
        south.setBorder(new EmptyBorder(4, 16, 14, 16));
        south.add(quickMatchRow);
        south.add(createRow);
        south.add(buttonRow);
        south.add(statusLabel);
        dialog.add(south, BorderLayout.SOUTH);

        Runnable refreshRooms = () -> SwingUtilities.invokeLater(() -> {
            model.setRowCount(0);
            for (GameClient.RoomSummary room : gameClient.getRoomList()) {
                model.addRow(new Object[]{room.getId(), formatStatus(room.getStatus()), room.getSpectators()});
            }
        });
        gameClient.setRoomListListener(refreshRooms);
        refreshRooms.run();

        createButton.addActionListener(e -> gameClient.sendCreateRoom(nameField.getText().trim()));
        joinButton.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            gameClient.sendJoinRoom((String) model.getValueAt(row, 0));
        });

        Runnable enterSearchingState = () -> {
            playButton.setVisible(false);
            cancelSearchButton.setVisible(true);
            createButton.setEnabled(false);
            joinButton.setEnabled(false);
            nameField.setEnabled(false);
            table.setEnabled(false);
        };
        Runnable exitSearchingState = () -> {
            playButton.setVisible(true);
            cancelSearchButton.setVisible(false);
            createButton.setEnabled(true);
            joinButton.setEnabled(table.getSelectedRow() >= 0);
            nameField.setEnabled(true);
            table.setEnabled(true);
            statusLabel.setForeground(ERROR_ACCENT);
            statusLabel.setText(" ");
        };
        playButton.addActionListener(e -> {
            gameClient.sendFindMatch();
            enterSearchingState.run();
        });
        cancelSearchButton.addActionListener(e -> {
            gameClient.sendCancelMatch();
            exitSearchingState.run();
        });

        cancelButton.addActionListener(e -> {
            gameClient.sendCancelMatch(); // harmless no-op unless a search was actually in progress
            dialog.setVisible(false);
            dialog.dispose();
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                gameClient.sendCancelMatch();
                dialog.setVisible(false);
                dialog.dispose();
            }
        });

        // Polls rather than reacting to a dedicated "joined" event, since
        // COLOR_ASSIGNED/SPECTATING are already fully handled by GameClient
        // for every OTHER purpose (setting myColor, the status line) -
        // adding a third listener callback just for this dialog's one-time
        // "close yourself now" signal would be more plumbing than a simple
        // ~150ms poll of state that already exists.
        String[] lastError = {null};
        Timer poller = new Timer(150, null);
        poller.addActionListener(e -> {
            if (gameClient.getLastError() != null && !gameClient.getLastError().equals(lastError[0])) {
                lastError[0] = gameClient.getLastError();
                statusLabel.setForeground(ERROR_ACCENT);
                statusLabel.setText(lastError[0]);
            }

            if (cancelSearchButton.isVisible() && gameClient.getConnectionStatus() != null) {
                statusLabel.setForeground(TEXT_MUTED);
                statusLabel.setText(gameClient.getConnectionStatus());
            }

            if (gameClient.getMatchNotFoundMessage() != null) {
                String message = gameClient.getMatchNotFoundMessage();
                gameClient.clearMatchNotFound();
                exitSearchingState.run();
                JOptionPane.showMessageDialog(dialog, message, "No match found", JOptionPane.INFORMATION_MESSAGE);
            }

            boolean spectating = gameClient.getConnectionStatus() != null
                    && gameClient.getConnectionStatus().startsWith("Spectating");
            if (gameClient.getMyColor() != null || spectating) {
                joined[0] = true;
                poller.stop();
                dialog.setVisible(false);
                dialog.dispose();
            }
        });
        poller.start();

        dialog.setSize(460, 420);
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true); // blocks here until dialog.dispose()

        poller.stop();
        gameClient.setRoomListListener(null);
        return joined[0];
    }

    private static String formatStatus(String status) {
        if (status == null) return "";
        switch (status) {
            case "WAITING_FOR_OPPONENT":
                return "Waiting for opponent";
            case "IN_PROGRESS":
                return "In progress";
            case "FINISHED":
                return "Finished";
            default:
                return status;
        }
    }

    private static JButton darkButton(String text, Color accent) {
        JButton button = new JButton(text);
        button.setBackground(HEADER_BG);
        button.setForeground(accent);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accent, 1),
                new EmptyBorder(6, 14, 6, 14)));
        return button;
    }
}

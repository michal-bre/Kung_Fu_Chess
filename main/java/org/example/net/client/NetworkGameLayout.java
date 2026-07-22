package org.example.net.client;

import org.example.view.GamePanel;
import org.example.view.ScreenFittable;

import javax.swing.*;
import java.awt.*;

/**
 * Composes GamePanel (completely unmodified, still shared with local
 * hot-seat mode) together with RosterPanel (network-only) into one component
 * GameWindow can host - the alternative would have been teaching GamePanel
 * about RosterPanel/GameClient directly, which GamePanel's own class doc
 * explicitly avoids (local mode has no concept of remote spectators or room
 * membership at all, so it would have nothing to feed a roster with).
 *
 * Implements ScreenFittable itself, delegating to GamePanel with
 * RosterPanel's own reserved width subtracted first - the exact same
 * "reserve this side panel's width, pass the rest through" pattern GamePanel
 * already uses for its own two move-history side panels relative to
 * BoardView (see GamePanel.constrainToContentArea). Without this, GameWindow
 * would only ever see GamePanel directly and RosterPanel wouldn't exist from
 * its point of view at all - wrapping the two together and re-implementing
 * ScreenFittable here is what makes the screen-fitting pass still shrink the
 * actual board by the right amount instead of running out of room to place
 * the roster column.
 */
public final class NetworkGameLayout extends JPanel implements ScreenFittable {

    private final GamePanel gamePanel;
    private final RosterPanel rosterPanel;

    public NetworkGameLayout(GamePanel gamePanel, RosterPanel rosterPanel) {
        this.gamePanel = gamePanel;
        this.rosterPanel = rosterPanel;

        setLayout(new BorderLayout());
        add(gamePanel, BorderLayout.CENTER);
        add(rosterPanel, BorderLayout.EAST);
    }

    @Override
    public void constrainToContentArea(int maxWidth, int maxHeight) {
        gamePanel.constrainToContentArea(Math.max(0, maxWidth - rosterPanel.getReservedWidth()), maxHeight);
    }
}

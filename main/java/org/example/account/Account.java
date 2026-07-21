package org.example.account;

/**
 * A player's persistent profile: username plus running ELO rating and game
 * totals. Immutable value object - every "change" (findOrCreateAccount,
 * recordGameResult) goes through AccountRepository and returns/persists a
 * fresh Account rather than mutating one in place, matching how Piece and
 * GameSnapshot are treated elsewhere in this project.
 *
 * Lives in its own top-level package (org.example.account) rather than
 * inside model/engine/controller: it isn't part of the chess rules or
 * real-time arbiter at all - the local hot-seat game (GuiMain) has no
 * concept of accounts and never will, since there's no persistence or
 * networking involved there. This package is purely a networked-server
 * concern, alongside net.server, and depends on nothing from
 * model/engine/rules/controller.
 */
public final class Account {
    private final String username;
    private final int elo;
    private final int gamesPlayed;
    private final int wins;
    private final int losses;

    public Account(String username, int elo, int gamesPlayed, int wins, int losses) {
        this.username = username;
        this.elo = elo;
        this.gamesPlayed = gamesPlayed;
        this.wins = wins;
        this.losses = losses;
    }

    public String getUsername() {
        return username;
    }

    public int getElo() {
        return elo;
    }

    public int getGamesPlayed() {
        return gamesPlayed;
    }

    public int getWins() {
        return wins;
    }

    public int getLosses() {
        return losses;
    }
}

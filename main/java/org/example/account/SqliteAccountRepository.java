package org.example.account;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The real, persistent AccountRepository - a single-table SQLite database
 * (accounts: username TEXT PRIMARY KEY, elo INTEGER, games_played INTEGER,
 * wins INTEGER, losses INTEGER), created automatically on first use.
 *
 * Written against ONLY java.sql (Connection/DriverManager/PreparedStatement/
 * ResultSet - part of the JDK itself, module java.sql) and never references
 * any org.sqlite.* class directly, so this file compiles with no SQLite jar
 * on the classpath at all. A JDBC driver jar is still required at RUNTIME
 * though - DriverManager.getConnection("jdbc:sqlite:...") has nothing to
 * hand that URL to without one. A driver jar could not be sourced inside
 * the sandbox this project's tooling runs in (no working internet access to
 * Maven Central, and no bundled copy found anywhere on that sandbox's
 * filesystem, unlike Java-WebSocket - see the project's Phase 2 notes); a
 * modern sqlite-jdbc jar (e.g. org.xerial:sqlite-jdbc) needs to be placed in
 * lib/ on whatever machine actually RUNS ServerMain. Once it's on the
 * classpath, no other code needs to change: JDBC 4+ drivers self-register
 * via META-INF/services, so DriverManager finds it automatically.
 *
 * One Connection per method call (not a held-open field) - this project has
 * no request volume anywhere close to where connection pooling would matter
 * (one query per login, one write per game-ended event), and it sidesteps
 * ever having to worry about a stale/broken connection surviving across
 * calls. Every method is synchronized: SQLite itself serializes writers at
 * the file level regardless, and GameServer's own call sites already expect
 * these calls to be safe to invoke from arbitrary I/O threads (see
 * GameServer's class doc on engineLock and the "runs while holding
 * engineLock" note on its onGameEnded handler).
 */
public final class SqliteAccountRepository implements AccountRepository {

    private final String jdbcUrl;

    public SqliteAccountRepository(String databaseFilePath) {
        this.jdbcUrl = "jdbc:sqlite:" + databaseFilePath;
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement statement = conn.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS accounts (" +
                    "username TEXT PRIMARY KEY, " +
                    "elo INTEGER NOT NULL, " +
                    "games_played INTEGER NOT NULL, " +
                    "wins INTEGER NOT NULL, " +
                    "losses INTEGER NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialize the accounts database at " + jdbcUrl
                    + " - is a SQLite JDBC driver jar (e.g. sqlite-jdbc) present in lib/?", e);
        }
    }

    @Override
    public synchronized Account findOrCreateAccount(String username) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            Account existing = select(conn, username);
            if (existing != null) {
                return existing;
            }

            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO accounts (username, elo, games_played, wins, losses) VALUES (?, ?, 0, 0, 0)")) {
                insert.setString(1, username);
                insert.setInt(2, EloRating.DEFAULT_RATING);
                insert.executeUpdate();
            }
            return new Account(username, EloRating.DEFAULT_RATING, 0, 0, 0);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read or create account for username " + username, e);
        }
    }

    @Override
    public synchronized void recordGameResult(String winnerUsername, int newWinnerElo, String loserUsername, int newLoserElo) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            applyResult(conn, winnerUsername, newWinnerElo, true);
            applyResult(conn, loserUsername, newLoserElo, false);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record game result for "
                    + winnerUsername + " (winner) / " + loserUsername + " (loser)", e);
        }
    }

    private Account select(Connection conn, String username) throws SQLException {
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT elo, games_played, wins, losses FROM accounts WHERE username = ?")) {
            select.setString(1, username);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) return null;
                return new Account(username, rs.getInt("elo"), rs.getInt("games_played"),
                        rs.getInt("wins"), rs.getInt("losses"));
            }
        }
    }

    private void applyResult(Connection conn, String username, int newElo, boolean won) throws SQLException {
        // Row is assumed to already exist (created by findOrCreateAccount at
        // login time, which GameServer always calls before a game can even
        // start) - this method only updates, it never inserts.
        String sql = "UPDATE accounts SET elo = ?, games_played = games_played + 1, "
                + (won ? "wins = wins + 1" : "losses = losses + 1")
                + " WHERE username = ?";
        try (PreparedStatement update = conn.prepareStatement(sql)) {
            update.setInt(1, newElo);
            update.setString(2, username);
            update.executeUpdate();
        }
    }
}

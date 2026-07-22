package org.example.net.server;

import org.example.account.AccountRepository;
import org.example.account.InMemoryAccountRepository;
import org.example.account.SqliteAccountRepository;
import org.example.adapters.BoardParser;
import org.example.model.Board;

import java.util.Arrays;
import java.util.List;

/**
 * Composition root for the networked server process (CTD 26 spec slides
 * 3-5).
 *
 * This class no longer builds a Board/MovementEngine/GameEngine/EventBus
 * itself - every Room builds its own fresh set when created (see Room's
 * constructor), since Phase 5 supports many concurrent rooms rather than one
 * implicit table. This class only hands GameServer a boardFactory (how to
 * build the STARTING position for any room - the one thing that's actually
 * configuration, not per-room state), the account repository, and a
 * server-side GameLogger for Phase 5's dual-side logging.
 *
 * Run with optional arguments: `java org.example.net.server.ServerMain [port] [dbFile]`;
 * defaults to port 8887 and kungfuchess.db in the working directory.
 *
 * IMPORTANT (Phase 3 / accounts+ELO): SqliteAccountRepository is written
 * against plain java.sql and needs an actual JDBC driver jar on the
 * classpath at runtime to work (e.g. org.xerial:sqlite-jdbc, downloadable
 * from https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/ - grab the
 * latest -jar-with-dependencies-free "sqlite-jdbc-X.Y.Z.jar" and drop it in
 * lib/). Without one present, DriverManager.getConnection("jdbc:sqlite:...")
 * fails immediately with "No suitable driver found" the moment this class
 * tries to open the database - if that happens, this class falls back to a
 * clear console warning and an in-memory (non-persistent) repository rather
 * than crashing the whole server, so the rest of the game is still fully
 * playable even before the jar is in place.
 */
public final class ServerMain {

    private static final int DEFAULT_PORT = 8887;
    private static final String DEFAULT_DB_FILE = "kungfuchess.db";

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String dbFile = args.length > 1 ? args[1] : DEFAULT_DB_FILE;

        AccountRepository accountRepository = openAccountRepository(dbFile);
        org.example.net.log.GameLogger logger = org.example.net.log.GameLogger.create("server", "server");

        GameServer server = new GameServer(port, ServerMain::newStartingBoard, accountRepository,
                GameServer.DEFAULT_RESIGN_GRACE_MILLIS, logger);
        server.start();
        server.startGameLoop();

        System.out.println("Kung Fu Chess server started on ws://localhost:" + port);
        System.out.println("Log in with a username, then create or join a room from the lobby to play (or spectate a room that's already full).");
    }

    /** The standard starting position, built fresh on demand - see GameServer.startNewRound, which calls this once per round (every match gets its own brand-new Board, never a reused/still-mutated one from a previous game). */
    private static Board newStartingBoard() {
        List<String> startingPosition = Arrays.asList(
                "bR bN bB bQ bK bB bN bR",
                "bP bP bP bP bP bP bP bP",
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . .",
                "wP wP wP wP wP wP wP wP",
                "wR wN wB wQ wK wB wN wR"
        );
        return BoardParser.parse(startingPosition);
    }

    /**
     * Tries to open the real, persistent SQLite-backed repository; falls
     * back to an in-memory one (with a clear warning) if that fails - most
     * commonly because no SQLite JDBC driver jar is on the classpath yet
     * (see this class's doc). This keeps the server usable for local
     * testing/demoing even before that jar has been added, rather than
     * refusing to start at all.
     */
    private static AccountRepository openAccountRepository(String dbFile) {
        try {
            SqliteAccountRepository repository = new SqliteAccountRepository(dbFile);
            System.out.println("Accounts database: " + dbFile);
            return repository;
        } catch (RuntimeException e) {
            System.err.println("Could not open SQLite accounts database (" + e.getMessage() + ")");
            System.err.println("Falling back to IN-MEMORY accounts - ratings will NOT persist across restarts.");
            System.err.println("Add a SQLite JDBC driver jar (e.g. sqlite-jdbc) to lib/ to enable persistence.");
            return new InMemoryAccountRepository();
        }
    }
}

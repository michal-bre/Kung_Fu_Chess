package org.example.net.server;

import org.example.account.Account;
import org.example.account.AccountRepository;
import org.example.account.EloRating;
import org.example.bus.EventBus;
import org.example.bus.GameEndedEvent;
import org.example.bus.GameStartedEvent;
import org.example.bus.MoveLoggedEvent;
import org.example.bus.ScoreUpdatedEvent;
import org.example.engine.DefaultGameEngine;
import org.example.engine.GameEngine;
import org.example.engine.GameSnapshot;
import org.example.engine.JumpResult;
import org.example.engine.MoveResult;
import org.example.engine.MovementEngine;
import org.example.engine.PieceSnapshot;
import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;
import org.example.model.Square;
import org.example.net.protocol.Json;
import org.example.net.protocol.Protocol;
import org.example.rules.MoveValidationService;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The authoritative, server-side half of the networked game (CTD 26 spec
 * slides 3-5): a single-process WebSocket server that owns the real
 * Board/GameEngine and treats every connected client purely as an input
 * source + display sink.
 *
 * This is a genuine architectural shift from today's local/hot-seat mode:
 * there, the Swing client itself is authoritative (GameController talks
 * straight to a local GameEngine). Here, GameController is never
 * constructed at all - GameEngine is driven directly by this class, and
 * "what a click meant" is decided entirely on the CLIENT side (see
 * NetworkInputReceiver) and arrives here already reduced to a MOVE/JUMP
 * command naming two algebraic squares. The server is the only thing that
 * can ever actually mutate the board.
 *
 * Phase 4 turned this from "the first two connections play, forever" into
 * real (if single-table) matchmaking: a connection isn't assigned a color at
 * all until it LOGINs; LOGIN enqueues it in waitingQueue, and the moment two
 * connections are waiting AND no round is currently active, tryStartMatch
 * pairs them (FIFO - first-queued is White) and starts a fresh round via
 * startNewRound, which builds an entirely new Board/MovementEngine/
 * MoveValidationService/DefaultGameEngine/EventBus rather than reusing the
 * previous round's (now-finished, now-stale) ones. Every round-ending path -
 * a real king capture, or a forced resign() below - funnels through the
 * exact same GameEndedEvent -> onGameEnded handler, which is therefore also
 * the single place a finished round's seats are freed and the next
 * matchmaking attempt is kicked off.
 *
 * Disconnect handling: a player who drops mid-game isn't treated as an
 * immediate loss. Their seat enters a "disconnected, on the clock" grace
 * period (see beginDisconnectGracePeriod/forceResign); if the SAME username
 * logs back in before the grace period elapses, they resume the exact seat
 * they left (tryResumeDisconnectedSeat) with the game continuing exactly
 * where it was - the board never reset, only the WebSocket reference
 * changed. If the grace period elapses first, GameEngine.resign is called
 * for the disconnected color, which ends the game exactly like a king
 * capture would (ELO update, broadcast, seats freed, matchmaking resumes).
 *
 * Concurrency: java_websocket calls onOpen/onMessage/onClose from its own
 * I/O threads; this class also runs its own tick thread (startGameLoop) and
 * a disconnect-timer thread (disconnectExecutor). engineLock is now the
 * single lock for EVERYTHING mutable here - board/gameEngine/bus
 * (round state), whiteConnection/blackConnection/waitingQueue (matchmaking
 * state), and disconnectedColor/pendingResignTask (grace-period state) -
 * not just engine access like in Phase 2/3. This is safe specifically
 * because Java's synchronized is reentrant: onGameEnded (which always runs
 * from inside a synchronized(engineLock) block - see requestMove/
 * requestJump/advanceTime/resign's call sites) itself calls tryStartMatch,
 * which calls startNewRound, both of which also synchronize on engineLock -
 * the same thread re-entering the same monitor never blocks. Using one lock
 * for both concerns, rather than two separate locks, is what makes that
 * reentrant chain safe instead of a lock-ordering hazard.
 */
public final class GameServer extends WebSocketServer {

    public static final long DEFAULT_RESIGN_GRACE_MILLIS = 30_000;

    private final Supplier<Board> boardFactory;
    private final AccountRepository accountRepository;
    private final long resignGraceMillis;
    private final Object engineLock = new Object();

    private final ScheduledExecutorService disconnectExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "disconnect-grace-timer");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<WebSocket, Piece.Color> colorByConnection = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> usernameByConnection = new ConcurrentHashMap<>();
    private final Map<WebSocket, Account> accountByConnection = new ConcurrentHashMap<>();
    private final Deque<WebSocket> waitingQueue = new ConcurrentLinkedDeque<>();

    // The current round's game state - reassigned wholesale (never mutated
    // in place) by startNewRound, always under engineLock. Never null after
    // construction: round 1 is built eagerly in the constructor, so every
    // connection has something valid to read even before any player has
    // logged in.
    private Board board;
    private GameEngine gameEngine;
    private EventBus bus;

    private WebSocket whiteConnection;
    private WebSocket blackConnection;

    // Disconnect/auto-resign grace-period state - at most one pending at a
    // time, since there is only ever one active round on this single-table
    // server (see the class doc; true concurrent tables are Phase 5's
    // "rooms").
    private Piece.Color disconnectedColor;
    private String disconnectedUsername;
    private ScheduledFuture<?> pendingResignTask;

    public GameServer(int port, Supplier<Board> boardFactory, AccountRepository accountRepository) {
        this(port, boardFactory, accountRepository, DEFAULT_RESIGN_GRACE_MILLIS);
    }

    public GameServer(int port, Supplier<Board> boardFactory, AccountRepository accountRepository, long resignGraceMillis) {
        super(new InetSocketAddress(port));
        this.boardFactory = boardFactory;
        this.accountRepository = accountRepository;
        this.resignGraceMillis = resignGraceMillis;
        startNewRound();
    }

    @Override
    public void onStart() {
        System.out.println("[GameServer] listening on port " + getPort());
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[GameServer] connection opened from " + conn.getRemoteSocketAddress());
        // No color assignment here anymore - see the class doc. A newly
        // opened connection still gets an immediate snapshot of whatever
        // round is currently active/about to start, purely so a client that
        // connects before logging in has something non-empty to render.
        conn.send(buildStateMessage());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        waitingQueue.remove(conn);
        String username = usernameByConnection.remove(conn);
        accountByConnection.remove(conn);
        Piece.Color releasedColor = colorByConnection.remove(conn);

        synchronized (engineLock) {
            boolean wasActivePlayer = (conn == whiteConnection || conn == blackConnection);
            if (!wasActivePlayer) {
                System.out.println("[GameServer] connection closed (queued/unassigned): " + reason);
                return;
            }

            if (gameEngine.isGameOver()) {
                // The round had already finished (or a new one hadn't
                // properly started) by the time this close arrived - just
                // free the seat, nothing to put on the clock.
                if (conn == whiteConnection) whiteConnection = null;
                if (conn == blackConnection) blackConnection = null;
                tryStartMatch();
            } else {
                beginDisconnectGracePeriod(releasedColor, username);
            }
        }
        System.out.println("[GameServer] connection closed (" + releasedColor + "): " + reason);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Map<String, Object> parsed;
        try {
            parsed = Json.parseObject(message);
        } catch (RuntimeException e) {
            conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "malformed JSON"));
            return;
        }

        String type = Protocol.getString(parsed, "type");
        if (type == null) {
            conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "missing type"));
            return;
        }

        switch (type) {
            case Protocol.TYPE_LOGIN:
                handleLogin(conn, parsed);
                break;
            case Protocol.TYPE_MOVE:
                handleMove(conn, parsed);
                break;
            case Protocol.TYPE_JUMP:
                handleJump(conn, parsed);
                break;
            default:
                conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "unknown type " + type));
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[GameServer] error on " + (conn == null ? "server" : conn.getRemoteSocketAddress()) + ": " + ex);
    }

    /**
     * Handles a LOGIN message: looks up (or, for a first-time username,
     * creates) that player's persistent Account, sends it back as
     * ACCOUNT_INFO, then either resumes a disconnected seat this same
     * username still owns (tryResumeDisconnectedSeat) or joins the
     * matchmaking queue (enqueueForMatch) - never both.
     */
    private void handleLogin(WebSocket conn, Map<String, Object> message) {
        String username = Protocol.getString(message, "username");
        if (username == null || username.isEmpty()) {
            conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "username required"));
            return;
        }
        usernameByConnection.put(conn, username);

        Account account = accountRepository.findOrCreateAccount(username);
        accountByConnection.put(conn, account);
        conn.send(Protocol.write(Protocol.TYPE_ACCOUNT_INFO,
                "username", account.getUsername(),
                "elo", account.getElo(),
                "gamesPlayed", account.getGamesPlayed(),
                "wins", account.getWins(),
                "losses", account.getLosses()));

        synchronized (engineLock) {
            if (!tryResumeDisconnectedSeat(conn, username)) {
                enqueueForMatch(conn);
            }
        }
    }

    /** Must be called while holding engineLock. If {@code username} matches the seat currently on a disconnect grace-period clock, cancels that clock and hands the seat to {@code conn} instead of queueing it fresh. */
    private boolean tryResumeDisconnectedSeat(WebSocket conn, String username) {
        if (disconnectedColor == null || !username.equals(disconnectedUsername)) {
            return false;
        }

        Piece.Color color = disconnectedColor;
        if (pendingResignTask != null) {
            pendingResignTask.cancel(false);
        }
        pendingResignTask = null;
        disconnectedColor = null;
        disconnectedUsername = null;

        if (color == Piece.Color.WHITE) {
            whiteConnection = conn;
        } else {
            blackConnection = conn;
        }
        colorByConnection.put(conn, color);

        conn.send(Protocol.write(Protocol.TYPE_COLOR_ASSIGNED, "color", color.name()));
        conn.send(buildStateMessage());
        broadcast(Protocol.write(Protocol.TYPE_OPPONENT_RECONNECTED, "color", color.name(), "username", username));
        System.out.println("[GameServer] " + username + " reconnected as " + color);
        return true;
    }

    /** Must be called while holding engineLock. Adds {@code conn} to the matchmaking queue and immediately tries to start a match (which succeeds right away if this was the second waiting connection). */
    private void enqueueForMatch(WebSocket conn) {
        waitingQueue.add(conn);
        conn.send(Protocol.write(Protocol.TYPE_WAITING, "queuePosition", waitingQueue.size()));
        tryStartMatch();
    }

    /** Must be called while holding engineLock. A no-op unless a round isn't currently active AND at least two connections are waiting - see the class doc for why this can safely be called from many different places (onGameEnded, enqueueForMatch, a freed-up seat after a non-grace-period close) without any of them needing to reason about whether a match is "already" startable. */
    private void tryStartMatch() {
        if (whiteConnection != null && blackConnection != null) return;

        WebSocket first = pollNextOpenConnection();
        if (first == null) return;
        WebSocket second = pollNextOpenConnection();
        if (second == null) {
            waitingQueue.addFirst(first);
            return;
        }

        whiteConnection = first;
        blackConnection = second;
        colorByConnection.put(first, Piece.Color.WHITE);
        colorByConnection.put(second, Piece.Color.BLACK);

        startNewRound();

        first.send(Protocol.write(Protocol.TYPE_COLOR_ASSIGNED, "color", "WHITE"));
        second.send(Protocol.write(Protocol.TYPE_COLOR_ASSIGNED, "color", "BLACK"));
        first.send(buildStateMessage());
        second.send(buildStateMessage());
        System.out.println("[GameServer] match started: " + usernameByConnection.get(first) + " (WHITE) vs "
                + usernameByConnection.get(second) + " (BLACK)");
    }

    private WebSocket pollNextOpenConnection() {
        WebSocket conn;
        while ((conn = waitingQueue.poll()) != null) {
            if (conn.isOpen()) return conn;
        }
        return null;
    }

    /** Must be called while holding engineLock. Puts {@code color}'s seat on a resignGraceMillis clock: if tryResumeDisconnectedSeat doesn't cancel it first, forceResign runs once the clock elapses. */
    private void beginDisconnectGracePeriod(Piece.Color color, String username) {
        disconnectedColor = color;
        disconnectedUsername = username;

        broadcast(Protocol.write(Protocol.TYPE_OPPONENT_DISCONNECTED,
                "color", color.name(), "username", username, "graceSeconds", resignGraceMillis / 1000));

        pendingResignTask = disconnectExecutor.schedule(() -> forceResign(color), resignGraceMillis, TimeUnit.MILLISECONDS);
    }

    /** Runs on disconnectExecutor's own thread once a grace period elapses without a reconnect. */
    private void forceResign(Piece.Color resigningColor) {
        synchronized (engineLock) {
            // If the player already reconnected (or a whole new round
            // already started for other reasons) between scheduling this
            // task and it actually firing, disconnectedColor won't match
            // anymore - cancel() on the future should already prevent this
            // in practice, but this check costs nothing and removes any
            // reliance on cancellation timing being perfect.
            if (disconnectedColor != resigningColor) return;

            disconnectedColor = null;
            disconnectedUsername = null;
            pendingResignTask = null;

            gameEngine.resign(resigningColor);
            // gameEngine.resign publishes GameEndedEvent synchronously (see
            // MovementEngine.setWinner) - onGameEnded, still within this
            // same synchronized(engineLock) block via reentrancy, handles
            // the ELO update, broadcast, freeing the seats, and kicking off
            // the next matchmaking attempt. Nothing further to do here.
        }
    }

    /**
     * Builds a fresh round: a brand-new Board (from boardFactory) wired to a
     * brand-new MovementEngine/MoveValidationService/DefaultGameEngine and a
     * brand-new EventBus - exactly the same wiring recipe GuiMain/ServerMain
     * always used, just repeated on demand instead of once at process
     * startup. Re-subscribes the same four bus relays (score/move-log/
     * game-started/game-ended -> broadcast) to the NEW bus, since EventBus
     * has no unsubscribe (see its class doc) and the previous round's bus is
     * simply abandoned along with the rest of that round's now-finished
     * state. Must be called while holding engineLock.
     */
    private void startNewRound() {
        Board newBoard = boardFactory.get();
        EventBus newBus = new EventBus();
        MovementEngine movementEngine = new MovementEngine(newBoard, newBus);
        MoveValidationService moveValidationService = new MoveValidationService(newBoard, movementEngine);
        GameEngine newEngine = new DefaultGameEngine(newBoard, movementEngine, moveValidationService, Board.CELL_SIZE);

        newBus.subscribe(ScoreUpdatedEvent.class, this::onScoreUpdated);
        newBus.subscribe(MoveLoggedEvent.class, this::onMoveLogged);
        newBus.subscribe(GameStartedEvent.class, event -> broadcast(Protocol.write(Protocol.TYPE_GAME_STARTED)));
        newBus.subscribe(GameEndedEvent.class, this::onGameEnded);

        this.board = newBoard;
        this.gameEngine = newEngine;
        this.bus = newBus;
    }

    private void handleMove(WebSocket conn, Map<String, Object> message) {
        Piece.Color owner = colorByConnection.get(conn);
        String fromSquare = Protocol.getString(message, "from");
        String toSquare = Protocol.getString(message, "to");

        Optional<Position> from = fromSquare == null ? Optional.empty() : Square.fromAlgebraic(fromSquare, board.getHeight());
        Optional<Position> to = toSquare == null ? Optional.empty() : Square.fromAlgebraic(toSquare, board.getHeight());
        if (!from.isPresent() || !to.isPresent()) {
            conn.send(Protocol.write(Protocol.TYPE_MOVE_REJECTED, "square", fromSquare, "reason", "bad_square"));
            return;
        }

        MoveResult result;
        synchronized (engineLock) {
            // Server-side ownership authority: a connection may only move a
            // piece of its OWN assigned color - this is the one check that
            // has no equivalent in the local/hot-seat InteractionHandler,
            // since there both sides share one input source and this
            // distinction doesn't exist.
            Piece piece = board.getPiece(from.get());
            if (owner == null || piece == null || piece.getColor() != owner) {
                result = MoveResult.rejected("not_your_piece");
            } else {
                result = gameEngine.requestMove(from.get(), to.get());
            }
        }
        if (!result.isAccepted()) {
            conn.send(Protocol.write(Protocol.TYPE_MOVE_REJECTED, "square", toSquare, "reason", result.getReason()));
        }
    }

    private void handleJump(WebSocket conn, Map<String, Object> message) {
        Piece.Color owner = colorByConnection.get(conn);
        String squareName = Protocol.getString(message, "square");
        Optional<Position> square = squareName == null ? Optional.empty() : Square.fromAlgebraic(squareName, board.getHeight());
        if (!square.isPresent()) {
            conn.send(Protocol.write(Protocol.TYPE_JUMP_REJECTED, "square", squareName, "reason", "bad_square"));
            return;
        }

        JumpResult result;
        synchronized (engineLock) {
            Piece piece = board.getPiece(square.get());
            if (owner == null || piece == null || piece.getColor() != owner) {
                result = JumpResult.rejected("not_your_piece");
            } else {
                result = gameEngine.requestJump(square.get());
            }
        }
        if (!result.isAccepted()) {
            conn.send(Protocol.write(Protocol.TYPE_JUMP_REJECTED, "square", squareName, "reason", result.getReason()));
        }
    }

    /**
     * Runs the server's own tick loop on a dedicated daemon thread: advances
     * the engine's clock and broadcasts a fresh STATE snapshot to every
     * connected client, at roughly the same ~33ms cadence GameLoop uses for
     * local play. Separate method (not started from the constructor) so
     * ServerMain controls exactly when the game clock starts running.
     */
    public void startGameLoop() {
        Thread loop = new Thread(() -> {
            long tickMillis = 33;
            while (true) {
                synchronized (engineLock) {
                    gameEngine.advanceTime(tickMillis);
                }
                broadcast(buildStateMessage());
                try {
                    Thread.sleep(tickMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "game-tick-loop");
        loop.setDaemon(true);
        loop.start();
    }

    /**
     * Serializes a base GameSnapshot - selectedPosition/rejectedPosition are
     * deliberately NOT included: those are per-viewer UI concerns (which
     * square THIS player has clicked), not authoritative game state, so the
     * server has nothing meaningful to put there. Each client reconstructs
     * its own composite GameSnapshot locally by overlaying its own selection
     * onto this base - see NetworkInputReceiver/GameClient.
     */
    private String buildStateMessage() {
        GameSnapshot snapshot;
        synchronized (engineLock) {
            snapshot = gameEngine.snapshot(null, null, -1);
        }

        List<Object> pieces = new ArrayList<>();
        for (PieceSnapshot p : snapshot.getPieces()) {
            Map<String, Object> pieceMap = new LinkedHashMap<>();
            pieceMap.put("id", p.getId());
            pieceMap.put("color", p.getColor().name());
            pieceMap.put("type", p.getType().name());
            pieceMap.put("x", p.getPixelX());
            pieceMap.put("y", p.getPixelY());
            pieceMap.put("state", p.getVisualState().name());
            pieceMap.put("elapsed", p.getStateElapsedMillis());
            pieces.add(pieceMap);
        }

        return Protocol.write(Protocol.TYPE_STATE,
                "boardWidth", snapshot.getBoardWidth(),
                "boardHeight", snapshot.getBoardHeight(),
                "gameTimeMillis", snapshot.getGameTimeMillis(),
                "gameOver", snapshot.isGameOver(),
                "winner", snapshot.getWinner() == null ? null : snapshot.getWinner().name(),
                "pieces", pieces);
    }

    private void onScoreUpdated(ScoreUpdatedEvent event) {
        broadcast(Protocol.write(Protocol.TYPE_SCORE,
                "color", event.getColor().name(),
                "score", event.getNewScore()));
    }

    private void onMoveLogged(MoveLoggedEvent event) {
        broadcast(Protocol.write(Protocol.TYPE_MOVE_LOG,
                "color", event.getEntry().getColor().name(),
                "timeMillis", event.getEntry().getTimeMillis(),
                "notation", event.getEntry().getNotation(),
                "capture", event.isCapture()));
    }

    /**
     * Fires synchronously off MovementEngine's own capture-resolution code
     * (or DefaultGameEngine.resign, for a forced auto-resign) - always from
     * inside a synchronized(engineLock) block (handleMove/handleJump/the
     * tick loop/forceResign), so this method runs WHILE holding engineLock.
     * It does a couple of small, fast SQLite writes plus a couple of network
     * broadcasts in that window; acceptable for this project's scope (a
     * single one-time event per game, not a hot path).
     *
     * Only updates ELO when BOTH sides are real, logged-in accounts
     * (accountByConnection has an entry for both the winning and losing
     * connection) - a spectator-only or single-player session still ends
     * the game and still broadcasts GAME_ENDED, just without any rating
     * fields attached.
     *
     * Regardless of how the round ended, both seats are freed and
     * tryStartMatch is invoked at the end - this is the ONE place every
     * round-ending path (king capture, forced resignation) converges, so
     * it's the natural single place to hand the table to the next two
     * waiting players, if any.
     */
    private void onGameEnded(GameEndedEvent event) {
        Piece.Color winnerColor = event.getWinner();
        WebSocket winnerConn = winnerColor == Piece.Color.WHITE ? whiteConnection : blackConnection;
        WebSocket loserConn = winnerColor == Piece.Color.WHITE ? blackConnection : whiteConnection;

        Account winnerAccount = winnerConn == null ? null : accountByConnection.get(winnerConn);
        Account loserAccount = loserConn == null ? null : accountByConnection.get(loserConn);

        if (winnerAccount == null || loserAccount == null) {
            broadcast(Protocol.write(Protocol.TYPE_GAME_ENDED, "winner", winnerColor.name()));
        } else {
            EloRating.Result result = EloRating.computeForWin(winnerAccount.getElo(), loserAccount.getElo());
            accountRepository.recordGameResult(
                    winnerAccount.getUsername(), result.getNewWinnerRating(),
                    loserAccount.getUsername(), result.getNewLoserRating());

            broadcast(Protocol.write(Protocol.TYPE_GAME_ENDED,
                    "winner", winnerColor.name(),
                    "winnerUsername", winnerAccount.getUsername(),
                    "winnerNewElo", result.getNewWinnerRating(),
                    "winnerEloDelta", result.getNewWinnerRating() - winnerAccount.getElo(),
                    "loserUsername", loserAccount.getUsername(),
                    "loserNewElo", result.getNewLoserRating(),
                    "loserEloDelta", result.getNewLoserRating() - loserAccount.getElo()));
        }

        whiteConnection = null;
        blackConnection = null;
        tryStartMatch();
    }
}

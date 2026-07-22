package org.example.net.server;

import org.example.account.Account;
import org.example.account.AccountRepository;
import org.example.account.EloRating;
import org.example.bus.EventBus;
import org.example.bus.GameEndedEvent;
import org.example.bus.GameStartedEvent;
import org.example.bus.MoveLoggedEvent;
import org.example.bus.ScoreUpdatedEvent;
import org.example.controller.MoveHistoryEntry;
import org.example.controller.MoveNotation;
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
import org.example.net.log.GameLogger;
import org.example.net.protocol.Protocol;
import org.example.rules.MoveValidationService;
import org.java_websocket.WebSocket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * One game table: a single, self-contained board/engine/round plus the
 * White/Black seats, any spectators, and disconnect/auto-resign state for
 * that one game. Phase 5 (CTD 26 spec slide 5) split this out of GameServer,
 * which through Phase 4 WAS this - a single implicit table the whole server
 * shared - so that GameServer could become a genuine multi-room lobby (see
 * its class doc): many Rooms can now exist and tick concurrently, each
 * completely isolated from the others.
 *
 * That isolation is the one thing that changed in every method ported over
 * from Phase 4's GameServer: broadcasting no longer means "every connection
 * on the server" (WebSocketServer.broadcast would leak this room's board
 * into every OTHER room's players and spectators) - it means exactly this
 * room's White/Black connections plus its spectator set, via
 * broadcastToRoom below.
 *
 * Spectators (new this phase): any connection GameServer routes into this
 * room via addSpectator once both seats are already taken. A spectator
 * receives the exact same STATE/SCORE/MOVE_LOG/GAME_STARTED/GAME_ENDED
 * broadcasts a player does (nothing about this room's outward messages
 * distinguishes a spectator from a player), but never has an entry in
 * colorByConnection, so handleMove/handleJump's ownership check rejects
 * anything a spectator tries to send - the exact same mechanism that
 * already made an unassigned connection harmless in Phase 2, just now with
 * a real audience actually watching instead of unused.
 *
 * usernameByConnection/accountByConnection are NOT owned here - they're
 * genuinely connection-level, not room-level, state (a player's account
 * doesn't change depending on which room they're in), so GameServer owns
 * those maps and hands this room read-only lookups into them.
 *
 * Roster (Phase 8): everyone in the room - both seats plus every spectator -
 * gets a live ROOM_ROSTER broadcast naming every participant and their role
 * ("you are black", "Carol is a viewer", etc., in the client's own words -
 * see RosterPanel), rebroadcast on every join/leave/disconnect/reconnect.
 * whiteUsername/blackUsername/spectatorUsernames exist ONLY for this: names
 * are captured at assignment time rather than re-read from
 * usernameByConnection later, because GameServer purges that map's entry for
 * a connection the moment it closes - which can happen well before this
 * room finishes reacting to it (the entire disconnect grace period, for a
 * seat). disconnectedUsername already solved this same problem for one seat;
 * these fields generalize it to the whole roster.
 */
final class Room {

    private final String id;
    private final AccountRepository accountRepository;
    private final long resignGraceMillis;
    private final ScheduledExecutorService disconnectExecutor;
    private final GameLogger logger;
    private final Map<WebSocket, String> usernameByConnection;
    private final Map<WebSocket, Account> accountByConnection;
    private final Object engineLock = new Object();

    private final Map<WebSocket, Piece.Color> colorByConnection = new ConcurrentHashMap<>();
    private final Set<WebSocket> spectators = ConcurrentHashMap.newKeySet();

    private Board board;
    private GameEngine gameEngine;
    private EventBus bus;

    private WebSocket whiteConnection;
    private WebSocket blackConnection;

    // Names captured at seat/spectator-assignment time, deliberately NOT
    // re-read from usernameByConnection later - GameServer purges that map's
    // entry for a connection as soon as it closes (see GameServer.onClose),
    // which can happen well before this room finishes reacting to it (e.g.
    // the whole disconnect grace period). disconnectedUsername already had
    // to solve this exact problem for the one seat currently mid-grace-period
    // - these fields are the same fix applied everywhere a roster entry
    // needs a name that must outlive its connection's own map entry.
    private String whiteUsername;
    private String blackUsername;
    private final Map<WebSocket, String> spectatorUsernames = new ConcurrentHashMap<>();

    private Piece.Color disconnectedColor;
    private String disconnectedUsername;
    private ScheduledFuture<?> pendingResignTask;

    Room(String id, Supplier<Board> boardFactory, AccountRepository accountRepository, long resignGraceMillis,
         ScheduledExecutorService disconnectExecutor, GameLogger logger,
         Map<WebSocket, String> usernameByConnection, Map<WebSocket, Account> accountByConnection) {
        this.id = id;
        this.accountRepository = accountRepository;
        this.resignGraceMillis = resignGraceMillis;
        this.disconnectExecutor = disconnectExecutor;
        this.logger = logger;
        this.usernameByConnection = usernameByConnection;
        this.accountByConnection = accountByConnection;

        Board newBoard = boardFactory.get();
        EventBus newBus = new EventBus();
        MovementEngine movementEngine = new MovementEngine(newBoard, newBus);
        MoveValidationService moveValidationService = new MoveValidationService(newBoard, movementEngine);
        GameEngine newEngine = new DefaultGameEngine(newBoard, movementEngine, moveValidationService, Board.CELL_SIZE);

        newBus.subscribe(ScoreUpdatedEvent.class, this::onScoreUpdated);
        newBus.subscribe(MoveLoggedEvent.class, this::onMoveLogged);
        newBus.subscribe(GameStartedEvent.class, event -> broadcastToRoom(Protocol.write(Protocol.TYPE_GAME_STARTED)));
        newBus.subscribe(GameEndedEvent.class, this::onGameEnded);

        this.board = newBoard;
        this.gameEngine = newEngine;
        this.bus = newBus;
    }

    String getId() {
        return id;
    }

    boolean hasOpenSeat() {
        synchronized (engineLock) {
            return whiteConnection == null || blackConnection == null;
        }
    }

    int getSpectatorCount() {
        return spectators.size();
    }

    boolean isGameOver() {
        synchronized (engineLock) {
            return gameEngine.isGameOver();
        }
    }

    /** A small, ROOM_LIST-ready summary of this room's current status. */
    Map<String, Object> summarize() {
        String status;
        synchronized (engineLock) {
            if (gameEngine.isGameOver()) {
                status = "FINISHED";
            } else if (hasOpenSeat()) {
                status = "WAITING_FOR_OPPONENT";
            } else {
                status = "IN_PROGRESS";
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", id);
        summary.put("status", status);
        summary.put("spectators", spectators.size());
        return summary;
    }

    /** Assigns {@code conn} to whichever seat (White first, then Black) is still open, and sends it COLOR_ASSIGNED plus an immediate STATE snapshot. Caller (GameServer) must already have checked hasOpenSeat(). */
    void assignSeat(WebSocket conn) {
        Piece.Color color;
        String username = usernameByConnection.get(conn);
        synchronized (engineLock) {
            if (whiteConnection == null) {
                whiteConnection = conn;
                whiteUsername = username;
                color = Piece.Color.WHITE;
            } else {
                blackConnection = conn;
                blackUsername = username;
                color = Piece.Color.BLACK;
            }
        }
        colorByConnection.put(conn, color);
        conn.send(Protocol.write(Protocol.TYPE_COLOR_ASSIGNED, "color", color.name(), "roomId", id));
        conn.send(buildStateMessage());
        logger.log("room " + id + ": " + username + " seated as " + color);
        broadcastRoster();
    }

    void addSpectator(WebSocket conn) {
        String username = usernameByConnection.get(conn);
        spectators.add(conn);
        spectatorUsernames.put(conn, username);
        conn.send(Protocol.write(Protocol.TYPE_SPECTATING, "roomId", id));
        conn.send(buildStateMessage());
        logger.log("room " + id + ": " + username + " is now spectating");
        broadcastRoster();
    }

    /**
     * Removes {@code conn} from this room, however it left (a graceful
     * LEAVE_ROOM or the WebSocket simply closing - GameServer calls this
     * from both places identically). A spectator is just dropped. A seated
     * player mid-game instead starts this room's disconnect grace period
     * (see beginDisconnectGracePeriod) rather than losing immediately -
     * leaving and disconnecting are treated the same way on purpose: from
     * this room's perspective, "the connection is gone" is the only fact
     * that matters, not why.
     */
    void removeConnection(WebSocket conn) {
        if (spectators.remove(conn)) {
            String spectatorUsername = spectatorUsernames.remove(conn);
            logger.log("room " + id + ": " + spectatorUsername + " stopped spectating");
            broadcastRoster();
            return;
        }

        Piece.Color releasedColor = colorByConnection.remove(conn);
        if (releasedColor == null) {
            return; // wasn't a member of this room at all
        }

        synchronized (engineLock) {
            boolean wasActivePlayer = (conn == whiteConnection || conn == blackConnection);
            if (!wasActivePlayer) return;

            if (gameEngine.isGameOver()) {
                if (conn == whiteConnection) { whiteConnection = null; whiteUsername = null; }
                if (conn == blackConnection) { blackConnection = null; blackUsername = null; }
            } else {
                beginDisconnectGracePeriod(releasedColor, usernameByConnection.get(conn));
            }
        }
        broadcastRoster();
    }

    /** If {@code username} matches the seat currently on this room's disconnect grace-period clock, cancels that clock and hands the seat to {@code conn} instead. Returns whether that happened. */
    boolean tryResumeDisconnectedSeat(WebSocket conn, String username) {
        synchronized (engineLock) {
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
                whiteUsername = username;
            } else {
                blackConnection = conn;
                blackUsername = username;
            }
            colorByConnection.put(conn, color);

            conn.send(Protocol.write(Protocol.TYPE_COLOR_ASSIGNED, "color", color.name(), "roomId", id));
            conn.send(buildStateMessage());
            broadcastToRoom(Protocol.write(Protocol.TYPE_OPPONENT_RECONNECTED, "color", color.name(), "username", username));
            logger.log("room " + id + ": " + username + " reconnected as " + color);
            broadcastRoster();
            return true;
        }
    }

    private void beginDisconnectGracePeriod(Piece.Color color, String username) {
        disconnectedColor = color;
        disconnectedUsername = username;

        broadcastToRoom(Protocol.write(Protocol.TYPE_OPPONENT_DISCONNECTED,
                "color", color.name(), "username", username, "graceSeconds", resignGraceMillis / 1000));
        logger.log("room " + id + ": " + username + " (" + color + ") disconnected - " + (resignGraceMillis / 1000) + "s to reconnect");

        pendingResignTask = disconnectExecutor.schedule(() -> forceResign(color), resignGraceMillis, TimeUnit.MILLISECONDS);
    }

    private void forceResign(Piece.Color resigningColor) {
        synchronized (engineLock) {
            if (disconnectedColor != resigningColor) return;

            disconnectedColor = null;
            disconnectedUsername = null;
            pendingResignTask = null;

            gameEngine.resign(resigningColor);
            // publishes GameEndedEvent synchronously -> onGameEnded, still
            // within this same synchronized(engineLock) block (reentrant).
        }
        // The roster's "disconnected" flag for this seat is derived from
        // disconnectedColor, just cleared above - rebroadcast so the roster
        // reflects "game over" instead of still showing this seat as
        // mid-grace-period once its player has actually been auto-resigned.
        broadcastRoster();
    }

    /**
     * Accepts or rejects a MOVE, and - on acceptance - builds and publishes
     * the exact same MoveLoggedEvent InteractionHandler.handleClick builds
     * for a local move (same MoveNotation formatting, same "read the
     * capture/mover from the board at request time, not at arrival" rule -
     * see MoveHistoryEntry's class doc). This class had no equivalent of
     * that logic until this was added: gameEngine.requestMove was being
     * called directly, bypassing InteractionHandler (which is local-mode
     * only) entirely, so no MOVE_LOG ever reached a networked client's
     * move-history table even though the move itself worked fine (STATE
     * broadcasts don't depend on this at all - see tick/buildStateMessage).
     */
    void handleMove(WebSocket conn, Map<String, Object> message) {
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
        Piece movingPiece;
        boolean isCapture;
        long gameTimeMillis;
        synchronized (engineLock) {
            movingPiece = board.getPiece(from.get());
            isCapture = board.getPiece(to.get()) != null;
            if (owner == null || movingPiece == null || movingPiece.getColor() != owner) {
                result = MoveResult.rejected("not_your_piece");
            } else {
                result = gameEngine.requestMove(from.get(), to.get());
            }
            gameTimeMillis = gameEngine.getGameTimeMillis();
        }

        if (result.isAccepted()) {
            MoveHistoryEntry entry = new MoveHistoryEntry(movingPiece.getColor(), gameTimeMillis,
                    MoveNotation.format(movingPiece, from.get(), to.get(), isCapture, board.getHeight()));
            bus.publish(new MoveLoggedEvent(entry, isCapture));
        } else {
            conn.send(Protocol.write(Protocol.TYPE_MOVE_REJECTED, "square", toSquare, "reason", result.getReason()));
        }
    }

    void handleJump(WebSocket conn, Map<String, Object> message) {
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

    /** Called once per server tick (~33ms) by GameServer's global tick loop - advances this room's own clock and broadcasts a fresh STATE to this room's own members only. */
    void tick(long millis) {
        synchronized (engineLock) {
            gameEngine.advanceTime(millis);
        }
        broadcastToRoom(buildStateMessage());
    }

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
                "roomId", id,
                "boardWidth", snapshot.getBoardWidth(),
                "boardHeight", snapshot.getBoardHeight(),
                "gameTimeMillis", snapshot.getGameTimeMillis(),
                "gameOver", snapshot.isGameOver(),
                "winner", snapshot.getWinner() == null ? null : snapshot.getWinner().name(),
                "pieces", pieces);
    }

    private void onScoreUpdated(ScoreUpdatedEvent event) {
        broadcastToRoom(Protocol.write(Protocol.TYPE_SCORE,
                "color", event.getColor().name(),
                "score", event.getNewScore()));
    }

    private void onMoveLogged(MoveLoggedEvent event) {
        broadcastToRoom(Protocol.write(Protocol.TYPE_MOVE_LOG,
                "color", event.getEntry().getColor().name(),
                "timeMillis", event.getEntry().getTimeMillis(),
                "notation", event.getEntry().getNotation(),
                "capture", event.isCapture()));
        logger.log("room " + id + ": " + event.getEntry().getNotation()
                + (event.isCapture() ? " (capture)" : ""));
    }

    private void onGameEnded(GameEndedEvent event) {
        Piece.Color winnerColor = event.getWinner();
        WebSocket winnerConn = winnerColor == Piece.Color.WHITE ? whiteConnection : blackConnection;
        WebSocket loserConn = winnerColor == Piece.Color.WHITE ? blackConnection : whiteConnection;

        Account winnerAccount = winnerConn == null ? null : accountByConnection.get(winnerConn);
        Account loserAccount = loserConn == null ? null : accountByConnection.get(loserConn);

        if (winnerAccount == null || loserAccount == null) {
            broadcastToRoom(Protocol.write(Protocol.TYPE_GAME_ENDED, "winner", winnerColor.name()));
            logger.log("room " + id + ": game ended, winner=" + winnerColor + " (no ELO update - not both sides were logged-in accounts)");
            return;
        }

        EloRating.Result result = EloRating.computeForWin(winnerAccount.getElo(), loserAccount.getElo());
        accountRepository.recordGameResult(
                winnerAccount.getUsername(), result.getNewWinnerRating(),
                loserAccount.getUsername(), result.getNewLoserRating());

        broadcastToRoom(Protocol.write(Protocol.TYPE_GAME_ENDED,
                "winner", winnerColor.name(),
                "winnerUsername", winnerAccount.getUsername(),
                "winnerNewElo", result.getNewWinnerRating(),
                "winnerEloDelta", result.getNewWinnerRating() - winnerAccount.getElo(),
                "loserUsername", loserAccount.getUsername(),
                "loserNewElo", result.getNewLoserRating(),
                "loserEloDelta", result.getNewLoserRating() - loserAccount.getElo()));
        logger.log("room " + id + ": game ended, " + winnerAccount.getUsername() + " (" + winnerColor + ") beat "
                + loserAccount.getUsername() + " - ELO " + winnerAccount.getElo() + " -> " + result.getNewWinnerRating()
                + " / " + loserAccount.getElo() + " -> " + result.getNewLoserRating());
    }

    /** Rebroadcasts the full participant roster (see buildRoster) to everyone currently in this room - called after every seat/spectator join, leave, disconnect, and reconnect. */
    private void broadcastRoster() {
        broadcastToRoom(Protocol.write(Protocol.TYPE_ROOM_ROSTER, "roomId", id, "players", buildRoster()));
    }

    /**
     * One entry per current participant - both seats (if occupied) followed
     * by every spectator, in join order. A seat whose player is currently
     * mid-disconnect-grace-period is still included (still occupies the
     * seat as far as the room is concerned - see removeConnection/
     * beginDisconnectGracePeriod), just flagged "disconnected": true.
     */
    private List<Object> buildRoster() {
        List<Object> roster = new ArrayList<>();
        synchronized (engineLock) {
            if (whiteConnection != null) {
                roster.add(rosterEntry(whiteUsername, "WHITE", disconnectedColor == Piece.Color.WHITE));
            }
            if (blackConnection != null) {
                roster.add(rosterEntry(blackUsername, "BLACK", disconnectedColor == Piece.Color.BLACK));
            }
        }
        for (String spectatorUsername : spectatorUsernames.values()) {
            roster.add(rosterEntry(spectatorUsername, "SPECTATOR", false));
        }
        return roster;
    }

    private static Map<String, Object> rosterEntry(String username, String role, boolean disconnected) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("username", username == null ? "?" : username);
        entry.put("role", role);
        entry.put("disconnected", disconnected);
        return entry;
    }

    /** Sends {@code json} to exactly this room's own members - White, Black, and every spectator - never the whole server (see class doc). */
    private void broadcastToRoom(String json) {
        if (whiteConnection != null && whiteConnection.isOpen()) whiteConnection.send(json);
        if (blackConnection != null && blackConnection.isOpen()) blackConnection.send(json);
        for (WebSocket spectator : spectators) {
            if (spectator.isOpen()) spectator.send(json);
        }
    }
}

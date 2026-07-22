package org.example.net.server;

import org.example.account.Account;
import org.example.account.AccountRepository;
import org.example.account.AuthenticationException;
import org.example.model.Board;
import org.example.net.log.GameLogger;
import org.example.net.protocol.Json;
import org.example.net.protocol.Protocol;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The multi-room lobby (CTD 26 spec slide 5): tracks every open Room, routes
 * each connection's messages to whichever room (if any) it currently
 * belongs to, and keeps every connected client's room list up to date.
 *
 * Through Phase 4, GameServer WAS a single implicit table - the first two
 * logged-in connections played, everyone else queued for that same table.
 * Phase 5 replaces that with explicit rooms: LOGIN no longer auto-queues a
 * connection into anything: it only authenticates (ACCOUNT_INFO). A
 * connection then either CREATE_ROOMs a new table (becoming its White seat)
 * or JOIN_ROOMs an existing one by id - taking the open seat if there is
 * one, or becoming a spectator if not (see Room's class doc for what that
 * means). Everything about actually PLAYING a game once seated - move
 * ownership, disconnect/auto-resign, ELO on game end, the per-room broadcast
 * loop - now lives in Room, unchanged in substance from Phase 4, just scoped
 * to one table instead of the whole server.
 *
 * usernameByConnection/accountByConnection stay here (not in Room) because
 * they're connection-level facts, not room-level ones - the same connection
 * could in principle look up its account before ever joining a room, or
 * after leaving one. roomByConnection is the only new piece of per-connection
 * bookkeeping this phase adds: which room (if any) a connection's MOVE/JUMP/
 * LEAVE_ROOM messages should be routed to.
 *
 * Dual-side logging (this phase's other addition): every significant
 * server-side event - connections, room lifecycle, moves, disconnects, ELO
 * updates - goes through a single shared GameLogger (see its class doc for
 * why "dual-side" means server and client each keep their own independent
 * log, not that there are two loggers here).
 */
public final class GameServer extends WebSocketServer {

    public static final long DEFAULT_RESIGN_GRACE_MILLIS = 30_000;

    // Phase 7 "Play" quick match (CTD 26 spec slide 6): search within +-100
    // ELO, give up (MATCH_NOT_FOUND) after 1 minute if nobody suitable shows
    // up.
    public static final int MATCH_ELO_RANGE = 100;
    public static final long MATCH_SEARCH_TIMEOUT_MILLIS = 60_000;

    private final Supplier<Board> boardFactory;
    private final AccountRepository accountRepository;
    private final long resignGraceMillis;
    private final long matchSearchTimeoutMillis;
    private final GameLogger logger;

    private final ScheduledExecutorService disconnectExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "disconnect-grace-timer");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<WebSocket, String> usernameByConnection = new ConcurrentHashMap<>();
    private final Map<WebSocket, Account> accountByConnection = new ConcurrentHashMap<>();
    private final Map<WebSocket, Room> roomByConnection = new ConcurrentHashMap<>();
    private final Map<String, Room> roomsById = new ConcurrentHashMap<>();
    private final AtomicInteger roomCounter = new AtomicInteger(0);
    private final AtomicInteger matchRoomCounter = new AtomicInteger(0);

    /** One connection currently searching for a "Play" quick match - see handleFindMatch/tryMatch. Guarded by waitingPlayersLock (a plain ArrayList, not a concurrent collection, since matching has to scan-then-remove atomically - a lock-free structure would let two searchers both "find" the same waiting entry). */
    private static final class WaitingPlayer {
        final WebSocket conn;
        final int elo;
        final ScheduledFuture<?> timeoutTask;

        WaitingPlayer(WebSocket conn, int elo, ScheduledFuture<?> timeoutTask) {
            this.conn = conn;
            this.elo = elo;
            this.timeoutTask = timeoutTask;
        }
    }

    private final Object waitingPlayersLock = new Object();
    private final List<WaitingPlayer> waitingPlayers = new ArrayList<>();

    public GameServer(int port, Supplier<Board> boardFactory, AccountRepository accountRepository) {
        this(port, boardFactory, accountRepository, DEFAULT_RESIGN_GRACE_MILLIS, GameLogger.create("server", "server"));
    }

    public GameServer(int port, Supplier<Board> boardFactory, AccountRepository accountRepository, long resignGraceMillis) {
        this(port, boardFactory, accountRepository, resignGraceMillis, GameLogger.create("server", "server"));
    }

    public GameServer(int port, Supplier<Board> boardFactory, AccountRepository accountRepository, long resignGraceMillis, GameLogger logger) {
        this(port, boardFactory, accountRepository, resignGraceMillis, MATCH_SEARCH_TIMEOUT_MILLIS, logger);
    }

    /** The one constructor that lets tests override the 1-minute quick-match search timeout too, the same way the 4-arg/5-arg constructors already let them override the disconnect grace period - see GameServerDisconnectIntegrationTest's own class doc for why that matters for keeping tests fast. */
    public GameServer(int port, Supplier<Board> boardFactory, AccountRepository accountRepository,
                       long resignGraceMillis, long matchSearchTimeoutMillis, GameLogger logger) {
        super(new InetSocketAddress(port));
        this.boardFactory = boardFactory;
        this.accountRepository = accountRepository;
        this.resignGraceMillis = resignGraceMillis;
        this.matchSearchTimeoutMillis = matchSearchTimeoutMillis;
        this.logger = logger;
    }

    @Override
    public void onStart() {
        logger.log("listening on port " + getPort());
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        logger.log("connection opened from " + conn.getRemoteSocketAddress());
        conn.send(buildRoomListMessage());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        // Room.removeConnection (via beginDisconnectGracePeriod) needs to
        // read this connection's username to include it in the
        // OPPONENT_DISCONNECTED broadcast - it must run BEFORE
        // usernameByConnection/accountByConnection are cleared, or it reads
        // null. accountByConnection deliberately stays populated until after
        // too, for the same reason (Room.onGameEnded, which a forced
        // resignation triggers synchronously from within removeConnection,
        // reads accountByConnection for both players).
        Room room = roomByConnection.remove(conn);
        if (room != null) {
            room.removeConnection(conn);
            broadcastRoomList();
        }
        cancelMatchSearch(conn);
        usernameByConnection.remove(conn);
        accountByConnection.remove(conn);
        logger.log("connection closed: " + reason);
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
            case Protocol.TYPE_CREATE_ROOM:
                handleCreateRoom(conn, parsed);
                break;
            case Protocol.TYPE_JOIN_ROOM:
                handleJoinRoom(conn, parsed);
                break;
            case Protocol.TYPE_LEAVE_ROOM:
                handleLeaveRoom(conn);
                break;
            case Protocol.TYPE_FIND_MATCH:
                handleFindMatch(conn);
                break;
            case Protocol.TYPE_CANCEL_MATCH:
                cancelMatchSearch(conn);
                break;
            case Protocol.TYPE_MOVE:
                withRoom(conn, room -> room.handleMove(conn, parsed));
                break;
            case Protocol.TYPE_JUMP:
                withRoom(conn, room -> room.handleJump(conn, parsed));
                break;
            default:
                conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "unknown type " + type));
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        logger.log("error on " + (conn == null ? "server" : conn.getRemoteSocketAddress()) + ": " + ex);
    }

    /**
     * CTD 26 spec slide 5: username + password, verified against SQLite (or
     * InMemoryAccountRepository's in-memory equivalent) via
     * AccountRepository.authenticate - see that method's class doc for the
     * "first login claims the username" policy. A failed authentication
     * (wrong password for an existing account) never puts anything into
     * usernameByConnection/accountByConnection, so this connection stays
     * exactly as unauthenticated as it was before the attempt - it can
     * simply try LOGIN again.
     */
    private void handleLogin(WebSocket conn, Map<String, Object> message) {
        String username = Protocol.getString(message, "username");
        if (username == null || username.isEmpty()) {
            conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "username required"));
            return;
        }
        String password = Protocol.getString(message, "password");
        if (password == null || password.isEmpty()) {
            conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "password required"));
            return;
        }

        Account account;
        try {
            account = accountRepository.authenticate(username, password);
        } catch (AuthenticationException e) {
            conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "invalid username or password"));
            return;
        }

        usernameByConnection.put(conn, username);
        accountByConnection.put(conn, account);
        conn.send(Protocol.write(Protocol.TYPE_ACCOUNT_INFO,
                "username", account.getUsername(),
                "elo", account.getElo(),
                "gamesPlayed", account.getGamesPlayed(),
                "wins", account.getWins(),
                "losses", account.getLosses()));
    }

    /**
     * Creates a new room named per the message's "name" field (auto-generated
     * if blank), rejecting the request if that name is already taken by an
     * open room - room ids are just their (unique) display name, so there's
     * no separate id-vs-name distinction for the lobby dialog to juggle.
     * The creator is seated as White immediately (a fresh room always has
     * both seats open, so assignSeat always gives the creator White - see
     * Room.assignSeat).
     */
    private void handleCreateRoom(WebSocket conn, Map<String, Object> message) {
        if (!usernameByConnection.containsKey(conn)) {
            conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "log in before creating a room"));
            return;
        }
        if (roomByConnection.containsKey(conn)) {
            conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "already in a room - leave it first"));
            return;
        }

        String requestedName = Protocol.getString(message, "name");
        String name = (requestedName == null || requestedName.trim().isEmpty())
                ? "Room-" + roomCounter.incrementAndGet()
                : requestedName.trim();

        if (roomsById.containsKey(name)) {
            conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "a room named '" + name + "' already exists"));
            return;
        }

        Room room = new Room(name, boardFactory, accountRepository, resignGraceMillis,
                disconnectExecutor, logger, usernameByConnection, accountByConnection);
        roomsById.put(name, room);
        roomByConnection.put(conn, room);
        room.assignSeat(conn);

        logger.log(usernameByConnection.get(conn) + " created room '" + name + "'");
        broadcastRoomList();
    }

    private void handleJoinRoom(WebSocket conn, Map<String, Object> message) {
        if (!usernameByConnection.containsKey(conn)) {
            conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "log in before joining a room"));
            return;
        }
        if (roomByConnection.containsKey(conn)) {
            conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "already in a room - leave it first"));
            return;
        }

        String roomId = Protocol.getString(message, "roomId");
        Room room = roomId == null ? null : roomsById.get(roomId);
        if (room == null) {
            conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "no such room: " + roomId));
            return;
        }

        roomByConnection.put(conn, room);
        if (!room.tryResumeDisconnectedSeat(conn, usernameByConnection.get(conn))) {
            if (room.hasOpenSeat()) {
                room.assignSeat(conn);
            } else {
                room.addSpectator(conn);
            }
        }
        broadcastRoomList();
    }

    private void handleLeaveRoom(WebSocket conn) {
        Room room = roomByConnection.remove(conn);
        if (room == null) {
            conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "not currently in a room"));
            return;
        }
        room.removeConnection(conn);
        broadcastRoomList();
    }

    /**
     * CTD 26 spec slide 6's "Play" button: looks for another connection
     * already waiting whose ELO is within MATCH_ELO_RANGE of this one's. If
     * one is found, both are removed from the waiting list and seated
     * together in a brand-new auto-named room (same seat-assignment
     * mechanics CREATE_ROOM/JOIN_ROOM already use - the waiting player, who
     * arrived first, gets White). If not, this connection joins the waiting
     * list itself with a MATCH_SEARCH_TIMEOUT_MILLIS countdown that sends
     * MATCH_NOT_FOUND if nobody suitable shows up in time.
     */
    private void handleFindMatch(WebSocket conn) {
        if (!usernameByConnection.containsKey(conn)) {
            conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "log in before playing"));
            return;
        }
        if (roomByConnection.containsKey(conn)) {
            conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "already in a room - leave it first"));
            return;
        }

        int myElo = accountByConnection.get(conn).getElo();

        WaitingPlayer opponent = null;
        synchronized (waitingPlayersLock) {
            for (Iterator<WaitingPlayer> it = waitingPlayers.iterator(); it.hasNext(); ) {
                WaitingPlayer candidate = it.next();
                if (Math.abs(candidate.elo - myElo) <= MATCH_ELO_RANGE) {
                    it.remove();
                    opponent = candidate;
                    break;
                }
            }
            if (opponent == null) {
                if (waitingPlayers.stream().anyMatch(w -> w.conn == conn)) {
                    return; // already searching - a duplicate FIND_MATCH is a harmless no-op
                }
                ScheduledFuture<?> timeoutTask = disconnectExecutor.schedule(
                        () -> onMatchSearchTimedOut(conn), matchSearchTimeoutMillis, TimeUnit.MILLISECONDS);
                waitingPlayers.add(new WaitingPlayer(conn, myElo, timeoutTask));
            }
        }

        if (opponent == null) {
            conn.send(Protocol.write(Protocol.TYPE_WAITING, "message", "searching for an opponent within " + MATCH_ELO_RANGE + " ELO"));
            logger.log(usernameByConnection.get(conn) + " (ELO " + myElo + ") is searching for a quick match");
            return;
        }

        opponent.timeoutTask.cancel(false);
        String roomId = "Match-" + matchRoomCounter.incrementAndGet();
        Room room = new Room(roomId, boardFactory, accountRepository, resignGraceMillis,
                disconnectExecutor, logger, usernameByConnection, accountByConnection);
        roomsById.put(roomId, room);
        roomByConnection.put(opponent.conn, room);
        roomByConnection.put(conn, room);
        room.assignSeat(opponent.conn); // arrived first -> White
        room.assignSeat(conn);          // matched second -> Black

        logger.log("quick match: " + usernameByConnection.get(opponent.conn) + " (ELO " + opponent.elo + ") vs "
                + usernameByConnection.get(conn) + " (ELO " + myElo + ") in room '" + roomId + "'");
        broadcastRoomList();
    }

    private void onMatchSearchTimedOut(WebSocket conn) {
        boolean wasWaiting;
        synchronized (waitingPlayersLock) {
            wasWaiting = waitingPlayers.removeIf(w -> w.conn == conn);
        }
        if (wasWaiting && conn.isOpen()) {
            conn.send(Protocol.write(Protocol.TYPE_MATCH_NOT_FOUND,
                    "message", "no opponent found within " + MATCH_ELO_RANGE + " ELO in "
                            + (matchSearchTimeoutMillis / 1000) + "s"));
        }
    }

    /** Removes {@code conn} from the quick-match waiting list if it's on it (idempotent - a no-op if it isn't), cancelling its timeout task either way. Used for both an explicit CANCEL_MATCH and cleanup on disconnect. */
    private void cancelMatchSearch(WebSocket conn) {
        synchronized (waitingPlayersLock) {
            for (Iterator<WaitingPlayer> it = waitingPlayers.iterator(); it.hasNext(); ) {
                WaitingPlayer candidate = it.next();
                if (candidate.conn == conn) {
                    candidate.timeoutTask.cancel(false);
                    it.remove();
                    return;
                }
            }
        }
    }

    private interface RoomAction {
        void run(Room room);
    }

    private void withRoom(WebSocket conn, RoomAction action) {
        Room room = roomByConnection.get(conn);
        if (room == null) {
            conn.send(Protocol.write(Protocol.TYPE_ERROR, "message", "not currently in a room"));
            return;
        }
        action.run(room);
    }

    /**
     * Runs every open room's tick on a single shared daemon thread, once per
     * ~33ms interval - each Room.tick call advances that room's own clock
     * and broadcasts to that room's own members only (see Room.tick). One
     * shared thread for every room (rather than one thread per room) keeps
     * this simple and is more than fast enough at the scale a single
     * process's rooms will ever reach - ticking N rooms is O(N) trivial work
     * per interval, not N concurrent blocking operations.
     */
    public void startGameLoop() {
        Thread loop = new Thread(() -> {
            long tickMillis = 33;
            while (true) {
                for (Room room : roomsById.values()) {
                    room.tick(tickMillis);
                }
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

    private void broadcastRoomList() {
        broadcast(buildRoomListMessage());
    }

    private String buildRoomListMessage() {
        List<Object> summaries = new ArrayList<>();
        for (Room room : roomsById.values()) {
            summaries.add(room.summarize());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", Protocol.TYPE_ROOM_LIST);
        payload.put("rooms", summaries);
        return Json.write(payload);
    }
}

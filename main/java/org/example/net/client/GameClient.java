package org.example.net.client;

import org.example.account.Account;
import org.example.bus.EventBus;
import org.example.bus.GameEndedEvent;
import org.example.bus.GameStartedEvent;
import org.example.bus.MoveLoggedEvent;
import org.example.bus.ScoreUpdatedEvent;
import org.example.controller.MoveHistoryEntry;
import org.example.engine.GameSnapshot;
import org.example.engine.PieceSnapshot;
import org.example.engine.VisualState;
import org.example.model.Piece;
import org.example.model.Position;
import org.example.net.protocol.Json;
import org.example.net.protocol.Protocol;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The client-side half of Phase 2: connects to a GameServer, sends this
 * player's clicks over the wire as MOVE/JUMP commands, and turns whatever
 * the server broadcasts back into exactly the same shapes the existing,
 * unmodified view layer already knows how to consume - a GameSnapshot for
 * BoardView, and the same bus events (ScoreUpdatedEvent, MoveLoggedEvent,
 * GameStartedEvent, GameEndedEvent) for GamePanel/SoundPlayer.
 *
 * This is what lets BoardView, GamePanel, ImgRenderer, and SoundPlayer be
 * reused completely unchanged for networked play (see BoardView's and
 * GamePanel's class docs, both already built around a Supplier<GameSnapshot>
 * and an EventBus rather than a concrete GameController/live engine
 * reference): as far as those classes are concerned, "a GameClient
 * republishing server messages" is indistinguishable from "a local
 * MovementEngine/InteractionHandler publishing directly" - both are just
 * something that hands them a GameSnapshot and fires bus events.
 *
 * The one piece of local, client-only UI state a real player needs -
 * "which square do I currently have selected" - deliberately does NOT live
 * here: it's owned by NetworkInputReceiver, since it is purely a per-viewer
 * click-tracking concern the server has no reason to know about (see
 * GameServer.buildStateMessage's class doc). What DOES live here,
 * mirroring InteractionHandler's own fields, is rejection feedback
 * (lastRejectedPosition/At) - the server is the only thing that can know a
 * MOVE/JUMP was illegal, so it tells this class via MOVE_REJECTED/
 * JUMP_REJECTED and this class remembers it exactly the way
 * InteractionHandler remembers its own local rejections.
 */
public final class GameClient extends WebSocketClient {

    // Every composition root in this project (GuiMain, ServerMain) hardcodes
    // the same 8x8 starting position - there is no variable-board-size
    // support anywhere in the codebase to preserve here, so the network
    // client assumes the same fixed dimensions rather than waiting on the
    // server to tell it (which would leave a window with nothing to render
    // before the first STATE message arrives).
    private static final int BOARD_WIDTH = 8;
    private static final int BOARD_HEIGHT = 8;

    private final String username;
    private final EventBus localBus;
    private final Runnable onStateUpdated;

    private volatile Piece.Color myColor;
    private final AtomicReference<GameSnapshot> baseSnapshot = new AtomicReference<>(emptySnapshot());
    private volatile Position lastRejectedPosition;
    private volatile long lastRejectedAtMillis = Long.MIN_VALUE;

    // Phase 3 (accounts + ELO): this player's persistent profile, as last
    // reported by the server (ACCOUNT_INFO on login) - see Account's class
    // doc. lastGameEndedSummary is a ready-to-display human string built the
    // moment a GAME_ENDED message carrying rating-change fields arrives, so
    // NetworkGuiMain doesn't need its own ELO-formatting logic - see
    // onGameEnded below.
    private volatile Account myAccount;
    private volatile String lastGameEndedSummary;

    // Phase 4 (matchmaking + disconnect/auto-resign): a single human-readable
    // status line reflecting WAITING/OPPONENT_DISCONNECTED/
    // OPPONENT_RECONNECTED, for NetworkGuiMain to show in the window title
    // without needing its own copy of this formatting logic. Cleared back to
    // null once a match actually starts (COLOR_ASSIGNED) - see onMessage.
    private volatile String connectionStatus;

    public GameClient(URI serverUri, String username, EventBus localBus, Runnable onStateUpdated) {
        super(serverUri);
        this.username = username;
        this.localBus = localBus;
        this.onStateUpdated = onStateUpdated;
    }

    private static GameSnapshot emptySnapshot() {
        return new GameSnapshot(BOARD_WIDTH, BOARD_HEIGHT, new ArrayList<>(), null, null, -1, 0, false, null);
    }

    public Piece.Color getMyColor() {
        return myColor;
    }

    /** This player's persistent account info as last reported by the server, or null before the ACCOUNT_INFO response to LOGIN has arrived. */
    public Account getMyAccount() {
        return myAccount;
    }

    /** A ready-to-display summary of the most recent game's rating changes (e.g. "Alice: 1200 -> 1215 (+15) | Bob: 1200 -> 1185 (-15)"), or null if the most recent GAME_ENDED carried no rating fields (see GameServer.onGameEnded's class doc for when that happens). */
    public String getLastGameEndedSummary() {
        return lastGameEndedSummary;
    }

    /** A ready-to-display matchmaking/connection status line (waiting for an opponent, opponent disconnected with a countdown, opponent reconnected), or null once a match is actively underway with both sides present - see the field's own doc. */
    public String getConnectionStatus() {
        return connectionStatus;
    }

    public Position getLastRejectedPosition() {
        return lastRejectedPosition;
    }

    public long getLastRejectedAtMillis() {
        return lastRejectedAtMillis;
    }

    /** The latest server-reported state, with selectedPosition/rejectedPosition left null/absent - see class doc. Never null: starts out as an empty 8x8 board so BoardView always has something to draw, even before the connection finishes handshaking. */
    public GameSnapshot getBaseSnapshot() {
        return baseSnapshot.get();
    }

    public void sendMove(Position from, Position to) {
        send(Protocol.write(Protocol.TYPE_MOVE,
                "from", org.example.model.Square.toAlgebraic(from, BOARD_HEIGHT),
                "to", org.example.model.Square.toAlgebraic(to, BOARD_HEIGHT)));
    }

    public void sendJump(Position at) {
        send(Protocol.write(Protocol.TYPE_JUMP,
                "square", org.example.model.Square.toAlgebraic(at, BOARD_HEIGHT)));
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        send(Protocol.write(Protocol.TYPE_LOGIN, "username", username));
    }

    @Override
    public void onMessage(String message) {
        Map<String, Object> parsed;
        try {
            parsed = Json.parseObject(message);
        } catch (RuntimeException e) {
            System.err.println("[GameClient] received malformed JSON: " + e);
            return;
        }

        String type = Protocol.getString(parsed, "type");
        if (type == null) return;

        switch (type) {
            case Protocol.TYPE_COLOR_ASSIGNED:
                String colorName = Protocol.getString(parsed, "color");
                myColor = "SPECTATOR".equals(colorName) ? null : Piece.Color.valueOf(colorName);
                connectionStatus = null; // a match just started (or was resumed) - no longer waiting/disconnected
                break;
            case Protocol.TYPE_WAITING:
                connectionStatus = "Waiting for an opponent (queue position "
                        + (int) Protocol.getNumber(parsed, "queuePosition") + ")";
                break;
            case Protocol.TYPE_OPPONENT_DISCONNECTED:
                connectionStatus = Protocol.getString(parsed, "username") + " disconnected - auto-win in "
                        + (int) Protocol.getNumber(parsed, "graceSeconds") + "s if they don't reconnect";
                break;
            case Protocol.TYPE_OPPONENT_RECONNECTED:
                connectionStatus = Protocol.getString(parsed, "username") + " reconnected - game resumed";
                break;
            case Protocol.TYPE_ACCOUNT_INFO:
                myAccount = new Account(
                        Protocol.getString(parsed, "username"),
                        (int) Protocol.getNumber(parsed, "elo"),
                        (int) Protocol.getNumber(parsed, "gamesPlayed"),
                        (int) Protocol.getNumber(parsed, "wins"),
                        (int) Protocol.getNumber(parsed, "losses"));
                System.out.println("[GameClient] logged in as " + myAccount.getUsername()
                        + " - ELO " + myAccount.getElo()
                        + " (" + myAccount.getWins() + "W/" + myAccount.getLosses() + "L)");
                break;
            case Protocol.TYPE_STATE:
                onState(parsed);
                break;
            case Protocol.TYPE_SCORE:
                localBus.publish(new ScoreUpdatedEvent(
                        Piece.Color.valueOf(Protocol.getString(parsed, "color")),
                        (int) Protocol.getNumber(parsed, "score")));
                break;
            case Protocol.TYPE_MOVE_LOG:
                MoveHistoryEntry entry = new MoveHistoryEntry(
                        Piece.Color.valueOf(Protocol.getString(parsed, "color")),
                        (long) Protocol.getNumber(parsed, "timeMillis"),
                        Protocol.getString(parsed, "notation"));
                localBus.publish(new MoveLoggedEvent(entry, Protocol.getBoolean(parsed, "capture")));
                break;
            case Protocol.TYPE_GAME_STARTED:
                localBus.publish(new GameStartedEvent());
                break;
            case Protocol.TYPE_GAME_ENDED:
                lastGameEndedSummary = buildGameEndedSummary(parsed);
                localBus.publish(new GameEndedEvent(Piece.Color.valueOf(Protocol.getString(parsed, "winner"))));
                break;
            case Protocol.TYPE_MOVE_REJECTED:
            case Protocol.TYPE_JUMP_REJECTED:
                onRejected(parsed);
                break;
            case Protocol.TYPE_ERROR:
                System.err.println("[GameClient] server error: " + Protocol.getString(parsed, "message"));
                break;
            default:
                // Forward-compatible: an unrecognized message type from a
                // newer server is silently ignored rather than crashing the
                // connection.
        }
    }

    @SuppressWarnings("unchecked")
    private void onState(Map<String, Object> message) {
        int boardWidth = (int) Protocol.getNumber(message, "boardWidth");
        int boardHeight = (int) Protocol.getNumber(message, "boardHeight");
        long gameTimeMillis = (long) Protocol.getNumber(message, "gameTimeMillis");
        boolean gameOver = Protocol.getBoolean(message, "gameOver");
        String winnerName = Protocol.getString(message, "winner");
        Piece.Color winner = winnerName == null ? null : Piece.Color.valueOf(winnerName);

        List<PieceSnapshot> pieces = new ArrayList<>();
        Object piecesRaw = message.get("pieces");
        if (piecesRaw instanceof List) {
            for (Object item : (List<Object>) piecesRaw) {
                Map<String, Object> p = (Map<String, Object>) item;
                pieces.add(new PieceSnapshot(
                        Protocol.getString(p, "id"),
                        Piece.Color.valueOf(Protocol.getString(p, "color")),
                        Piece.Type.valueOf(Protocol.getString(p, "type")),
                        Protocol.getNumber(p, "x"),
                        Protocol.getNumber(p, "y"),
                        VisualState.valueOf(Protocol.getString(p, "state")),
                        (long) Protocol.getNumber(p, "elapsed")));
            }
        }

        baseSnapshot.set(new GameSnapshot(boardWidth, boardHeight, pieces, null, null, -1,
                gameTimeMillis, gameOver, winner));

        if (onStateUpdated != null) {
            javax.swing.SwingUtilities.invokeLater(onStateUpdated);
        }
    }

    /**
     * Builds a human-readable rating-change summary from a GAME_ENDED
     * message, and - if this client's own account was one of the two
     * participants - updates myAccount in place so the player's displayed
     * ELO reflects the game they just finished immediately, without waiting
     * for a fresh LOGIN round-trip. Returns null if the message carries no
     * rating fields at all (see GameServer.onGameEnded's class doc for when
     * that happens - e.g. a spectator-only session).
     */
    private String buildGameEndedSummary(Map<String, Object> message) {
        String winnerUsername = Protocol.getString(message, "winnerUsername");
        String loserUsername = Protocol.getString(message, "loserUsername");
        if (winnerUsername == null || loserUsername == null) {
            return null;
        }

        int winnerNewElo = (int) Protocol.getNumber(message, "winnerNewElo");
        int winnerDelta = (int) Protocol.getNumber(message, "winnerEloDelta");
        int loserNewElo = (int) Protocol.getNumber(message, "loserNewElo");
        int loserDelta = (int) Protocol.getNumber(message, "loserEloDelta");

        Account current = myAccount;
        if (current != null && current.getUsername().equals(winnerUsername)) {
            myAccount = new Account(winnerUsername, winnerNewElo, current.getGamesPlayed() + 1, current.getWins() + 1, current.getLosses());
        } else if (current != null && current.getUsername().equals(loserUsername)) {
            myAccount = new Account(loserUsername, loserNewElo, current.getGamesPlayed() + 1, current.getWins(), current.getLosses() + 1);
        }

        return winnerUsername + ": " + (winnerNewElo - winnerDelta) + " -> " + winnerNewElo
                + " (" + (winnerDelta >= 0 ? "+" : "") + winnerDelta + ")"
                + "  |  " + loserUsername + ": " + (loserNewElo - loserDelta) + " -> " + loserNewElo
                + " (" + (loserDelta >= 0 ? "+" : "") + loserDelta + ")";
    }

    private void onRejected(Map<String, Object> message) {
        String squareName = Protocol.getString(message, "square");
        if (squareName == null) return;
        org.example.model.Square.fromAlgebraic(squareName, BOARD_HEIGHT).ifPresent(pos -> {
            lastRejectedPosition = pos;
            lastRejectedAtMillis = baseSnapshot.get().getGameTimeMillis();
        });
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("[GameClient] connection closed: " + reason);
    }

    @Override
    public void onError(Exception ex) {
        System.err.println("[GameClient] error: " + ex);
    }
}

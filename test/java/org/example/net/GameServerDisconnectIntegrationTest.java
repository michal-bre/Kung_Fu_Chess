package org.example.net;

import org.example.account.InMemoryAccountRepository;
import org.example.adapters.BoardParser;
import org.example.model.Board;
import org.example.net.protocol.Json;
import org.example.net.protocol.Protocol;
import org.example.net.server.GameServer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Real, no-mocking end-to-end coverage for Phase 4's disconnect/auto-resign
 * handling: a real GameServer, driven by real org.java_websocket clients,
 * with a short resignGraceMillis (see GameServer's constructor) so these
 * tests don't need to wait out a real 30-second grace period - a few hundred
 * milliseconds is enough to exercise the same code path.
 *
 * Kept in its own file, separate from GameServerIntegrationTest, because
 * every test here needs this short grace period and real wall-clock waits
 * around it, which the plain connectivity/matchmaking tests don't.
 */
public class GameServerDisconnectIntegrationTest {

    private static final long GRACE_MILLIS = 800;

    private GameServer server;
    private RecordingClient white;
    private RecordingClient black;
    private int port;

    @Before
    public void startServer() throws Exception {
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
        Board board = BoardParser.parse(startingPosition);

        port = 27000 + (int) (System.nanoTime() % 3000);
        server = new GameServer(port, () -> board, new InMemoryAccountRepository(), GRACE_MILLIS);
        server.start();
        server.startGameLoop();
        Thread.sleep(300);
    }

    @After
    public void stopServer() throws Exception {
        if (white != null && white.isOpen()) white.closeBlocking();
        if (black != null && black.isOpen()) black.closeBlocking();
        server.stop(0);
    }

    /** Logs both connections in, in a deterministic order (see GameServerIntegrationTest's class doc for why LOGIN sends need to be serialized this way), has white create {@code roomName} and black join it, and waits for both COLOR_ASSIGNED messages before returning. */
    private void loginBothAndPlayInRoom(BlockingQueue<Map<String, Object>> whiteMessages, BlockingQueue<Map<String, Object>> blackMessages, String roomName) throws InterruptedException {
        white.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Alice", "password", "pw")));
        takeOfType(whiteMessages, Protocol.TYPE_ACCOUNT_INFO);
        black.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Bob", "password", "pw")));
        takeOfType(blackMessages, Protocol.TYPE_ACCOUNT_INFO);

        white.send(Json.write(Protocol.msg(Protocol.TYPE_CREATE_ROOM, "name", roomName)));
        takeOfType(whiteMessages, Protocol.TYPE_COLOR_ASSIGNED);
        black.send(Json.write(Protocol.msg(Protocol.TYPE_JOIN_ROOM, "roomId", roomName)));
        takeOfType(blackMessages, Protocol.TYPE_COLOR_ASSIGNED);
    }

    @Test
    public void disconnectingMidGameNotifiesTheOpponentWithAGraceCountdown() throws Exception {
        BlockingQueue<Map<String, Object>> whiteMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> blackMessages = new LinkedBlockingQueue<>();
        white = new RecordingClient(new URI("ws://localhost:" + port), whiteMessages);
        assertTrue(white.connectBlocking(5, TimeUnit.SECONDS));
        black = new RecordingClient(new URI("ws://localhost:" + port), blackMessages);
        assertTrue(black.connectBlocking(5, TimeUnit.SECONDS));
        loginBothAndPlayInRoom(whiteMessages, blackMessages, "disconnect-notify-room");

        white.closeBlocking();

        Map<String, Object> notice = takeOfType(blackMessages, Protocol.TYPE_OPPONENT_DISCONNECTED);
        assertEquals("WHITE", notice.get("color"));
        assertEquals("Alice", notice.get("username"));
    }

    @Test
    public void reconnectingWithTheSameUsernameBeforeGraceExpiresResumesTheSameSeat() throws Exception {
        BlockingQueue<Map<String, Object>> whiteMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> blackMessages = new LinkedBlockingQueue<>();
        white = new RecordingClient(new URI("ws://localhost:" + port), whiteMessages);
        assertTrue(white.connectBlocking(5, TimeUnit.SECONDS));
        black = new RecordingClient(new URI("ws://localhost:" + port), blackMessages);
        assertTrue(black.connectBlocking(5, TimeUnit.SECONDS));
        loginBothAndPlayInRoom(whiteMessages, blackMessages, "reconnect-room");

        white.closeBlocking();
        takeOfType(blackMessages, Protocol.TYPE_OPPONENT_DISCONNECTED);

        // Reconnect well within the 800ms grace period, as a brand-new
        // WebSocket connection (exactly what a real client restarting would
        // produce) - same username, and explicitly re-joining the same room:
        // with multiple concurrent rooms possible (Phase 5), the server has
        // no way to guess which disconnected seat a bare LOGIN is trying to
        // resume, so the reconnecting client has to name the room, exactly
        // like joining fresh - Room.tryResumeDisconnectedSeat is what
        // notices the username matches a seat already on this room's grace
        // clock and resumes it instead of adding a new spectator/player.
        BlockingQueue<Map<String, Object>> reconnectMessages = new LinkedBlockingQueue<>();
        RecordingClient reconnected = new RecordingClient(new URI("ws://localhost:" + port), reconnectMessages);
        assertTrue(reconnected.connectBlocking(5, TimeUnit.SECONDS));
        reconnected.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Alice", "password", "pw")));
        takeOfType(reconnectMessages, Protocol.TYPE_ACCOUNT_INFO);
        reconnected.send(Json.write(Protocol.msg(Protocol.TYPE_JOIN_ROOM, "roomId", "reconnect-room")));

        Map<String, Object> colorAssigned = takeOfType(reconnectMessages, Protocol.TYPE_COLOR_ASSIGNED);
        assertEquals("WHITE", colorAssigned.get("color"));

        Map<String, Object> reconnectNotice = takeOfType(blackMessages, Protocol.TYPE_OPPONENT_RECONNECTED);
        assertEquals("WHITE", reconnectNotice.get("color"));
        assertEquals("Alice", reconnectNotice.get("username"));

        // The game must still be in progress (not force-resigned) - a MOVE
        // from the reconnected white seat should be accepted normally.
        // STATE broadcasts keep arriving every ~33ms regardless, so scan
        // within a bounded window rather than looping until the queue goes
        // quiet (it never does).
        reconnected.send(Json.write(Protocol.msg(Protocol.TYPE_MOVE, "from", "e2", "to", "e4")));
        long moveCheckDeadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < moveCheckDeadline) {
            Map<String, Object> msg = reconnectMessages.poll(200, TimeUnit.MILLISECONDS);
            if (msg == null) continue;
            assertNotEquals("a move from the resumed seat should not be rejected",
                    Protocol.TYPE_MOVE_REJECTED, msg.get("type"));
        }

        reconnected.closeBlocking();
    }

    @Test
    public void failingToReconnectBeforeGraceExpiresForcesAResignation() throws Exception {
        BlockingQueue<Map<String, Object>> whiteMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> blackMessages = new LinkedBlockingQueue<>();
        white = new RecordingClient(new URI("ws://localhost:" + port), whiteMessages);
        assertTrue(white.connectBlocking(5, TimeUnit.SECONDS));
        black = new RecordingClient(new URI("ws://localhost:" + port), blackMessages);
        assertTrue(black.connectBlocking(5, TimeUnit.SECONDS));
        loginBothAndPlayInRoom(whiteMessages, blackMessages, "force-resign-room");

        white.closeBlocking();
        takeOfType(blackMessages, Protocol.TYPE_OPPONENT_DISCONNECTED);

        // Let the grace period elapse without reconnecting.
        Map<String, Object> gameEnded = takeOfType(blackMessages, Protocol.TYPE_GAME_ENDED, GRACE_MILLIS + 4000);
        assertEquals("BLACK", gameEnded.get("winner"));
    }

    @Test
    public void aFinishedRoomDoesNotPreventABrandNewRoomFromBeingCreatedAndJoined() throws Exception {
        BlockingQueue<Map<String, Object>> whiteMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> blackMessages = new LinkedBlockingQueue<>();
        white = new RecordingClient(new URI("ws://localhost:" + port), whiteMessages);
        assertTrue(white.connectBlocking(5, TimeUnit.SECONDS));
        black = new RecordingClient(new URI("ws://localhost:" + port), blackMessages);
        assertTrue(black.connectBlocking(5, TimeUnit.SECONDS));
        loginBothAndPlayInRoom(whiteMessages, blackMessages, "old-room");

        white.closeBlocking();
        takeOfType(blackMessages, Protocol.TYPE_OPPONENT_DISCONNECTED);
        takeOfType(blackMessages, Protocol.TYPE_GAME_ENDED, GRACE_MILLIS + 4000);

        // Since Phase 5, a finished room just stays finished - there's no
        // implicit "table" that frees itself for the next pair. A brand-new
        // pair of players creating/joining their OWN new room must still
        // work fine regardless of what happened in the old one (this is
        // really a room-isolation check wearing a disconnect-flow costume).
        BlockingQueue<Map<String, Object>> carolMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> daveMessages = new LinkedBlockingQueue<>();
        RecordingClient carol = new RecordingClient(new URI("ws://localhost:" + port), carolMessages);
        assertTrue(carol.connectBlocking(5, TimeUnit.SECONDS));
        RecordingClient dave = new RecordingClient(new URI("ws://localhost:" + port), daveMessages);
        assertTrue(dave.connectBlocking(5, TimeUnit.SECONDS));

        carol.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Carol", "password", "pw")));
        takeOfType(carolMessages, Protocol.TYPE_ACCOUNT_INFO);
        dave.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Dave", "password", "pw")));
        takeOfType(daveMessages, Protocol.TYPE_ACCOUNT_INFO);

        carol.send(Json.write(Protocol.msg(Protocol.TYPE_CREATE_ROOM, "name", "new-room")));
        Map<String, Object> carolColor = takeOfType(carolMessages, Protocol.TYPE_COLOR_ASSIGNED);
        dave.send(Json.write(Protocol.msg(Protocol.TYPE_JOIN_ROOM, "roomId", "new-room")));
        Map<String, Object> daveColor = takeOfType(daveMessages, Protocol.TYPE_COLOR_ASSIGNED);
        assertEquals("WHITE", carolColor.get("color"));
        assertEquals("BLACK", daveColor.get("color"));

        carol.closeBlocking();
        dave.closeBlocking();
    }

    private static Map<String, Object> takeOfType(BlockingQueue<Map<String, Object>> queue, String type) throws InterruptedException {
        return takeOfType(queue, type, 5000);
    }

    private static Map<String, Object> takeOfType(BlockingQueue<Map<String, Object>> queue, String type, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> msg = queue.poll(300, TimeUnit.MILLISECONDS);
            if (msg == null) continue;
            if (type.equals(msg.get("type"))) return msg;
        }
        throw new AssertionError("timed out waiting for a message of type " + type);
    }

    private static final class RecordingClient extends WebSocketClient {
        private final BlockingQueue<Map<String, Object>> out;

        RecordingClient(URI uri, BlockingQueue<Map<String, Object>> out) {
            super(uri);
            this.out = out;
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
        }

        @Override
        public void onMessage(String message) {
            out.add(Json.parseObject(message));
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
        }

        @Override
        public void onError(Exception ex) {
        }
    }
}

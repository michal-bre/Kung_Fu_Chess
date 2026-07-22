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
 * A real, no-mocking end-to-end test of the networked stack's core
 * connectivity (CTD 26 spec slides 3-4): starts an actual GameServer on a
 * real loopback port and drives it with plain org.java_websocket clients
 * (not GameClient) that exercise exactly the same bytes a real player's
 * client would send/receive - so a bug in Json/Protocol/GameServer's message
 * handling would show up here even if GameClient's own deserialization
 * happened to mask it.
 *
 * Since Phase 5, LOGIN alone no longer puts anyone into a game - a
 * connection has to CREATE_ROOM or JOIN_ROOM explicitly (see GameServer's
 * class doc). Every test here that needs an actual match therefore has
 * white create a room and black join it, rather than the Phase 4 shape
 * where logging in was enough. See RoomIntegrationTest for coverage of the
 * room lifecycle itself (creation, the open-room list, spectating a full
 * room) and GameServerDisconnectIntegrationTest for disconnect/auto-resign/
 * reconnect, both kept in their own files.
 *
 * Every other test in this project exercises the engine/controller layers
 * directly, in-process, with no real I/O - these are the ones that go over
 * an actual socket, because a wire protocol two independent processes have
 * to agree on can't be verified any other way.
 *
 * Uses a random, unprivileged, unlikely-to-collide port (chosen once from a
 * high range at class-load time) rather than a hardcoded one - CI/dev
 * machines may run several of these in parallel or in quick succession
 * across other tools.
 */
public class GameServerIntegrationTest {

    private GameServer server;
    private RecordingClient white;
    private RecordingClient black;
    private int port;

    @Before
    public void startServer() throws Exception {
        port = 20000 + (int) (System.nanoTime() % 10000);
        server = new GameServer(port, GameServerIntegrationTest::standardStartingBoard, new InMemoryAccountRepository());
        server.start();
        server.startGameLoop();
        Thread.sleep(300); // give the selector thread time to actually bind
    }

    @After
    public void stopServer() throws Exception {
        if (white != null) white.closeBlocking();
        if (black != null) black.closeBlocking();
        server.stop(0);
    }

    private static Board standardStartingBoard() {
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
     * Logs both connections in (serialized via white's ACCOUNT_INFO ack, so
     * the two LOGIN sends - which travel over separate connections' own I/O
     * threads with no ordering guarantee between them - can't race), has
     * white create {@code roomName} and black join it, and waits for both
     * COLOR_ASSIGNED confirmations before returning.
     */
    private void createRoomAndJoin(BlockingQueue<Map<String, Object>> whiteMessages,
                                    BlockingQueue<Map<String, Object>> blackMessages, String roomName) throws InterruptedException {
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
    public void roomCreatorIsWhiteJoinerIsBlack() throws Exception {
        BlockingQueue<Map<String, Object>> whiteMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> blackMessages = new LinkedBlockingQueue<>();

        white = new RecordingClient(new URI("ws://localhost:" + port), whiteMessages);
        assertTrue(white.connectBlocking(5, TimeUnit.SECONDS));
        black = new RecordingClient(new URI("ws://localhost:" + port), blackMessages);
        assertTrue(black.connectBlocking(5, TimeUnit.SECONDS));

        white.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Alice", "password", "pw")));
        takeOfType(whiteMessages, Protocol.TYPE_ACCOUNT_INFO);
        black.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Bob", "password", "pw")));
        takeOfType(blackMessages, Protocol.TYPE_ACCOUNT_INFO);

        white.send(Json.write(Protocol.msg(Protocol.TYPE_CREATE_ROOM, "name", "table-1")));
        Map<String, Object> whiteColor = takeOfType(whiteMessages, Protocol.TYPE_COLOR_ASSIGNED);
        assertEquals("WHITE", whiteColor.get("color"));

        black.send(Json.write(Protocol.msg(Protocol.TYPE_JOIN_ROOM, "roomId", "table-1")));
        Map<String, Object> blackColor = takeOfType(blackMessages, Protocol.TYPE_COLOR_ASSIGNED);
        assertEquals("BLACK", blackColor.get("color"));
    }

    @Test
    public void acceptedMoveIsReflectedInBroadcastState() throws Exception {
        BlockingQueue<Map<String, Object>> whiteMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> blackMessages = new LinkedBlockingQueue<>();

        white = new RecordingClient(new URI("ws://localhost:" + port), whiteMessages);
        assertTrue(white.connectBlocking(5, TimeUnit.SECONDS));
        black = new RecordingClient(new URI("ws://localhost:" + port), blackMessages);
        assertTrue(black.connectBlocking(5, TimeUnit.SECONDS));
        createRoomAndJoin(whiteMessages, blackMessages, "table-2");

        white.send(Json.write(Protocol.msg(Protocol.TYPE_MOVE, "from", "e2", "to", "e4")));

        assertTrue("a white pawn should end up at e4's pixel coordinate (400,400) in a broadcast STATE",
                waitForPawnAt(whiteMessages, "WHITE", 400, 400));
    }

    @Test
    public void serverRejectsAConnectionMovingAPieceItDoesNotOwn() throws Exception {
        BlockingQueue<Map<String, Object>> whiteMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> blackMessages = new LinkedBlockingQueue<>();

        white = new RecordingClient(new URI("ws://localhost:" + port), whiteMessages);
        assertTrue(white.connectBlocking(5, TimeUnit.SECONDS));
        black = new RecordingClient(new URI("ws://localhost:" + port), blackMessages);
        assertTrue(black.connectBlocking(5, TimeUnit.SECONDS));
        createRoomAndJoin(whiteMessages, blackMessages, "table-3");

        // Black attempts to move a WHITE pawn - must be rejected regardless
        // of whether the move would otherwise be legal.
        black.send(Json.write(Protocol.msg(Protocol.TYPE_MOVE, "from", "d2", "to", "d4")));

        Map<String, Object> rejection = takeOfType(blackMessages, Protocol.TYPE_MOVE_REJECTED);
        assertEquals("not_your_piece", rejection.get("reason"));
    }

    @Test
    public void movingBeforeJoiningARoomIsRejectedWithAnError() throws Exception {
        BlockingQueue<Map<String, Object>> whiteMessages = new LinkedBlockingQueue<>();
        white = new RecordingClient(new URI("ws://localhost:" + port), whiteMessages);
        assertTrue(white.connectBlocking(5, TimeUnit.SECONDS));

        white.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Alice", "password", "pw")));
        takeOfType(whiteMessages, Protocol.TYPE_ACCOUNT_INFO);

        white.send(Json.write(Protocol.msg(Protocol.TYPE_MOVE, "from", "e2", "to", "e4")));
        Map<String, Object> error = takeOfType(whiteMessages, Protocol.TYPE_ERROR);
        assertEquals("not currently in a room", error.get("message"));
    }

    @SuppressWarnings("unchecked")
    private boolean waitForPawnAt(BlockingQueue<Map<String, Object>> queue, String color, double expectedX, double expectedY) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> msg = queue.poll(300, TimeUnit.MILLISECONDS);
            if (msg == null) continue;
            if (!Protocol.TYPE_STATE.equals(msg.get("type"))) continue;

            for (Object o : (List<Object>) msg.get("pieces")) {
                Map<String, Object> p = (Map<String, Object>) o;
                if (!color.equals(p.get("color")) || !"PAWN".equals(p.get("type"))) continue;
                double x = ((Number) p.get("x")).doubleValue();
                double y = ((Number) p.get("y")).doubleValue();
                if (Math.abs(x - expectedX) < 1 && Math.abs(y - expectedY) < 1) return true;
            }
        }
        return false;
    }

    private static Map<String, Object> takeOfType(BlockingQueue<Map<String, Object>> queue, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
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

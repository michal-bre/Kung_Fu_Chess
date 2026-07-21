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
 * connectivity + matchmaking (CTD 26 spec slides 3-4): starts an actual
 * GameServer on a real loopback port and drives it with plain
 * org.java_websocket clients (not GameClient) that exercise exactly the same
 * bytes a real player's client would send/receive - so a bug in
 * Json/Protocol/GameServer's message handling would show up here even if
 * GameClient's own deserialization happened to mask it.
 *
 * Since Phase 4, color assignment happens on LOGIN (via matchmaking), not on
 * raw connection open - every test here sends LOGIN before expecting
 * COLOR_ASSIGNED. See GameServerDisconnectIntegrationTest for the
 * disconnect/auto-resign/reconnect side of Phase 4, kept in its own file
 * since it needs a short resignGraceMillis and real wall-clock waits that
 * the plain connectivity tests here don't.
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

    @Test
    public void firstLoggedInPlayerIsWhiteSecondIsBlack() throws Exception {
        BlockingQueue<Map<String, Object>> whiteMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> blackMessages = new LinkedBlockingQueue<>();

        white = new RecordingClient(new URI("ws://localhost:" + port), whiteMessages);
        assertTrue(white.connectBlocking(5, TimeUnit.SECONDS));
        black = new RecordingClient(new URI("ws://localhost:" + port), blackMessages);
        assertTrue(black.connectBlocking(5, TimeUnit.SECONDS));

        // LOGIN is sent over each connection's own I/O thread with no ack
        // wait, so two sends this close together have no guaranteed arrival
        // order at the server unless we force one - waiting for white's
        // ACCOUNT_INFO (always the LOGIN handler's first response, sent
        // before any matchmaking decision) guarantees the server finished
        // processing white's LOGIN, and therefore queued white, before
        // black's LOGIN is even sent - otherwise this test would be
        // flaky about which connection actually becomes White.
        white.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Alice")));
        takeOfType(whiteMessages, Protocol.TYPE_ACCOUNT_INFO);
        black.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Bob")));

        Map<String, Object> whiteColor = takeOfType(whiteMessages, Protocol.TYPE_COLOR_ASSIGNED);
        Map<String, Object> blackColor = takeOfType(blackMessages, Protocol.TYPE_COLOR_ASSIGNED);

        assertEquals("WHITE", whiteColor.get("color"));
        assertEquals("BLACK", blackColor.get("color"));
    }

    @Test
    public void aLoneLoggedInPlayerWaitsUntilASecondOneArrives() throws Exception {
        BlockingQueue<Map<String, Object>> whiteMessages = new LinkedBlockingQueue<>();
        white = new RecordingClient(new URI("ws://localhost:" + port), whiteMessages);
        assertTrue(white.connectBlocking(5, TimeUnit.SECONDS));

        white.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Alice")));

        Map<String, Object> waiting = takeOfType(whiteMessages, Protocol.TYPE_WAITING);
        assertEquals(1.0, waiting.get("queuePosition"));

        // No COLOR_ASSIGNED should show up while still alone in the queue -
        // scan everything that arrives in a short window, ignoring the
        // STATE broadcasts the tick loop keeps sending regardless.
        long deadline = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> msg = whiteMessages.poll(200, TimeUnit.MILLISECONDS);
            if (msg == null) continue;
            assertNotEquals("no match should start with only one player queued",
                    Protocol.TYPE_COLOR_ASSIGNED, msg.get("type"));
        }
    }

    @Test
    public void aThirdConnectionQueuesInsteadOfJoiningTheActiveGame() throws Exception {
        BlockingQueue<Map<String, Object>> whiteMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> blackMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> thirdMessages = new LinkedBlockingQueue<>();

        white = new RecordingClient(new URI("ws://localhost:" + port), whiteMessages);
        assertTrue(white.connectBlocking(5, TimeUnit.SECONDS));
        black = new RecordingClient(new URI("ws://localhost:" + port), blackMessages);
        assertTrue(black.connectBlocking(5, TimeUnit.SECONDS));
        RecordingClient third = new RecordingClient(new URI("ws://localhost:" + port), thirdMessages);
        assertTrue(third.connectBlocking(5, TimeUnit.SECONDS));

        // LOGIN is sent over each connection's own I/O thread with no ack
        // wait, so two sends this close together have no guaranteed arrival
        // order at the server unless we force one - waiting for white's
        // ACCOUNT_INFO (always the LOGIN handler's first response, sent
        // before any matchmaking decision) guarantees the server finished
        // processing white's LOGIN, and therefore queued white, before
        // black's LOGIN is even sent - otherwise this test would be
        // flaky about which connection actually becomes White.
        white.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Alice")));
        takeOfType(whiteMessages, Protocol.TYPE_ACCOUNT_INFO);
        black.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Bob")));
        takeOfType(whiteMessages, Protocol.TYPE_COLOR_ASSIGNED);
        takeOfType(blackMessages, Protocol.TYPE_COLOR_ASSIGNED);

        third.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Carol")));
        Map<String, Object> waiting = takeOfType(thirdMessages, Protocol.TYPE_WAITING);
        assertEquals(1.0, waiting.get("queuePosition"));

        third.closeBlocking();
    }

    @Test
    public void acceptedMoveIsReflectedInBroadcastState() throws Exception {
        BlockingQueue<Map<String, Object>> whiteMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> blackMessages = new LinkedBlockingQueue<>();

        white = new RecordingClient(new URI("ws://localhost:" + port), whiteMessages);
        assertTrue(white.connectBlocking(5, TimeUnit.SECONDS));
        black = new RecordingClient(new URI("ws://localhost:" + port), blackMessages);
        assertTrue(black.connectBlocking(5, TimeUnit.SECONDS));

        // LOGIN is sent over each connection's own I/O thread with no ack
        // wait, so two sends this close together have no guaranteed arrival
        // order at the server unless we force one - waiting for white's
        // ACCOUNT_INFO (always the LOGIN handler's first response, sent
        // before any matchmaking decision) guarantees the server finished
        // processing white's LOGIN, and therefore queued white, before
        // black's LOGIN is even sent - otherwise this test would be
        // flaky about which connection actually becomes White.
        white.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Alice")));
        takeOfType(whiteMessages, Protocol.TYPE_ACCOUNT_INFO);
        black.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Bob")));
        takeOfType(whiteMessages, Protocol.TYPE_COLOR_ASSIGNED);
        takeOfType(blackMessages, Protocol.TYPE_COLOR_ASSIGNED);

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

        // LOGIN is sent over each connection's own I/O thread with no ack
        // wait, so two sends this close together have no guaranteed arrival
        // order at the server unless we force one - waiting for white's
        // ACCOUNT_INFO (always the LOGIN handler's first response, sent
        // before any matchmaking decision) guarantees the server finished
        // processing white's LOGIN, and therefore queued white, before
        // black's LOGIN is even sent - otherwise this test would be
        // flaky about which connection actually becomes White.
        white.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Alice")));
        takeOfType(whiteMessages, Protocol.TYPE_ACCOUNT_INFO);
        black.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Bob")));
        takeOfType(whiteMessages, Protocol.TYPE_COLOR_ASSIGNED);
        takeOfType(blackMessages, Protocol.TYPE_COLOR_ASSIGNED);

        // Black attempts to move a WHITE pawn - must be rejected regardless
        // of whether the move would otherwise be legal.
        black.send(Json.write(Protocol.msg(Protocol.TYPE_MOVE, "from", "d2", "to", "d4")));

        Map<String, Object> rejection = takeOfType(blackMessages, Protocol.TYPE_MOVE_REJECTED);
        assertEquals("not_your_piece", rejection.get("reason"));
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

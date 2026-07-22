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
 * Real, no-mocking end-to-end coverage for Phase 5's rooms + spectators
 * (CTD 26 spec slide 5): a real GameServer driven by real
 * org.java_websocket clients, focused specifically on the room lifecycle
 * itself (CREATE_ROOM/JOIN_ROOM/LEAVE_ROOM, the live ROOM_LIST, and
 * spectating a full room) - as opposed to GameServerIntegrationTest, which
 * covers connectivity/move-ownership once already seated in a room, and
 * GameServerDisconnectIntegrationTest, which covers the disconnect/
 * auto-resign/reconnect flow.
 *
 * The most important thing this file checks that no other test file does:
 * ROOM ISOLATION - a move made in one room must never leak into another
 * room's STATE broadcasts. That's the one behavior that would silently
 * break if Room's broadcastToRoom ever regressed back to
 * WebSocketServer.broadcast (server-wide) the way Phase 2-4's single-table
 * GameServer used it.
 */
public class RoomIntegrationTest {

    private GameServer server;
    private RecordingClient a;
    private RecordingClient b;
    private RecordingClient c;
    private int port;

    @Before
    public void startServer() throws Exception {
        port = 30000 + (int) (System.nanoTime() % 4000);
        server = new GameServer(port, RoomIntegrationTest::standardStartingBoard, new InMemoryAccountRepository());
        server.start();
        server.startGameLoop();
        Thread.sleep(300);
    }

    @After
    public void stopServer() throws Exception {
        if (a != null && a.isOpen()) a.closeBlocking();
        if (b != null && b.isOpen()) b.closeBlocking();
        if (c != null && c.isOpen()) c.closeBlocking();
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

    private RecordingClient loggedInClient(String username) throws Exception {
        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        RecordingClient client = new RecordingClient(new URI("ws://localhost:" + port), messages);
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
        client.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", username, "password", "pw")));
        takeOfType(messages, Protocol.TYPE_ACCOUNT_INFO);
        return client;
    }

    @Test
    public void creatingARoomAddsItToTheRoomListAsWaitingForOpponent() throws Exception {
        a = loggedInClient("Alice");

        a.send(Json.write(Protocol.msg(Protocol.TYPE_CREATE_ROOM, "name", "alpha")));
        takeOfType(a.messages, Protocol.TYPE_COLOR_ASSIGNED);

        Map<String, Object> roomListMsg = takeOfType(a.messages, Protocol.TYPE_ROOM_LIST);
        @SuppressWarnings("unchecked")
        List<Object> rooms = (List<Object>) roomListMsg.get("rooms");
        assertEquals(1, rooms.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> room = (Map<String, Object>) rooms.get(0);
        assertEquals("alpha", room.get("id"));
        assertEquals("WAITING_FOR_OPPONENT", room.get("status"));
        assertEquals(0.0, room.get("spectators"));
    }

    @Test
    public void secondConnectionJoiningAFullRoomBecomesASpectatorNotASeat() throws Exception {
        a = loggedInClient("Alice");
        b = loggedInClient("Bob");
        c = loggedInClient("Carol");

        a.send(Json.write(Protocol.msg(Protocol.TYPE_CREATE_ROOM, "name", "beta")));
        takeOfType(a.messages, Protocol.TYPE_COLOR_ASSIGNED);
        b.send(Json.write(Protocol.msg(Protocol.TYPE_JOIN_ROOM, "roomId", "beta")));
        takeOfType(b.messages, Protocol.TYPE_COLOR_ASSIGNED);

        // The room is now full (White + Black taken) - a third joiner must
        // become a spectator, never a seat, regardless of ordering.
        c.send(Json.write(Protocol.msg(Protocol.TYPE_JOIN_ROOM, "roomId", "beta")));
        Map<String, Object> spectating = takeOfType(c.messages, Protocol.TYPE_SPECTATING);
        assertEquals("beta", spectating.get("roomId"));

        Map<String, Object> roomListMsg = takeOfType(c.messages, Protocol.TYPE_ROOM_LIST);
        @SuppressWarnings("unchecked")
        List<Object> rooms = (List<Object>) roomListMsg.get("rooms");
        @SuppressWarnings("unchecked")
        Map<String, Object> room = (Map<String, Object>) rooms.get(0);
        assertEquals("IN_PROGRESS", room.get("status"));
        assertEquals(1.0, room.get("spectators"));
    }

    @Test
    public void aSpectatorReceivesStateUpdatesButCannotMove() throws Exception {
        a = loggedInClient("Alice");
        b = loggedInClient("Bob");
        c = loggedInClient("Carol");

        a.send(Json.write(Protocol.msg(Protocol.TYPE_CREATE_ROOM, "name", "gamma")));
        takeOfType(a.messages, Protocol.TYPE_COLOR_ASSIGNED);
        b.send(Json.write(Protocol.msg(Protocol.TYPE_JOIN_ROOM, "roomId", "gamma")));
        takeOfType(b.messages, Protocol.TYPE_COLOR_ASSIGNED);
        c.send(Json.write(Protocol.msg(Protocol.TYPE_JOIN_ROOM, "roomId", "gamma")));
        takeOfType(c.messages, Protocol.TYPE_SPECTATING);

        // The spectator should still see the board update after a real move...
        a.send(Json.write(Protocol.msg(Protocol.TYPE_MOVE, "from", "e2", "to", "e4")));
        assertTrue("the spectator should receive STATE broadcasts for the room it's watching",
                waitForPawnAt(c.messages, "WHITE", 400, 400));

        // ...but a MOVE the spectator itself sends must be rejected, since
        // it was never assigned a color in this room.
        c.send(Json.write(Protocol.msg(Protocol.TYPE_MOVE, "from", "e7", "to", "e5")));
        Map<String, Object> rejection = takeOfType(c.messages, Protocol.TYPE_MOVE_REJECTED);
        assertEquals("not_your_piece", rejection.get("reason"));
    }

    @Test
    public void movesInOneRoomNeverAppearInAnotherRoomsBroadcasts() throws Exception {
        a = loggedInClient("Alice");
        b = loggedInClient("Bob");

        RecordingClient carol = loggedInClient("Carol");
        RecordingClient dave = loggedInClient("Dave");

        a.send(Json.write(Protocol.msg(Protocol.TYPE_CREATE_ROOM, "name", "room-x")));
        takeOfType(a.messages, Protocol.TYPE_COLOR_ASSIGNED);
        b.send(Json.write(Protocol.msg(Protocol.TYPE_JOIN_ROOM, "roomId", "room-x")));
        takeOfType(b.messages, Protocol.TYPE_COLOR_ASSIGNED);

        carol.send(Json.write(Protocol.msg(Protocol.TYPE_CREATE_ROOM, "name", "room-y")));
        takeOfType(carol.messages, Protocol.TYPE_COLOR_ASSIGNED);
        dave.send(Json.write(Protocol.msg(Protocol.TYPE_JOIN_ROOM, "roomId", "room-y")));
        takeOfType(dave.messages, Protocol.TYPE_COLOR_ASSIGNED);

        // White in room-x moves e2->e4. Room-y's players must never see a
        // white pawn show up at e4 - their board only ever has their OWN
        // room's moves applied to it (both rooms start from the same
        // standard position, so a leaked broadcast would be indistinguishable
        // from a real bug here, not just a coincidence of board setup).
        a.send(Json.write(Protocol.msg(Protocol.TYPE_MOVE, "from", "e2", "to", "e4")));
        assertTrue(waitForPawnAt(a.messages, "WHITE", 400, 400));

        assertFalse("room-y must never observe room-x's move",
                waitForPawnAtWithShortTimeout(carol.messages, "WHITE", 400, 400));

        carol.closeBlocking();
        dave.closeBlocking();
    }

    @Test
    public void leavingARoomFreesTheSeatForAReconnectingOrNewPlayer() throws Exception {
        a = loggedInClient("Alice");
        b = loggedInClient("Bob");

        a.send(Json.write(Protocol.msg(Protocol.TYPE_CREATE_ROOM, "name", "delta")));
        takeOfType(a.messages, Protocol.TYPE_COLOR_ASSIGNED);
        b.send(Json.write(Protocol.msg(Protocol.TYPE_JOIN_ROOM, "roomId", "delta")));
        takeOfType(b.messages, Protocol.TYPE_COLOR_ASSIGNED);

        // White leaves explicitly (not a disconnect) - since the game is
        // still in progress, this starts the same disconnect-grace flow a
        // dropped socket would (see Room.removeConnection's class doc).
        a.send(Json.write(Protocol.msg(Protocol.TYPE_LEAVE_ROOM)));
        Map<String, Object> notice = takeOfType(b.messages, Protocol.TYPE_OPPONENT_DISCONNECTED);
        assertEquals("WHITE", notice.get("color"));
    }

    @SuppressWarnings("unchecked")
    private boolean waitForPawnAt(BlockingQueue<Map<String, Object>> queue, String color, double expectedX, double expectedY) throws InterruptedException {
        return waitForPawnAt(queue, color, expectedX, expectedY, 5000);
    }

    private boolean waitForPawnAtWithShortTimeout(BlockingQueue<Map<String, Object>> queue, String color, double expectedX, double expectedY) throws InterruptedException {
        return waitForPawnAt(queue, color, expectedX, expectedY, 1500);
    }

    @SuppressWarnings("unchecked")
    private boolean waitForPawnAt(BlockingQueue<Map<String, Object>> queue, String color, double expectedX, double expectedY, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
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
        final BlockingQueue<Map<String, Object>> messages;

        RecordingClient(URI uri, BlockingQueue<Map<String, Object>> messages) {
            super(uri);
            this.messages = messages;
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
        }

        @Override
        public void onMessage(String message) {
            messages.add(Json.parseObject(message));
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
        }

        @Override
        public void onError(Exception ex) {
        }
    }
}

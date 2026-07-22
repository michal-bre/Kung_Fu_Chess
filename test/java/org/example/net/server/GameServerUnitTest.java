package org.example.net.server;

import org.example.account.AccountRepository;
import org.example.account.InMemoryAccountRepository;
import org.example.adapters.BoardParser;
import org.example.model.Board;
import org.example.net.log.GameLogger;
import org.example.net.protocol.Json;
import org.example.net.protocol.Protocol;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Direct, no-socket unit coverage of GameServer's message routing (LOGIN,
 * CREATE_ROOM, JOIN_ROOM, LEAVE_ROOM, and the various guard clauses around
 * them) - the "matchmaking queue" half of Phase 6's integration pass, in the
 * post-Phase-5 sense: Phase 4's FIFO waiting queue no longer exists, so what
 * "matchmaking" means now is this exact routing - which connection ends up
 * seated, spectating, or rejected by which room-related message.
 *
 * GameServer extends WebSocketServer, but its constructor never binds a real
 * socket (WebSocketServer's constructor just records the InetSocketAddress;
 * actual binding happens in start(), which none of these tests call), and
 * every method under test here (onOpen/onMessage/onClose) only ever touches
 * the WebSocket it's handed - never anything socket-specific - so a
 * {@link FakeConnection} plus direct calls exercise the exact same
 * production code GameServerIntegrationTest/RoomIntegrationTest exercise
 * over a real port, just without paying for a real TCP handshake.
 *
 * One gap this approach knowingly accepts: broadcastRoomList() goes through
 * WebSocketServer's own broadcast(), which iterates the server's internal
 * "real accepted connections" registry - a registry these tests never
 * populate, since these connections were never actually accepted over a
 * socket. That means ROOM_LIST broadcasts silently reach nobody in these
 * tests. RoomIntegrationTest's creatingARoomAddsItToTheRoomListAsWaitingForOpponent
 * already covers that broadcast for real; every other message here (sent
 * directly to a specific connection via conn.send(...), never via
 * broadcast()) is unaffected and asserted on normally.
 */
public class GameServerUnitTest {

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

    private GameServer newServer() {
        return newServer(30_000);
    }

    private GameServer newServer(long resignGraceMillis) {
        return new GameServer(0, GameServerUnitTest::standardStartingBoard, new InMemoryAccountRepository(),
                resignGraceMillis, GameLogger.create("gameserver-unit-test", "test"));
    }

    private GameServer newServer(AccountRepository accountRepository) {
        return new GameServer(0, GameServerUnitTest::standardStartingBoard, accountRepository,
                30_000, GameLogger.create("gameserver-unit-test", "test"));
    }

    /** For the "Play" quick-match timeout test - a real search timeout well under 30s so the test doesn't have to wait around, the same trick newServer(resignGraceMillis) already uses for the disconnect grace period. */
    private GameServer newServerWithShortMatchTimeout(long matchSearchTimeoutMillis) {
        return new GameServer(0, GameServerUnitTest::standardStartingBoard, new InMemoryAccountRepository(),
                30_000, matchSearchTimeoutMillis, GameLogger.create("gameserver-unit-test", "test"));
    }

    private static void send(GameServer server, FakeConnection conn, String type, Object... keyValuePairs) {
        server.onMessage(conn, Protocol.write(type, keyValuePairs));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lastMessageOfType(FakeConnection conn, String type) {
        for (int i = conn.sentMessages.size() - 1; i >= 0; i--) {
            Map<String, Object> parsed = Json.parseObject(conn.sentMessages.get(i));
            if (type.equals(parsed.get("type"))) return parsed;
        }
        throw new AssertionError("connection never received a message of type " + type
                + " - actually received: " + conn.sentMessages);
    }

    private static FakeConnection connectAndLogin(GameServer server, String username) {
        FakeConnection conn = new FakeConnection();
        server.onOpen(conn, null);
        send(server, conn, Protocol.TYPE_LOGIN, "username", username, "password", "pw-" + username);
        return conn;
    }

    @Test
    public void openingAConnectionSendsTheCurrentRoomList() {
        GameServer server = newServer();
        FakeConnection conn = new FakeConnection();

        server.onOpen(conn, null);

        Map<String, Object> roomList = lastMessageOfType(conn, Protocol.TYPE_ROOM_LIST);
        assertEquals(Arrays.asList(), roomList.get("rooms"));
    }

    @Test
    public void loginRespondsWithAccountInfoAndDoesNotAutoSeatAnyone() {
        GameServer server = newServer();
        FakeConnection conn = connectAndLogin(server, "Alice");

        Map<String, Object> accountInfo = lastMessageOfType(conn, Protocol.TYPE_ACCOUNT_INFO);
        assertEquals("Alice", accountInfo.get("username"));

        // Since LOGIN alone must not seat anyone (Phase 5 replaced Phase 4's
        // auto-queue), a MOVE right after login should be rejected as "not
        // currently in a room", not routed to any table.
        send(server, conn, Protocol.TYPE_MOVE, "from", "e2", "to", "e4");
        assertEquals("not currently in a room", lastMessageOfType(conn, Protocol.TYPE_ERROR).get("message"));
    }

    @Test
    public void creatingOrJoiningARoomWithoutLoggingInFirstIsRejected() {
        GameServer server = newServer();
        FakeConnection conn = new FakeConnection();
        server.onOpen(conn, null);

        send(server, conn, Protocol.TYPE_CREATE_ROOM, "name", "r1");
        assertEquals("log in before creating a room", lastMessageOfType(conn, Protocol.TYPE_ERROR).get("message"));

        send(server, conn, Protocol.TYPE_JOIN_ROOM, "roomId", "r1");
        assertEquals("log in before joining a room", lastMessageOfType(conn, Protocol.TYPE_ERROR).get("message"));
    }

    @Test
    public void creatingARoomWithNoNameAutoGeneratesOne() {
        GameServer server = newServer();
        FakeConnection conn = connectAndLogin(server, "Alice");

        send(server, conn, Protocol.TYPE_CREATE_ROOM);

        String roomId = (String) lastMessageOfType(conn, Protocol.TYPE_COLOR_ASSIGNED).get("roomId");
        assertTrue("an auto-generated room name should start with 'Room-', was: " + roomId,
                roomId.startsWith("Room-"));
    }

    @Test
    public void creatingARoomWithANameAlreadyInUseIsRejected() {
        GameServer server = newServer();
        FakeConnection alice = connectAndLogin(server, "Alice");
        FakeConnection bob = connectAndLogin(server, "Bob");
        send(server, alice, Protocol.TYPE_CREATE_ROOM, "name", "dup-room");

        send(server, bob, Protocol.TYPE_CREATE_ROOM, "name", "dup-room");

        assertEquals("a room named 'dup-room' already exists", lastMessageOfType(bob, Protocol.TYPE_ERROR).get("message"));
    }

    @Test
    public void creatingOrJoiningARoomWhileAlreadyInOneIsRejected() {
        GameServer server = newServer();
        FakeConnection alice = connectAndLogin(server, "Alice");
        send(server, alice, Protocol.TYPE_CREATE_ROOM, "name", "r1");

        send(server, alice, Protocol.TYPE_CREATE_ROOM, "name", "r2");
        assertEquals("already in a room - leave it first", lastMessageOfType(alice, Protocol.TYPE_ERROR).get("message"));

        send(server, alice, Protocol.TYPE_JOIN_ROOM, "roomId", "r2");
        assertEquals("already in a room - leave it first", lastMessageOfType(alice, Protocol.TYPE_ERROR).get("message"));
    }

    @Test
    public void joiningANonexistentRoomIsRejected() {
        GameServer server = newServer();
        FakeConnection alice = connectAndLogin(server, "Alice");

        send(server, alice, Protocol.TYPE_JOIN_ROOM, "roomId", "nope");

        assertEquals("no such room: nope", lastMessageOfType(alice, Protocol.TYPE_ERROR).get("message"));
    }

    @Test
    public void secondJoinerIsSeatedBlackThirdJoinerBecomesASpectator() {
        GameServer server = newServer();
        FakeConnection alice = connectAndLogin(server, "Alice");
        FakeConnection bob = connectAndLogin(server, "Bob");
        FakeConnection carol = connectAndLogin(server, "Carol");
        send(server, alice, Protocol.TYPE_CREATE_ROOM, "name", "r1");
        assertEquals("WHITE", lastMessageOfType(alice, Protocol.TYPE_COLOR_ASSIGNED).get("color"));

        send(server, bob, Protocol.TYPE_JOIN_ROOM, "roomId", "r1");
        assertEquals("BLACK", lastMessageOfType(bob, Protocol.TYPE_COLOR_ASSIGNED).get("color"));

        send(server, carol, Protocol.TYPE_JOIN_ROOM, "roomId", "r1");
        assertEquals("r1", lastMessageOfType(carol, Protocol.TYPE_SPECTATING).get("roomId"));
    }

    /**
     * LEAVE_ROOM and a real disconnect go through the exact same
     * Room.removeConnection path (see Room's class doc: "leaving and
     * disconnecting are treated the same way on purpose"). So leaving a
     * room mid-game does NOT immediately free the seat for a stranger - it
     * starts the same disconnect grace period a dropped connection would,
     * meaning a third joiner becomes a spectator, and only Bob himself
     * (matching username) can reclaim the seat via JOIN_ROOM before the
     * grace period elapses.
     */
    @Test
    public void leavingARoomMidGameStartsAGracePeriodRatherThanImmediatelyFreeingTheSeat() {
        GameServer server = newServer();
        FakeConnection alice = connectAndLogin(server, "Alice");
        FakeConnection bob = connectAndLogin(server, "Bob");
        FakeConnection carol = connectAndLogin(server, "Carol");
        send(server, alice, Protocol.TYPE_CREATE_ROOM, "name", "r1");
        send(server, bob, Protocol.TYPE_JOIN_ROOM, "roomId", "r1");

        send(server, bob, Protocol.TYPE_LEAVE_ROOM);
        assertEquals("BLACK", lastMessageOfType(alice, Protocol.TYPE_OPPONENT_DISCONNECTED).get("color"));

        send(server, carol, Protocol.TYPE_JOIN_ROOM, "roomId", "r1");
        assertEquals("a stranger joining during Bob's grace period should become a spectator, not take his seat",
                "r1", lastMessageOfType(carol, Protocol.TYPE_SPECTATING).get("roomId"));

        FakeConnection bobRejoining = connectAndLogin(server, "Bob");
        send(server, bobRejoining, Protocol.TYPE_JOIN_ROOM, "roomId", "r1");
        assertEquals("Bob himself should still be able to reclaim his own seat within the grace period",
                "BLACK", lastMessageOfType(bobRejoining, Protocol.TYPE_COLOR_ASSIGNED).get("color"));
    }

    @Test
    public void leavingARoomYouAreNotInIsRejected() {
        GameServer server = newServer();
        FakeConnection alice = connectAndLogin(server, "Alice");

        send(server, alice, Protocol.TYPE_LEAVE_ROOM);

        assertEquals("not currently in a room", lastMessageOfType(alice, Protocol.TYPE_ERROR).get("message"));
    }

    @Test
    public void jumpingBeforeJoiningARoomIsRejected() {
        GameServer server = newServer();
        FakeConnection alice = connectAndLogin(server, "Alice");

        send(server, alice, Protocol.TYPE_JUMP, "square", "e2");

        assertEquals("not currently in a room", lastMessageOfType(alice, Protocol.TYPE_ERROR).get("message"));
    }

    @Test
    public void malformedJsonProducesAnError() {
        GameServer server = newServer();
        FakeConnection conn = new FakeConnection();
        server.onOpen(conn, null);

        server.onMessage(conn, "{not valid json");

        assertEquals("malformed JSON", lastMessageOfType(conn, Protocol.TYPE_ERROR).get("message"));
    }

    @Test
    public void aMessageWithNoTypeFieldProducesAnError() {
        GameServer server = newServer();
        FakeConnection conn = new FakeConnection();
        server.onOpen(conn, null);

        server.onMessage(conn, "{\"username\":\"Alice\"}");

        assertEquals("missing type", lastMessageOfType(conn, Protocol.TYPE_ERROR).get("message"));
    }

    @Test
    public void anUnknownMessageTypeProducesAnError() {
        GameServer server = newServer();
        FakeConnection conn = new FakeConnection();
        server.onOpen(conn, null);

        send(server, conn, "NOT_A_REAL_TYPE");

        String message = (String) lastMessageOfType(conn, Protocol.TYPE_ERROR).get("message");
        assertTrue(message.startsWith("unknown type"));
    }

    /**
     * Regression test for the Phase 6 bug fix: onClose used to clear
     * usernameByConnection BEFORE handing the disconnect to the connection's
     * Room, so Room.beginDisconnectGracePeriod read a null username and both
     * the OPPONENT_DISCONNECTED notice and the reconnect match (which
     * compares usernames) broke. This drives the exact same onClose ->
     * removeConnection -> beginDisconnectGracePeriod path GameServer uses in
     * production, without a live socket or the real 30s grace timer.
     */
    @Test
    public void disconnectingMidGameReportsTheRealUsernameNotNull() {
        GameServer server = newServer();
        FakeConnection alice = connectAndLogin(server, "Alice");
        FakeConnection bob = connectAndLogin(server, "Bob");
        send(server, alice, Protocol.TYPE_CREATE_ROOM, "name", "r1");
        send(server, bob, Protocol.TYPE_JOIN_ROOM, "roomId", "r1");

        alice.setOpen(false);
        server.onClose(alice, 1006, "connection lost", true);

        Map<String, Object> notice = lastMessageOfType(bob, Protocol.TYPE_OPPONENT_DISCONNECTED);
        assertEquals("Alice", notice.get("username"));
    }

    /** Same regression, followed through to a successful reconnect via JOIN_ROOM. */
    @Test
    public void reconnectingAfterADisconnectViaJoinRoomResumesTheSameSeat() {
        GameServer server = newServer();
        FakeConnection alice = connectAndLogin(server, "Alice");
        FakeConnection bob = connectAndLogin(server, "Bob");
        send(server, alice, Protocol.TYPE_CREATE_ROOM, "name", "r1");
        send(server, bob, Protocol.TYPE_JOIN_ROOM, "roomId", "r1");

        alice.setOpen(false);
        server.onClose(alice, 1006, "connection lost", true);

        FakeConnection aliceReconnected = connectAndLogin(server, "Alice");
        send(server, aliceReconnected, Protocol.TYPE_JOIN_ROOM, "roomId", "r1");

        assertEquals("WHITE", lastMessageOfType(aliceReconnected, Protocol.TYPE_COLOR_ASSIGNED).get("color"));
        assertEquals("Alice", lastMessageOfType(bob, Protocol.TYPE_OPPONENT_RECONNECTED).get("username"));
    }

    // --- LOGIN password checks (Phase 7, CTD 26 spec slide 5) ---

    @Test
    public void loginWithNoPasswordFieldIsRejected() {
        GameServer server = newServer();
        FakeConnection conn = new FakeConnection();
        server.onOpen(conn, null);

        send(server, conn, Protocol.TYPE_LOGIN, "username", "Alice");

        assertEquals("password required", lastMessageOfType(conn, Protocol.TYPE_ERROR).get("message"));
    }

    @Test
    public void secondLoginWithAWrongPasswordIsRejectedAndDoesNotAuthenticate() {
        AccountRepository sharedRepo = new InMemoryAccountRepository();
        GameServer server = newServer(sharedRepo);
        FakeConnection first = new FakeConnection();
        server.onOpen(first, null);
        send(server, first, Protocol.TYPE_LOGIN, "username", "Alice", "password", "correct-horse");
        assertNotNull(lastMessageOfType(first, Protocol.TYPE_ACCOUNT_INFO));

        FakeConnection second = new FakeConnection();
        server.onOpen(second, null);
        send(server, second, Protocol.TYPE_LOGIN, "username", "Alice", "password", "wrong-password");

        assertEquals("invalid username or password", lastMessageOfType(second, Protocol.TYPE_ERROR).get("message"));
        // Not authenticated - a room action must still be rejected as "log in first", not routed anywhere.
        send(server, second, Protocol.TYPE_CREATE_ROOM, "name", "should-not-work");
        assertEquals("log in before creating a room", lastMessageOfType(second, Protocol.TYPE_ERROR).get("message"));
    }

    @Test
    public void secondLoginWithTheMatchingPasswordSucceeds() {
        AccountRepository sharedRepo = new InMemoryAccountRepository();
        GameServer server = newServer(sharedRepo);
        FakeConnection first = new FakeConnection();
        server.onOpen(first, null);
        send(server, first, Protocol.TYPE_LOGIN, "username", "Alice", "password", "correct-horse");

        FakeConnection second = new FakeConnection();
        server.onOpen(second, null);
        send(server, second, Protocol.TYPE_LOGIN, "username", "Alice", "password", "correct-horse");

        assertNotNull(lastMessageOfType(second, Protocol.TYPE_ACCOUNT_INFO));
    }

    // --- "Play" quick match (Phase 7, CTD 26 spec slide 6: ELO +-100, 1 min timeout) ---

    @Test
    public void findMatchBeforeLoggingInIsRejected() {
        GameServer server = newServer();
        FakeConnection conn = new FakeConnection();
        server.onOpen(conn, null);

        send(server, conn, Protocol.TYPE_FIND_MATCH);

        assertEquals("log in before playing", lastMessageOfType(conn, Protocol.TYPE_ERROR).get("message"));
    }

    @Test
    public void firstSearcherIsToldItIsWaiting() {
        GameServer server = newServer();
        FakeConnection alice = connectAndLogin(server, "Alice");

        send(server, alice, Protocol.TYPE_FIND_MATCH);

        assertNotNull(lastMessageOfType(alice, Protocol.TYPE_WAITING));
    }

    @Test
    public void twoDefaultEloPlayersAreMatchedTogetherWithTheWaitingOneAsWhite() {
        GameServer server = newServer();
        FakeConnection alice = connectAndLogin(server, "Alice");
        FakeConnection bob = connectAndLogin(server, "Bob");

        send(server, alice, Protocol.TYPE_FIND_MATCH); // both start at the same default ELO (1200) - always in range
        send(server, bob, Protocol.TYPE_FIND_MATCH);

        assertEquals("WHITE", lastMessageOfType(alice, Protocol.TYPE_COLOR_ASSIGNED).get("color"));
        assertEquals("BLACK", lastMessageOfType(bob, Protocol.TYPE_COLOR_ASSIGNED).get("color"));
        // Both landed in the same auto-named room.
        assertEquals(lastMessageOfType(alice, Protocol.TYPE_COLOR_ASSIGNED).get("roomId"),
                lastMessageOfType(bob, Protocol.TYPE_COLOR_ASSIGNED).get("roomId"));
    }

    @Test
    public void playersOutsideTheEloRangeAreNotMatchedToEachOther() {
        AccountRepository sharedRepo = new InMemoryAccountRepository();
        sharedRepo.recordGameResult("Alice", 1800, "Placeholder", 1200); // Alice far above default ELO
        GameServer server = newServer(sharedRepo);
        FakeConnection alice = connectAndLogin(server, "Alice");
        FakeConnection bob = connectAndLogin(server, "Bob"); // default 1200 - 600 points away

        send(server, alice, Protocol.TYPE_FIND_MATCH);
        send(server, bob, Protocol.TYPE_FIND_MATCH);

        // Neither should have been seated - both are still just waiting.
        assertNotNull(lastMessageOfType(alice, Protocol.TYPE_WAITING));
        assertNotNull(lastMessageOfType(bob, Protocol.TYPE_WAITING));
    }

    @Test
    public void cancellingASearchMeansItIsNoLongerMatchable() throws InterruptedException {
        GameServer server = newServer();
        FakeConnection alice = connectAndLogin(server, "Alice");
        send(server, alice, Protocol.TYPE_FIND_MATCH);

        send(server, alice, Protocol.TYPE_CANCEL_MATCH);

        FakeConnection bob = connectAndLogin(server, "Bob");
        send(server, bob, Protocol.TYPE_FIND_MATCH);
        // If Alice's cancelled search were still active, Bob would have been
        // matched with her (COLOR_ASSIGNED) instead of left waiting.
        assertNotNull(lastMessageOfType(bob, Protocol.TYPE_WAITING));
    }

    @Test
    public void disconnectingWhileSearchingRemovesTheWaitingEntry() {
        GameServer server = newServer();
        FakeConnection alice = connectAndLogin(server, "Alice");
        send(server, alice, Protocol.TYPE_FIND_MATCH);

        server.onClose(alice, 1006, "connection lost", true);

        FakeConnection bob = connectAndLogin(server, "Bob");
        send(server, bob, Protocol.TYPE_FIND_MATCH);
        assertNotNull("Alice's search must have been cleaned up on disconnect, or Bob would have matched her instead",
                lastMessageOfType(bob, Protocol.TYPE_WAITING));
    }

    @Test
    public void searchTimesOutAndSendsMatchNotFound() throws InterruptedException {
        GameServer server = newServerWithShortMatchTimeout(50);
        FakeConnection alice = connectAndLogin(server, "Alice");

        send(server, alice, Protocol.TYPE_FIND_MATCH);

        Map<String, Object> notFound = pollForMessageOfType(alice, Protocol.TYPE_MATCH_NOT_FOUND, 3000);
        assertNotNull(notFound.get("message"));
    }

    private static Map<String, Object> pollForMessageOfType(FakeConnection conn, String type, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (String raw : conn.sentMessages) {
                Map<String, Object> parsed = Json.parseObject(raw);
                if (type.equals(parsed.get("type"))) return parsed;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("timed out waiting for a message of type " + type
                + " - actually received: " + conn.sentMessages);
    }
}

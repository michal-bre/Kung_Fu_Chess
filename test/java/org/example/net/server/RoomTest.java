package org.example.net.server;

import org.example.account.Account;
import org.example.account.AccountRepository;
import org.example.account.InMemoryAccountRepository;
import org.example.adapters.BoardParser;
import org.example.model.Board;
import org.example.net.log.GameLogger;
import org.example.net.protocol.Json;
import org.example.net.protocol.Protocol;
import org.java_websocket.WebSocket;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/**
 * Direct, no-socket unit coverage of Room's lifecycle (seating, spectating,
 * disconnect/auto-resign, reconnection) - the "room lifecycle" half of Phase
 * 6's integration pass. Room is package-private and takes a plain
 * {@code WebSocket} for every connection it deals with, and {@code WebSocket}
 * is an interface in the Java-WebSocket library - so {@link FakeConnection}
 * can stand in for a real socket entirely, and every test here runs as a
 * plain in-process unit test with no server, no port, and (outside the two
 * disconnect-grace tests, which need a real timer to fire) no waiting.
 *
 * RoomIntegrationTest (in org.example.net) already proves this same behavior
 * end to end over real WebSocket connections and real room-isolation - this
 * file exists to cover the same decisions faster and more granularly, and to
 * make failures easier to localize to Room itself rather than to the
 * client/server wire plumbing around it.
 */
public class RoomTest {

    private AccountRepository accountRepository;
    private ScheduledExecutorService disconnectExecutor;
    private GameLogger logger;
    private Map<WebSocket, String> usernameByConnection;
    private Map<WebSocket, Account> accountByConnection;

    @Before
    public void setUp() {
        accountRepository = new InMemoryAccountRepository();
        disconnectExecutor = Executors.newSingleThreadScheduledExecutor();
        logger = GameLogger.create("room-test", "room-test");
        usernameByConnection = new ConcurrentHashMap<>();
        accountByConnection = new ConcurrentHashMap<>();
    }

    private static Supplier<Board> standardBoardFactory() {
        return () -> {
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
        };
    }

    private Room newRoom(String id, long resignGraceMillis) {
        return newRoom(id, resignGraceMillis, standardBoardFactory());
    }

    private Room newRoom(String id, long resignGraceMillis, Supplier<Board> boardFactory) {
        return new Room(id, boardFactory, accountRepository, resignGraceMillis,
                disconnectExecutor, logger, usernameByConnection, accountByConnection);
    }

    private FakeConnection login(String username) {
        FakeConnection conn = new FakeConnection();
        usernameByConnection.put(conn, username);
        return conn;
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lastRoster(FakeConnection conn) {
        Object players = lastMessageOfType(conn, Protocol.TYPE_ROOM_ROSTER).get("players");
        List<Map<String, Object>> roster = new java.util.ArrayList<>();
        for (Object item : (List<Object>) players) {
            roster.add((Map<String, Object>) item);
        }
        return roster;
    }

    private static Map<String, Object> rosterEntryFor(List<Map<String, Object>> roster, String username) {
        for (Map<String, Object> entry : roster) {
            if (username.equals(entry.get("username"))) return entry;
        }
        throw new AssertionError(username + " not found in roster: " + roster);
    }

    @Test
    public void firstConnectionToBeSeatedIsWhiteSecondIsBlack() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");

        room.assignSeat(alice);
        assertEquals("WHITE", lastMessageOfType(alice, Protocol.TYPE_COLOR_ASSIGNED).get("color"));
        assertTrue("one seat should still be open", room.hasOpenSeat());

        room.assignSeat(bob);
        assertEquals("BLACK", lastMessageOfType(bob, Protocol.TYPE_COLOR_ASSIGNED).get("color"));
        assertFalse("both seats should now be taken", room.hasOpenSeat());
    }

    @Test
    public void assigningASeatAlsoSendsAnImmediateStateSnapshot() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");

        room.assignSeat(alice);

        // COLOR_ASSIGNED, then an immediate STATE, then the roster broadcast
        // (see assigningASeatBroadcastsTheRosterToEveryoneInTheRoom for that
        // one's own content) - order matters here, not just membership.
        assertEquals(3, alice.sentMessages.size());
        assertEquals(Protocol.TYPE_COLOR_ASSIGNED, Json.parseObject(alice.sentMessages.get(0)).get("type"));
        assertEquals(Protocol.TYPE_STATE, Json.parseObject(alice.sentMessages.get(1)).get("type"));
        assertEquals(Protocol.TYPE_ROOM_ROSTER, Json.parseObject(alice.sentMessages.get(2)).get("type"));
    }

    @Test
    public void aThirdConnectionToAFullRoomBecomesASpectatorNotASeat() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        FakeConnection carol = login("Carol");

        room.assignSeat(alice);
        room.assignSeat(bob);
        room.addSpectator(carol);

        assertFalse(room.hasOpenSeat());
        assertEquals(1, room.getSpectatorCount());
        Map<String, Object> spectating = lastMessageOfType(carol, Protocol.TYPE_SPECTATING);
        assertEquals("room-1", spectating.get("roomId"));
    }

    // --- Roster ("everyone who joins the room gets a list of what they are") ---

    @Test
    public void assigningASeatBroadcastsTheRosterToEveryoneInTheRoom() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");

        room.assignSeat(alice);
        List<Map<String, Object>> aliceOnlyRoster = lastRoster(alice);
        assertEquals(1, aliceOnlyRoster.size());
        assertEquals("WHITE", rosterEntryFor(aliceOnlyRoster, "Alice").get("role"));

        room.assignSeat(bob);
        // Both Alice and Bob must see the UPDATED roster (both seats), not
        // just whoever most recently joined.
        List<Map<String, Object>> aliceFullRoster = lastRoster(alice);
        List<Map<String, Object>> bobFullRoster = lastRoster(bob);
        assertEquals(2, aliceFullRoster.size());
        assertEquals(2, bobFullRoster.size());
        assertEquals("WHITE", rosterEntryFor(bobFullRoster, "Alice").get("role"));
        assertEquals("BLACK", rosterEntryFor(bobFullRoster, "Bob").get("role"));
    }

    @Test
    public void aSpectatorAppearsInTheRosterWithARoleOfSpectator() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        FakeConnection carol = login("Carol");
        room.assignSeat(alice);
        room.assignSeat(bob);

        room.addSpectator(carol);

        List<Map<String, Object>> roster = lastRoster(carol);
        assertEquals(3, roster.size());
        assertEquals("SPECTATOR", rosterEntryFor(roster, "Carol").get("role"));
        // Alice and Bob must also see Carol added to the roster.
        assertEquals("SPECTATOR", rosterEntryFor(lastRoster(alice), "Carol").get("role"));
    }

    @Test
    public void aDisconnectedSeatStaysInTheRosterFlaggedAsDisconnected() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        room.assignSeat(alice);
        room.assignSeat(bob);

        alice.setOpen(false);
        room.removeConnection(alice);

        List<Map<String, Object>> roster = lastRoster(bob);
        assertEquals("Alice's seat must still appear in the roster during her grace period", 2, roster.size());
        Map<String, Object> aliceEntry = rosterEntryFor(roster, "Alice");
        assertEquals("WHITE", aliceEntry.get("role"));
        assertEquals(true, aliceEntry.get("disconnected"));
        assertEquals(false, rosterEntryFor(roster, "Bob").get("disconnected"));
    }

    @Test
    public void reconnectingClearsTheDisconnectedFlagInTheRoster() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        room.assignSeat(alice);
        room.assignSeat(bob);
        alice.setOpen(false);
        room.removeConnection(alice);

        FakeConnection aliceReconnected = login("Alice");
        room.tryResumeDisconnectedSeat(aliceReconnected, "Alice");

        List<Map<String, Object>> roster = lastRoster(bob);
        assertEquals(false, rosterEntryFor(roster, "Alice").get("disconnected"));
    }

    @Test
    public void aSpectatorLeavingIsRemovedFromTheRoster() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        FakeConnection carol = login("Carol");
        room.assignSeat(alice);
        room.assignSeat(bob);
        room.addSpectator(carol);

        room.removeConnection(carol);

        List<Map<String, Object>> roster = lastRoster(alice);
        assertEquals(2, roster.size());
        for (Map<String, Object> entry : roster) {
            assertNotEquals("Carol", entry.get("username"));
        }
    }

    @Test
    public void spectatorMovingAPieceIsRejectedAsNotYourPiece() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        FakeConnection carol = login("Carol");
        room.assignSeat(alice);
        room.assignSeat(bob);
        room.addSpectator(carol);

        room.handleMove(carol, Protocol.msg(Protocol.TYPE_MOVE, "from", "e2", "to", "e4"));

        Map<String, Object> rejection = lastMessageOfType(carol, Protocol.TYPE_MOVE_REJECTED);
        assertEquals("not_your_piece", rejection.get("reason"));
    }

    @Test
    public void blackMovingAWhitePieceIsRejectedAsNotYourPiece() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        room.assignSeat(alice);
        room.assignSeat(bob);

        room.handleMove(bob, Protocol.msg(Protocol.TYPE_MOVE, "from", "e2", "to", "e4"));

        Map<String, Object> rejection = lastMessageOfType(bob, Protocol.TYPE_MOVE_REJECTED);
        assertEquals("not_your_piece", rejection.get("reason"));
    }

    @Test
    public void whiteMovingItsOwnPawnIsAccepted() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        room.assignSeat(alice);
        room.assignSeat(bob);
        int messagesBeforeMove = alice.sentMessages.size();

        room.handleMove(alice, Protocol.msg(Protocol.TYPE_MOVE, "from", "e2", "to", "e4"));

        // An accepted move sends no rejection back - the move's effect shows
        // up in the next STATE broadcast (room.tick), not a direct reply.
        for (int i = messagesBeforeMove; i < alice.sentMessages.size(); i++) {
            assertNotEquals(Protocol.TYPE_MOVE_REJECTED, Json.parseObject(alice.sentMessages.get(i)).get("type"));
        }
    }

    /**
     * Regression test: Room.handleMove used to call gameEngine.requestMove
     * directly with no equivalent of InteractionHandler's move-history
     * bookkeeping, so an accepted networked move never published a
     * MoveLoggedEvent and MOVE_LOG never reached any client - the board
     * still updated fine via STATE, so nothing caught this until a real
     * player noticed an empty move-log table in two live GUI windows. Both
     * seats (mover and opponent) and any spectators are expected to receive
     * it, same as every other room broadcast.
     */
    @Test
    public void anAcceptedMovePublishesAMoveLogBroadcastToEveryoneInTheRoom() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        room.assignSeat(alice);
        room.assignSeat(bob);

        room.handleMove(alice, Protocol.msg(Protocol.TYPE_MOVE, "from", "e2", "to", "e4"));

        Map<String, Object> aliceLog = lastMessageOfType(alice, Protocol.TYPE_MOVE_LOG);
        assertEquals("WHITE", aliceLog.get("color"));
        assertEquals("e4", aliceLog.get("notation"));
        assertEquals(false, aliceLog.get("capture"));
        assertNotNull("Bob (the opponent) must see the move log too, not just the mover",
                lastMessageOfTypeOrNull(bob, Protocol.TYPE_MOVE_LOG));
    }

    @Test
    public void anAcceptedCaptureIsLoggedWithAnXInItsNotation() {
        // Same minimal two-piece layout GameServerAccountIntegrationTest
        // uses for its king-capture test: a white rook two squares from a
        // black king, so a single MOVE is itself a capture with no setup
        // moves needed first.
        Supplier<Board> minimalBoard = () -> {
            List<String> position = Arrays.asList(
                    "wR . bK . . . . .",
                    ". . . . . . . .",
                    ". . . . . . . .",
                    ". . . . . . . .",
                    ". . . . . . . .",
                    ". . . . . . . .",
                    ". . . . . . . .",
                    ". . . . . . . ."
            );
            return BoardParser.parse(position);
        };
        Room room = newRoom("room-1", 30_000, minimalBoard);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        room.assignSeat(alice);
        room.assignSeat(bob);

        room.handleMove(alice, Protocol.msg(Protocol.TYPE_MOVE, "from", "a8", "to", "c8"));

        Map<String, Object> aliceLog = lastMessageOfType(alice, Protocol.TYPE_MOVE_LOG);
        assertEquals(true, aliceLog.get("capture"));
        assertEquals("Rxc8", aliceLog.get("notation"));
    }

    private static Map<String, Object> lastMessageOfTypeOrNull(FakeConnection conn, String type) {
        for (int i = conn.sentMessages.size() - 1; i >= 0; i--) {
            Map<String, Object> parsed = Json.parseObject(conn.sentMessages.get(i));
            if (type.equals(parsed.get("type"))) return parsed;
        }
        return null;
    }

    @Test
    public void removingASpectatorJustDropsThemWithNoDisconnectNotice() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        FakeConnection carol = login("Carol");
        room.assignSeat(alice);
        room.assignSeat(bob);
        room.addSpectator(carol);

        room.removeConnection(carol);

        assertEquals(0, room.getSpectatorCount());
        for (String raw : alice.sentMessages) {
            assertNotEquals(Protocol.TYPE_OPPONENT_DISCONNECTED, Json.parseObject(raw).get("type"));
        }
    }

    @Test
    public void removingASeatedPlayerMidGameNotifiesTheOtherSeatWithAGraceCountdown() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        room.assignSeat(alice);
        room.assignSeat(bob);

        alice.setOpen(false); // simulate the real onClose flow: the socket is already gone
        room.removeConnection(alice);

        Map<String, Object> notice = lastMessageOfType(bob, Protocol.TYPE_OPPONENT_DISCONNECTED);
        assertEquals("WHITE", notice.get("color"));
        assertEquals("Alice", notice.get("username"));
        assertEquals(30.0, notice.get("graceSeconds"));
    }

    @Test
    public void reconnectingWithTheSameUsernameCancelsTheGraceAndResumesTheSameSeat() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        room.assignSeat(alice);
        room.assignSeat(bob);
        alice.setOpen(false);
        room.removeConnection(alice);

        FakeConnection aliceReconnected = login("Alice");
        boolean resumed = room.tryResumeDisconnectedSeat(aliceReconnected, "Alice");

        assertTrue(resumed);
        assertEquals("WHITE", lastMessageOfType(aliceReconnected, Protocol.TYPE_COLOR_ASSIGNED).get("color"));
        Map<String, Object> reconnected = lastMessageOfType(bob, Protocol.TYPE_OPPONENT_RECONNECTED);
        assertEquals("Alice", reconnected.get("username"));
        assertFalse("the seat should be occupied again, not open", room.hasOpenSeat());
    }

    @Test
    public void reconnectAttemptWithADifferentUsernameDoesNotResumeTheSeat() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        room.assignSeat(alice);
        room.assignSeat(bob);
        alice.setOpen(false);
        room.removeConnection(alice);

        FakeConnection mallory = login("Mallory");
        boolean resumed = room.tryResumeDisconnectedSeat(mallory, "Mallory");

        assertFalse(resumed);
        assertTrue(mallory.sentMessages.isEmpty());
    }

    @Test
    public void forcedResignationAfterGraceExpiresEndsTheGameForTheOtherColorAndUpdatesElo() throws Exception {
        // A short grace period (well under the 30s default) so this test
        // doesn't have to wait around - forceResign runs on the same real
        // ScheduledExecutorService production code uses, just with a much
        // shorter delay.
        Room room = newRoom("room-1", 50);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        accountByConnection.put(alice, new Account("Alice", 1200, 0, 0, 0));
        accountByConnection.put(bob, new Account("Bob", 1200, 0, 0, 0));
        room.assignSeat(alice);
        room.assignSeat(bob);

        alice.setOpen(false);
        room.removeConnection(alice);

        Map<String, Object> gameEnded = pollForMessageOfType(bob, Protocol.TYPE_GAME_ENDED, 3000);
        assertEquals("BLACK", gameEnded.get("winner"));
        assertEquals("Bob", gameEnded.get("winnerUsername"));
        assertEquals("Alice", gameEnded.get("loserUsername"));
        assertTrue(room.isGameOver());

        Account bobAfter = accountRepository.findOrCreateAccount("Bob");
        assertEquals(1216, bobAfter.getElo());
    }

    @Test
    public void summarizeReflectsStatusThroughARoomsLifecycle() {
        Room room = newRoom("room-1", 50);
        assertEquals("WAITING_FOR_OPPONENT", room.summarize().get("status"));

        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        room.assignSeat(alice);
        assertEquals("WAITING_FOR_OPPONENT", room.summarize().get("status"));

        room.assignSeat(bob);
        assertEquals("IN_PROGRESS", room.summarize().get("status"));
    }

    @Test
    public void broadcastsDuringTickNeverReachAConnectionThatHasClosed() {
        Room room = newRoom("room-1", 30_000);
        FakeConnection alice = login("Alice");
        FakeConnection bob = login("Bob");
        room.assignSeat(alice);
        room.assignSeat(bob);
        bob.setOpen(false);
        int bobMessagesBeforeTick = bob.sentMessages.size();

        room.tick(33);

        assertTrue("a still-open connection should receive the tick's STATE broadcast",
                alice.sentMessages.size() > 0);
        assertEquals("a closed connection must never be sent to",
                bobMessagesBeforeTick, bob.sentMessages.size());
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

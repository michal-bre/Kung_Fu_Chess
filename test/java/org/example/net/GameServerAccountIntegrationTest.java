package org.example.net;

import org.example.account.Account;
import org.example.account.AccountRepository;
import org.example.account.EloRating;
import org.example.account.InMemoryAccountRepository;
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
 * Real, no-mocking end-to-end coverage for Phase 3 (accounts + ELO): a real
 * GameServer, wired to an explicit InMemoryAccountRepository (so the test
 * can inspect persisted state directly - see AccountRepository's class doc
 * for why SqliteAccountRepository isn't exercised directly here, this
 * project's sandbox has no way to obtain a real SQLite JDBC driver jar),
 * driven by real org.java_websocket clients over an actual socket.
 *
 * Uses a minimal two-piece board (a white rook two squares from a black
 * king) rather than the standard starting position - the same minimal setup
 * EventBusIntegrationTest.gameEndedEventFiresWithWinnerOnKingCapture uses -
 * so a game-ending capture can be driven in a single move instead of a long
 * sequence, keeping this test's one necessary real-time wait (for the
 * server's tick loop to actually advance the move to completion) as short
 * as possible.
 */
public class GameServerAccountIntegrationTest {

    private GameServer server;
    private AccountRepository accountRepository;
    private RecordingClient white;
    private RecordingClient black;
    private int port;

    @Before
    public void startServer() throws Exception {
        List<String> minimalPosition = Arrays.asList(
                "wR . bK . . . . .",
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . ."
        );
        Board board = org.example.adapters.BoardParser.parse(minimalPosition);

        accountRepository = new InMemoryAccountRepository();
        port = 24000 + (int) (System.nanoTime() % 4000);
        server = new GameServer(port, () -> board, accountRepository);
        server.start();
        server.startGameLoop();
        Thread.sleep(300);
    }

    @After
    public void stopServer() throws Exception {
        if (white != null) white.closeBlocking();
        if (black != null) black.closeBlocking();
        server.stop(0);
    }

    @Test
    public void loginCreatesAnAccountAtDefaultRatingAndReportsItBack() throws Exception {
        BlockingQueue<Map<String, Object>> whiteMessages = new LinkedBlockingQueue<>();
        white = new RecordingClient(new URI("ws://localhost:" + port), whiteMessages);
        assertTrue(white.connectBlocking(5, TimeUnit.SECONDS));

        white.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Alice")));

        Map<String, Object> accountInfo = takeOfType(whiteMessages, Protocol.TYPE_ACCOUNT_INFO);
        assertEquals("Alice", accountInfo.get("username"));
        assertEquals((double) EloRating.DEFAULT_RATING, accountInfo.get("elo"));
        assertEquals(0.0, accountInfo.get("gamesPlayed"));
    }

    @Test
    public void loginReturnsThePreviouslyPersistedRatingOnASecondSession() throws Exception {
        accountRepository.recordGameResult("Alice", 1250, "SomeoneElse", 1150);

        BlockingQueue<Map<String, Object>> whiteMessages = new LinkedBlockingQueue<>();
        white = new RecordingClient(new URI("ws://localhost:" + port), whiteMessages);
        assertTrue(white.connectBlocking(5, TimeUnit.SECONDS));

        white.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Alice")));

        Map<String, Object> accountInfo = takeOfType(whiteMessages, Protocol.TYPE_ACCOUNT_INFO);
        assertEquals(1250.0, accountInfo.get("elo"));
        assertEquals(1.0, accountInfo.get("gamesPlayed"));
    }

    @Test
    public void kingCaptureUpdatesBothPlayersEloAndBroadcastsTheChange() throws Exception {
        BlockingQueue<Map<String, Object>> whiteMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> blackMessages = new LinkedBlockingQueue<>();

        white = new RecordingClient(new URI("ws://localhost:" + port), whiteMessages);
        assertTrue(white.connectBlocking(5, TimeUnit.SECONDS));
        black = new RecordingClient(new URI("ws://localhost:" + port), blackMessages);
        assertTrue(black.connectBlocking(5, TimeUnit.SECONDS));

        // See GameServerIntegrationTest for why white's LOGIN must be
        // confirmed (via its ACCOUNT_INFO ack) before black's is sent - two
        // sends this close together over separate connections have no
        // guaranteed arrival order at the server otherwise.
        white.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Alice")));
        takeOfType(whiteMessages, Protocol.TYPE_ACCOUNT_INFO);
        black.send(Json.write(Protocol.msg(Protocol.TYPE_LOGIN, "username", "Bob")));
        takeOfType(blackMessages, Protocol.TYPE_ACCOUNT_INFO);
        takeOfType(whiteMessages, Protocol.TYPE_COLOR_ASSIGNED);
        takeOfType(blackMessages, Protocol.TYPE_COLOR_ASSIGNED);

        // The white rook at a8 slides onto the black king at c8 - a 2-square
        // move, same geometry as EventBusIntegrationTest's king-capture test.
        white.send(Json.write(Protocol.msg(Protocol.TYPE_MOVE, "from", "a8", "to", "c8")));

        Map<String, Object> gameEnded = takeOfType(whiteMessages, Protocol.TYPE_GAME_ENDED, 6000);
        assertEquals("WHITE", gameEnded.get("winner"));
        assertEquals("Alice", gameEnded.get("winnerUsername"));
        assertEquals("Bob", gameEnded.get("loserUsername"));
        assertEquals(1216.0, gameEnded.get("winnerNewElo"));
        assertEquals(1184.0, gameEnded.get("loserNewElo"));
        assertEquals(16.0, gameEnded.get("winnerEloDelta"));
        assertEquals(-16.0, gameEnded.get("loserEloDelta"));

        Account alice = accountRepository.findOrCreateAccount("Alice");
        Account bob = accountRepository.findOrCreateAccount("Bob");
        assertEquals(1216, alice.getElo());
        assertEquals(1, alice.getWins());
        assertEquals(1184, bob.getElo());
        assertEquals(1, bob.getLosses());
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

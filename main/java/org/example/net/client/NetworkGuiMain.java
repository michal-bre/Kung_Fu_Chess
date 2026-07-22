package org.example.net.client;

import org.example.bus.EventBus;
import org.example.bus.GameEndedEvent;
import org.example.bus.GameStartedEvent;
import org.example.audio.SoundPlayer;
import org.example.controller.BoardMapper;
import org.example.model.Board;
import org.example.view.BoardInputListener;
import org.example.view.BoardView;
import org.example.view.GamePanel;
import org.example.view.GameWindow;
import org.example.view.ImgRenderer;
import org.example.view.Renderer;

import java.io.Console;
import java.net.URI;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * Composition root for the networked (multiplayer) Swing client - the
 * network counterpart to GuiMain (local hot-seat mode remains completely
 * untouched and reachable via GuiMain, per the "keep both" decision).
 *
 * Per the CTD 26 spec's shell-based login (slides 4-5: username, then
 * username+password): the prompts happen on the console, via a plain
 * Scanner, BEFORE any Swing window is created - mirroring
 * CommandLineAdapter's existing Scanner-based input pattern, just for a
 * couple of lines instead of a full board/commands loop. The password
 * prompt masks input via java.io.Console when one is available (a real
 * terminal); when run from an IDE's console (no real Console - see
 * readPassword), it falls back to plain, unmasked Scanner input instead of
 * failing outright.
 *
 * Reuses BoardView, GamePanel, ImgRenderer, GameWindow, BoardInputListener,
 * and SoundPlayer completely unchanged from local mode - see BoardView's,
 * GamePanel's, and InputReceiver's class docs for exactly why each of those
 * needed zero or minimal changes to support this. What's different from
 * GuiMain is what feeds them: no Board/GameEngine/GameController/
 * InteractionHandler/GameLoop here at all - GameClient supplies the
 * GameSnapshot (via NetworkInputReceiver's composite-snapshot method) and
 * republishes the server's bus events onto a local EventBus instead.
 *
 * Usage: {@code java org.example.net.client.NetworkGuiMain [host] [port]}
 * (defaults to localhost:8887, matching ServerMain's default port).
 */
public final class NetworkGuiMain {

    private static final int CELL_SIZE = Board.CELL_SIZE;
    private static final int BOARD_WIDTH = 8;
    private static final int BOARD_HEIGHT = 8;
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 8887;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;

        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter your username: ");
        String username = scanner.nextLine().trim();
        if (username.isEmpty()) {
            username = "Player" + (System.currentTimeMillis() % 1000);
        }
        String password = readPassword(scanner);

        EventBus localBus = new EventBus();

        BoardMapper boardMapper = new BoardMapper(CELL_SIZE, BOARD_HEIGHT, BOARD_WIDTH);

        Renderer renderer = new ImgRenderer(CELL_SIZE);
        GameWindow[] windowHolder = new GameWindow[1];
        BoardView[] boardViewHolder = new BoardView[1];
        GameClient[] gameClientHolder = new GameClient[1];

        GameClient gameClient = new GameClient(
                URI.create("ws://" + host + ":" + port),
                username,
                password,
                localBus,
                () -> {
                    if (boardViewHolder[0] != null) boardViewHolder[0].repaint();
                    // Phase 4: reflect matchmaking/disconnect status in the
                    // title on every STATE update (piggybacking on the same
                    // ~33ms cadence repaint already uses, rather than a
                    // separate poller) - but only while there's actually
                    // something to say; once it clears (a match starts or
                    // resumes), the GameStartedEvent/GameEndedEvent
                    // subscriptions below are what own the title instead.
                    // Reached through gameClientHolder rather than the
                    // gameClient local variable directly, since this lambda
                    // is itself part of the expression that initializes
                    // gameClient - referencing it by name here wouldn't
                    // compile (definite-assignment: gameClient doesn't exist
                    // yet from the compiler's point of view mid-construction).
                    GameClient self = gameClientHolder[0];
                    if (windowHolder[0] != null && self != null && self.getConnectionStatus() != null) {
                        windowHolder[0].setStatus(self.getConnectionStatus());
                    }
                });
        gameClientHolder[0] = gameClient;

        NetworkInputReceiver inputReceiver = new NetworkInputReceiver(gameClient, boardMapper);

        BoardView boardView = new BoardView(renderer, inputReceiver::getEffectiveSnapshot,
                BOARD_WIDTH, BOARD_HEIGHT, CELL_SIZE);
        boardViewHolder[0] = boardView;
        boardView.addMouseListener(new BoardInputListener(inputReceiver, boardView::getScale, boardView::repaint));

        GamePanel gamePanel = new GamePanel(boardView, localBus);

        // Phase 8: a live roster of everyone in the room and their role
        // ("Alice - White", "Carol - Viewer", ...) - network-only, so it's
        // composed alongside GamePanel here rather than added to GamePanel
        // itself (see NetworkGameLayout's class doc). Wired directly to
        // GameClient rather than through the shared EventBus, since
        // connectionStatus (WAITING/OPPONENT_DISCONNECTED/...) already sets
        // the precedent for network-only UI state bypassing the bus that
        // both local and networked modes share.
        RosterPanel rosterPanel = new RosterPanel();
        gameClient.setRosterListener(() -> rosterPanel.refresh(gameClient.getRoster()));
        NetworkGameLayout networkGameLayout = new NetworkGameLayout(gamePanel, rosterPanel);

        GameWindow window = new GameWindow("Kung Fu Chess — " + username, networkGameLayout);
        windowHolder[0] = window;
        localBus.subscribe(GameStartedEvent.class, event -> window.setStatus("Game in progress"));
        localBus.subscribe(GameEndedEvent.class, event -> {
            String base = "GAME OVER — " + event.getWinner().name() + " WINS";
            String eloSummary = gameClient.getLastGameEndedSummary();
            window.setStatus(eloSummary == null ? base : base + " — " + eloSummary);
        });
        new SoundPlayer(localBus);

        System.out.println("Connecting to ws://" + host + ":" + port + " as " + username + " ...");
        if (!gameClient.connectBlocking(10, TimeUnit.SECONDS)) {
            System.err.println("Could not connect to server at ws://" + host + ":" + port);
            System.exit(1);
        }

        // LOGIN was already sent from GameClient.onOpen the moment the
        // handshake completed, above - wait here for its definitive answer
        // (ACCOUNT_INFO on success, ERROR on a wrong password for an
        // existing account) before doing anything else, so a bad password
        // fails loudly and immediately instead of silently falling through
        // to a lobby where every room action would then fail too.
        long loginDeadline = System.currentTimeMillis() + 5000;
        while (gameClient.getMyAccount() == null && gameClient.getLastError() == null
                && System.currentTimeMillis() < loginDeadline) {
            Thread.sleep(50);
        }
        if (gameClient.getMyAccount() == null) {
            String reason = gameClient.getLastError() != null ? gameClient.getLastError() : "no response from server";
            System.err.println("Login failed: " + reason);
            System.err.println("Run the program again to retry with a different username/password.");
            gameClient.close();
            System.exit(1);
        }

        // Phase 5: the Create/Join/Cancel room lobby gates the game window -
        // LOGIN alone no longer puts a connection into any game (see
        // GameServer's class doc); the player has to explicitly pick a room
        // first. RoomLobbyDialog blocks until that happens (or Cancel).
        boolean joinedARoom = RoomLobbyDialog.show(gameClient, username);
        if (!joinedARoom) {
            System.out.println("Cancelled - no room joined.");
            gameClient.close();
            System.exit(0);
        }

        window.show();
    }

    /**
     * Prompts for a password on the console, masking keystrokes via
     * {@code System.console()} when a real system console is attached - the
     * normal case when this program is launched from an actual terminal.
     * {@code System.console()} returns null when there ISN'T one, which is
     * exactly what happens running this class from most IDEs' built-in Run
     * windows (IntelliJ included - its Run console is not a real system
     * console) - in that case this falls back to plain Scanner input with a
     * one-line heads-up that it won't be masked, rather than throwing a
     * NullPointerException or refusing to let the player log in at all.
     */
    private static String readPassword(Scanner scanner) {
        Console console = System.console();
        if (console != null) {
            char[] entered = console.readPassword("Enter your password: ");
            String password = new String(entered);
            java.util.Arrays.fill(entered, ' ');
            return password;
        }
        System.out.print("Enter your password (not masked - no console attached): ");
        return scanner.nextLine();
    }
}

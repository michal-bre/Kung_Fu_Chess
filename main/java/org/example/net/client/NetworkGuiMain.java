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

import java.net.URI;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * Composition root for the networked (multiplayer) Swing client - the
 * network counterpart to GuiMain (local hot-seat mode remains completely
 * untouched and reachable via GuiMain, per the "keep both" decision).
 *
 * Per the CTD 26 spec's shell-based login (slide 4): the username prompt
 * happens on the console, via a plain Scanner, BEFORE any Swing window is
 * created - mirroring CommandLineAdapter's existing Scanner-based input
 * pattern, just for a single line instead of a full board/commands loop.
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

        EventBus localBus = new EventBus();

        BoardMapper boardMapper = new BoardMapper(CELL_SIZE, BOARD_HEIGHT, BOARD_WIDTH);

        Renderer renderer = new ImgRenderer(CELL_SIZE);
        GameWindow[] windowHolder = new GameWindow[1];
        BoardView[] boardViewHolder = new BoardView[1];
        GameClient[] gameClientHolder = new GameClient[1];

        GameClient gameClient = new GameClient(
                URI.create("ws://" + host + ":" + port),
                username,
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

        GameWindow window = new GameWindow("Kung Fu Chess — " + username, gamePanel);
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

        window.show();
    }
}

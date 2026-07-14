package org.example.view;

import org.example.controller.GameController;

import javax.swing.*;

/**
 * View layer: drives the passage of game time and keeps the view in sync
 * with it.
 *
 * Kung Fu Chess moves resolve over real time (MovementEngine.advanceTime) -
 * unlike the CLI, which only advances time in response to an explicit WAIT
 * command, the GUI needs something ticking on its own so an in-flight move
 * actually arrives (and captures actually resolve) while the player is
 * watching, without them having to trigger it.
 *
 * A javax.swing.Timer is used rather than a raw Thread so every tick already
 * runs on the Swing Event Dispatch Thread: GameController/Board are not
 * thread-safe, and Swing's own repaint machinery must only be touched from
 * the EDT anyway, so this sidesteps synchronization entirely instead of
 * needing it.
 */
public class GameLoop {

    private static final int TICK_INTERVAL_MS = 16; // ~60 Hz

    private final Timer timer;
    private long lastTickMillis;

    public GameLoop(GameController gameController, Runnable onTick) {
        this.timer = new Timer(TICK_INTERVAL_MS, e -> {
            long now = System.currentTimeMillis();
            long delta = now - lastTickMillis;
            lastTickMillis = now;

            gameController.advanceTime(delta);
            onTick.run();
        });
    }

    public void start() {
        lastTickMillis = System.currentTimeMillis();
        timer.start();
    }

    public void stop() {
        timer.stop();
    }
}

package org.example.audio;

import org.example.bus.EventBus;
import org.example.bus.GameEndedEvent;
import org.example.bus.GameStartedEvent;
import org.example.bus.JumpPerformedEvent;
import org.example.bus.MoveLoggedEvent;

/**
 * Subscribes to the game's EventBus and plays a short, synthesized tone for
 * each kind of event - the "adding sound" bus use case from the CTD 26
 * server spec. Every tone is generated on the fly by ToneGenerator (see its
 * class doc for why); this class only decides WHICH tone/duration goes
 * with WHICH event, and never touches the engine/controller/view layers
 * directly - it only ever hears about the game through bus events, exactly
 * like GamePanel's score/history subscriptions.
 */
public final class SoundPlayer {

    private static final int MOVE_DURATION_MS = 90;
    private static final int CAPTURE_DURATION_MS = 140;
    private static final int JUMP_DURATION_MS = 70;
    private static final int GAME_START_DURATION_MS = 220;
    private static final int GAME_END_DURATION_MS = 320;

    public SoundPlayer(EventBus bus) {
        bus.subscribe(MoveLoggedEvent.class, this::onMoveLogged);
        bus.subscribe(JumpPerformedEvent.class, event -> ToneGenerator.play(880.00, JUMP_DURATION_MS, 0.35));
        bus.subscribe(GameStartedEvent.class, event -> ToneGenerator.play(523.25, GAME_START_DURATION_MS, 0.3));
        bus.subscribe(GameEndedEvent.class, event -> ToneGenerator.play(220.00, GAME_END_DURATION_MS, 0.4));
    }

    private void onMoveLogged(MoveLoggedEvent event) {
        if (event.isCapture()) {
            ToneGenerator.play(330.00, CAPTURE_DURATION_MS, 0.4);
        } else {
            ToneGenerator.play(660.00, MOVE_DURATION_MS, 0.25);
        }
    }
}

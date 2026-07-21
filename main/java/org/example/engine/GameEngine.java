package org.example.engine;

import org.example.model.Piece;
import org.example.model.Position;

import java.util.Optional;

/**
 * Engine layer: the application-service boundary the controller layer talks
 * to. This is the fix for the architecture review's items 5 and 6 - before
 * this interface existed, InteractionHandler itself performed application
 * guards, rule validation, distance/duration math, direct Board mutation,
 * and game-over/score bookkeeping - i.e. it was doing GameEngine's job in
 * addition to its own (interpreting clicks).
 *
 * The controller layer's job shrinks to: turn a pixel click into a Position
 * (BoardMapper), track which square is currently selected, and translate the
 * resulting user intent into exactly one of these calls. Everything about
 * WHETHER that intent is legal and HOW it plays out over time belongs here,
 * behind this interface - never leaked back out to the controller.
 *
 * Mirrors a typical web API layering: an HTTP controller calls
 * orderService.placeOrder(...) and does not itself compute inventory,
 * payment, or shipping - it only translates a request into a single service
 * call and relays the result.
 */
public interface GameEngine {

    /**
     * Requests that whatever piece is at {@code source} move to
     * {@code destination}. Performs every check that determines whether this
     * is legal (game-over, rule validity, destination not already reserved
     * by an active move of the same color) and, if accepted, schedules the
     * move with the real-time arbiter (EnginePort) - the controller layer
     * never sees any of those intermediate steps.
     */
    MoveResult requestMove(Position source, Position destination);

    /**
     * Requests that whatever piece is at {@code position} jump in place to
     * defend that square. Performs every check (game-over, resting, already
     * moving) and applies the reaction-window rule that decides whether a
     * jump against an already-in-flight enemy attack succeeds (created while
     * within the reaction window - see JUMP_DEFENSE_WINDOW_MS) or comes too
     * late (the enemy's move is instead forced to complete immediately,
     * capturing the piece that tried to jump). Every board mutation and
     * game-over/score bookkeeping this can trigger happens inside this call,
     * never in the controller layer.
     */
    JumpResult requestJump(Position position);

    /** Advances the real-time arbiter's clock - see EnginePort.advanceTime. */
    void advanceTime(long millis);

    boolean isGameOver();

    /** The real-time arbiter's current clock reading - see EnginePort.getGameTimeMillis. */
    long getGameTimeMillis();

    /** The piece currently occupying {@code position}, if any. */
    Optional<Piece> pieceAt(Position position);

    /**
     * Whether the piece at {@code position} (if any) is currently eligible
     * to be selected for a new move/jump - i.e. not already moving and not
     * resting. The controller layer uses this instead of separately querying
     * EnginePort itself.
     */
    boolean canSelect(Position position);

    int getScore(Piece.Color color);

    /**
     * Builds a complete, immutable GameSnapshot for the current instant -
     * see GameSnapshot's class doc. selectedPosition/rejectedPosition/
     * rejectedAtMillis are controller-owned UI state (which square is
     * selected, which square's last move attempt was rejected and when) that
     * the engine has no reason to track itself, so the controller passes
     * them in to be folded into the same snapshot the renderer reads,
     * instead of the renderer having to consult the controller separately.
     */
    GameSnapshot snapshot(Position selectedPosition, Position rejectedPosition, long rejectedAtMillis);

    /**
     * Ends the game immediately in favor of whichever color is NOT
     * {@code resigningColor} - the king-capture win condition that ends
     * every other game in this project, forced without an actual capture.
     * Added for Phase 4's disconnect/auto-resign handling: GameServer calls
     * this when a connected player's WebSocket drops and doesn't reconnect
     * within the grace period (see GameServer's class doc), so a dropped
     * connection resolves the game exactly like any other ending - through
     * GameEndedEvent, ELO update, and all - rather than needing a special
     * case anywhere else. A no-op if the game is already over (a resignation
     * can't un-decide an already-decided game, and can't happen twice).
     */
    void resign(Piece.Color resigningColor);
}

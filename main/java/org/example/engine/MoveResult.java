package org.example.engine;

/**
 * Engine layer: the outcome of a GameEngine.requestMove call - whether the
 * move was accepted, and if not, why. Lets the controller layer react to a
 * rejected move (e.g. flashing the target square) without needing to
 * re-derive the reason itself by re-running validation.
 */
public final class MoveResult {
    private final boolean accepted;
    private final String reason;

    private MoveResult(boolean accepted, String reason) {
        this.accepted = accepted;
        this.reason = reason;
    }

    public static MoveResult accepted() {
        return new MoveResult(true, "ok");
    }

    public static MoveResult rejected(String reason) {
        return new MoveResult(false, reason);
    }

    public boolean isAccepted() {
        return accepted;
    }

    public String getReason() {
        return reason;
    }
}

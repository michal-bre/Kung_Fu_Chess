package org.example.engine;

/**
 * Engine layer: the outcome of a GameEngine.requestJump call - see
 * MoveResult's class doc for the same rationale, applied to jumps.
 */
public final class JumpResult {
    private final boolean accepted;
    private final String reason;

    private JumpResult(boolean accepted, String reason) {
        this.accepted = accepted;
        this.reason = reason;
    }

    public static JumpResult accepted() {
        return new JumpResult(true, "ok");
    }

    public static JumpResult rejected(String reason) {
        return new JumpResult(false, reason);
    }

    public boolean isAccepted() {
        return accepted;
    }

    public String getReason() {
        return reason;
    }
}

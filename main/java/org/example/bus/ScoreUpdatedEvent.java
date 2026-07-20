package org.example.bus;

import org.example.model.Piece;

/**
 * Published once every time a color's running score changes - see
 * MovementEngine.addScore, the single method every capture/game-over site
 * in the engine funnels through, so this fires uniformly regardless of
 * which of the engine's several capture paths (air capture, simultaneous
 * arrival, late-jump forced completion, ...) triggered it.
 */
public final class ScoreUpdatedEvent {
    private final Piece.Color color;
    private final int newScore;

    public ScoreUpdatedEvent(Piece.Color color, int newScore) {
        this.color = color;
        this.newScore = newScore;
    }

    public Piece.Color getColor() {
        return color;
    }

    public int getNewScore() {
        return newScore;
    }
}

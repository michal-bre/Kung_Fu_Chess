package org.example.bus;

import org.example.model.Piece;

/**
 * Published exactly once per game, the moment a winner is set (see
 * MovementEngine.setWinner) - i.e. the instant a king is actually
 * captured, at whichever of the engine's several capture sites it happens.
 * Never re-derived by scanning the board.
 */
public final class GameEndedEvent {
    private final Piece.Color winner;

    public GameEndedEvent(Piece.Color winner) {
        this.winner = winner;
    }

    public Piece.Color getWinner() {
        return winner;
    }
}

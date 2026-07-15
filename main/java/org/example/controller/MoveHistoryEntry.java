package org.example.controller;

import org.example.model.Piece;

/**
 * Controller layer: a single completed move as it should appear in a
 * move-history table - which side made it, when (engine game time, in
 * milliseconds, at the moment the move was created), and a short algebraic-
 * style notation string describing it.
 *
 * Recorded by InteractionHandler at the moment a move is successfully
 * created (see handleClick), not at arrival: that's when the click actually
 * happened and when the board state used to decide capture-or-not was read,
 * matching how a player would expect a move log to read.
 *
 * The notation is a simplified approximation of Standard Algebraic
 * Notation, not a full implementation: it has no disambiguation between two
 * like pieces that could reach the same square, no check/checkmate suffix
 * (this engine has no check-detection at all), and no castling (the King's
 * move shape only ever allows a single square, so castling can't occur
 * here). For this project's purposes - a human-readable move log next to
 * the board - piece letter + optional capture "x" + destination square
 * (e.g. "Nc6", "Bxc6", "e4", "exd5") is enough.
 */
public final class MoveHistoryEntry {
    private final Piece.Color color;
    private final long timeMillis;
    private final String notation;

    public MoveHistoryEntry(Piece.Color color, long timeMillis, String notation) {
        this.color = color;
        this.timeMillis = timeMillis;
        this.notation = notation;
    }

    public Piece.Color getColor() {
        return color;
    }

    public long getTimeMillis() {
        return timeMillis;
    }

    public String getNotation() {
        return notation;
    }
}

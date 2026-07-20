package org.example.bus;

import org.example.model.Piece;

/**
 * Published once per successful jump. InteractionHandler.handleJump has no
 * move-history equivalent for jumps (see its class doc - a jump has no
 * chess-notation form), so this is the only bus signal that a jump
 * happened at all.
 */
public final class JumpPerformedEvent {
    private final Piece.Color color;

    public JumpPerformedEvent(Piece.Color color) {
        this.color = color;
    }

    public Piece.Color getColor() {
        return color;
    }
}

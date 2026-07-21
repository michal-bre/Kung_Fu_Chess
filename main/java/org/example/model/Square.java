package org.example.model;

import java.util.Optional;

/**
 * Model layer: algebraic notation (like "e2") <-> Position conversion.
 *
 * The same file-letter/rank-number math already lives, inline and private,
 * inside InteractionHandler.formatNotation - written there to build a move's
 * display string for the move-history table. This class exists for a
 * different reason: the networked client/server protocol (Phase 2) needs a
 * PUBLIC, well-defined, parseable text format for "the square the user
 * clicked", since a wire message can't hand over a Position object directly.
 * Algebraic notation was the obvious choice since it's already the format
 * the move history displays, and it's compact and human-readable in captured
 * network traffic during debugging.
 *
 * Deliberately NOT extracted by refactoring InteractionHandler's existing
 * private methods to call into this class - InteractionHandler is a small,
 * fully-tested, working piece of the ALREADY-SHIPPED local/hot-seat mode,
 * and touching it here would risk it for zero behavioral benefit (the two
 * pieces of logic are simple enough that a little duplication is cheaper
 * than the risk).
 *
 * fromAlgebraic returns Optional rather than throwing, because unlike
 * InteractionHandler's formatNotation (which only ever formats trusted,
 * already-validated positions), this class's parse direction is the one
 * PARSING untrusted text arriving over the network from a client - a
 * malformed or out-of-range square name is an expected possibility, not a
 * programming error, and callers (GameServer) need to be able to reject it
 * cleanly instead of crashing the connection.
 */
public final class Square {

    private Square() {
    }

    /** "e2"-style algebraic name for a Position, given the board's height (needed since rank counts down from the top row - see InteractionHandler.rankNumber for the identical reasoning). */
    public static String toAlgebraic(Position position, int boardHeight) {
        char file = (char) ('a' + position.getCol());
        int rank = boardHeight - position.getRow();
        return "" + file + rank;
    }

    /** Parses a square name like "e2" back into a Position, or empty if the text isn't a well-formed square name (e.g. wrong length, non-letter file, non-numeric rank). Does NOT bounds-check against boardWidth - callers that need that should check the returned Position's row/col themselves, since this class has no board-width parameter to check against. */
    public static Optional<Position> fromAlgebraic(String square, int boardHeight) {
        if (square == null || square.length() < 2) {
            return Optional.empty();
        }
        char fileChar = Character.toLowerCase(square.charAt(0));
        if (fileChar < 'a' || fileChar > 'z') {
            return Optional.empty();
        }
        String rankPart = square.substring(1);
        int rank;
        try {
            rank = Integer.parseInt(rankPart);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        int col = fileChar - 'a';
        int row = boardHeight - rank;
        return Optional.of(new Position(row, col));
    }
}

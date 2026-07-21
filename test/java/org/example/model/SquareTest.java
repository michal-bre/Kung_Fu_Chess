package org.example.model;

import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Direct coverage for Square's algebraic-notation <-> Position conversion -
 * the wire-protocol format MOVE/JUMP messages use (see GameServer/GameClient
 * and Square's own class doc for why this exists separately from
 * InteractionHandler's near-identical private notation logic).
 */
public class SquareTest {

    @Test
    public void toAlgebraicMatchesStandardChessNotationOnAnEightRowBoard() {
        // Row 0 is the top of the board (rank 8), row 7 is the bottom (rank 1) -
        // same convention InteractionHandler.rankNumber uses.
        assertEquals("a8", Square.toAlgebraic(new Position(0, 0), 8));
        assertEquals("h1", Square.toAlgebraic(new Position(7, 7), 8));
        assertEquals("e4", Square.toAlgebraic(new Position(4, 4), 8));
        assertEquals("e2", Square.toAlgebraic(new Position(6, 4), 8));
    }

    @Test
    public void fromAlgebraicIsTheExactInverseOfToAlgebraic() {
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Position original = new Position(row, col);
                String algebraic = Square.toAlgebraic(original, 8);
                Optional<Position> parsed = Square.fromAlgebraic(algebraic, 8);
                assertTrue(parsed.isPresent());
                assertEquals(original, parsed.get());
            }
        }
    }

    @Test
    public void fromAlgebraicRejectsMalformedInput() {
        assertFalse(Square.fromAlgebraic(null, 8).isPresent());
        assertFalse(Square.fromAlgebraic("", 8).isPresent());
        assertFalse(Square.fromAlgebraic("e", 8).isPresent());
        assertFalse(Square.fromAlgebraic("9z", 8).isPresent());
        assertFalse(Square.fromAlgebraic("eX", 8).isPresent());
    }

    @Test
    public void fromAlgebraicIsCaseInsensitiveOnTheFileLetter() {
        assertEquals(Square.fromAlgebraic("e4", 8), Square.fromAlgebraic("E4", 8));
    }
}

package org.example;
import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;
import org.example.adapters.BoardParser;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Iteration 1: Board Parsing and Validation
 *
 * Test that:
 * 1. Valid boards are parsed correctly
 * 2. Board dimensions are inferred correctly
 * 3. Row width mismatch throws ERROR ROW_WIDTH_MISMATCH
 * 4. Unknown tokens throw ERROR UNKNOWN_TOKEN
 * 5. Board can be printed in canonical form (space-separated, dot for empty)
 */
public class Iteration1_BoardParsingTest {

    @Test
    public void testParseEmptyBoard() {
        List<String> boardLines = Arrays.asList(
            ". .",
            ". ."
        );
        Board board = BoardParser.parse(boardLines);
        assertNotNull(board);
        assertEquals(2, board.getWidth());
        assertEquals(2, board.getHeight());
    }

    @Test
    public void testParseWithPieces() {
        List<String> boardLines = Arrays.asList(
            "wK bK",
            ". ."
        );
        Board board = BoardParser.parse(boardLines);
        assertEquals(2, board.getWidth());
        assertEquals(2, board.getHeight());
        assertNotNull(board.getPiece(new Position(0, 0)));
        assertEquals("wK", board.getPiece(new Position(0, 0)).toString());
        assertNotNull(board.getPiece(new Position(0, 1)));
        assertEquals("bK", board.getPiece(new Position(0, 1)).toString());
    }

    @Test
    public void testInferBoardDimensions_3x3() {
        List<String> boardLines = Arrays.asList(
            ". . .",
            ". . .",
            ". . ."
        );
        Board board = BoardParser.parse(boardLines);
        assertEquals(3, board.getWidth());
        assertEquals(3, board.getHeight());
    }

    @Test
    public void testInferBoardDimensions_8x8() {
        List<String> boardLines = Arrays.asList(
            "wR wN wB wQ wK wB wN wR",
            "wP wP wP wP wP wP wP wP",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            "bP bP bP bP bP bP bP bP",
            "bR bN bB bQ bK bB bN bR"
        );
        Board board = BoardParser.parse(boardLines);
        assertEquals(8, board.getWidth());
        assertEquals(8, board.getHeight());
    }

    @Test
    public void testRowWidthMismatchError() {
        List<String> boardLines = Arrays.asList(
            ". . .",
            ". ."  // ← width mismatch
        );
        try {
            BoardParser.parse(boardLines);
            fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("ERROR ROW_WIDTH_MISMATCH", e.getMessage());
        }
    }

    @Test
    public void testUnknownTokenError_InvalidPieceType() {
        List<String> boardLines = Arrays.asList(
            "wX ."  // ← 'X' is not a valid piece type
        );
        try {
            BoardParser.parse(boardLines);
            fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("ERROR UNKNOWN_TOKEN", e.getMessage());
        }
    }

    @Test
    public void testUnknownTokenError_InvalidColor() {
        List<String> boardLines = Arrays.asList(
            "rK ."  // ← 'r' is not a valid color (only 'w' and 'b')
        );
        try {
            BoardParser.parse(boardLines);
            fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("ERROR UNKNOWN_TOKEN", e.getMessage());
        }
    }

    @Test
    public void testUnknownTokenError_WrongTokenLength() {
        List<String> boardLines = Arrays.asList(
            "wKQ ."  // ← token length != 2
        );
        try {
            BoardParser.parse(boardLines);
            fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("ERROR UNKNOWN_TOKEN", e.getMessage());
        }
    }

    @Test
    public void testEmptyBoardLinesError() {
        List<String> boardLines = Arrays.asList();
        try {
            BoardParser.parse(boardLines);
            fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("ERROR ROW_WIDTH_MISMATCH", e.getMessage());
        }
    }

    @Test
    public void testAllValidPieceTypes() {
        List<String> boardLines = Arrays.asList(
            "wK wQ wR wB wN wP",
            "bK bQ bR bB bN bP"
        );
        Board board = BoardParser.parse(boardLines);
        assertEquals(6, board.getWidth());
        assertEquals(2, board.getHeight());

        // Verify all pieces parsed
        assertEquals("wK", board.getPiece(new Position(0, 0)).toString());
        assertEquals("bP", board.getPiece(new Position(1, 5)).toString());
    }

    @Test
    public void testMixedEmptyAndPieces() {
        List<String> boardLines = Arrays.asList(
            "wK . bK",
            ". . .",
            "wP . bP"
        );
        Board board = BoardParser.parse(boardLines);
        assertEquals(3, board.getWidth());
        assertEquals(3, board.getHeight());

        // Verify empty cells are null
        assertNull(board.getPiece(new Position(0, 1)));
        assertNull(board.getPiece(new Position(1, 0)));

        // Verify pieces exist
        assertNotNull(board.getPiece(new Position(0, 0)));
        assertNotNull(board.getPiece(new Position(2, 2)));
    }

    @Test
    public void testWhitePieces() {
        List<String> boardLines = Arrays.asList(
            "wK wQ wR wB wN wP"
        );
        Board board = BoardParser.parse(boardLines);
        for (int col = 0; col < 6; col++) {
            Piece piece = board.getPiece(new Position(0, col));
            assertEquals(Piece.Color.WHITE, piece.getColor());
        }
    }

    @Test
    public void testBlackPieces() {
        List<String> boardLines = Arrays.asList(
            "bK bQ bR bB bN bP"
        );
        Board board = BoardParser.parse(boardLines);
        for (int col = 0; col < 6; col++) {
            Piece piece = board.getPiece(new Position(0, col));
            assertEquals(Piece.Color.BLACK, piece.getColor());
        }
    }
}

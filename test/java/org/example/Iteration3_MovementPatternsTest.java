package org.example;

import org.junit.Before;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Iteration 3: Basic Movement Patterns
 *
 * Test that:
 * 1. King moves exactly 1 square in any direction
 * 2. King cannot move 2 or more squares
 * 3. Rook moves horizontally or vertically, but not diagonally
 * 4. Rook cannot move without straight line
 * 5. Bishop moves diagonally
 * 6. Bishop cannot move horizontally or vertically
 * 7. Queen combines Rook and Bishop (any straight/diagonal)
 * 8. Knight moves in L-shape (2,1 or 1,2)
 * 9. Knight cannot move like other pieces
 */
public class Iteration3_MovementPatternsTest {

    private MoveValidator moveValidator;
    private Board board;

    @Before
    public void setUp() {
        List<String> boardLines = Arrays.asList(
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . .",
            ". . . . . . . ."
        );
        board = BoardParser.parse(boardLines);
        moveValidator = new MoveValidator(board, new MovementEngine(board));
    }

    @Test
    public void testKingMovesOneSquareUp() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.KING));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(3, 4), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue(valid);
    }

    @Test
    public void testKingMovesOneSquareDown() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.KING));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(5, 4), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue(valid);
    }

    @Test
    public void testKingMovesOneSquareLeft() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.KING));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(4, 3), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue(valid);
    }

    @Test
    public void testKingMovesOneSquareRight() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.KING));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(4, 5), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue(valid);
    }

    @Test
    public void testKingMovesOneSquareDiagonal() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.KING));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(3, 3), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue(valid);
    }

    @Test
    public void testKingCannotMoveTwoSquares() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.KING));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(2, 4), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse(valid);
    }

    @Test
    public void testRookMovesHorizontally() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(4, 7), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue(valid);
    }

    @Test
    public void testRookMovesVertically() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(1, 4), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue(valid);
    }

    @Test
    public void testRookCannotMoveDiagonally() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(3, 3), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse(valid);
    }

    @Test
    public void testBishopMovesDiagonally() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.BISHOP));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(1, 1), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue(valid);
    }

    @Test
    public void testBishopMovesDiagonallyUpRight() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.BISHOP));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(2, 6), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue(valid);
    }

    @Test
    public void testBishopCannotMoveHorizontally() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.BISHOP));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(4, 7), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse(valid);
    }

    @Test
    public void testBishopCannotMoveVertically() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.BISHOP));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(1, 4), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse(valid);
    }

    @Test
    public void testQueenMovesHorizontally() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.QUEEN));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(4, 7), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue(valid);
    }

    @Test
    public void testQueenMovesVertically() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.QUEEN));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(1, 4), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue(valid);
    }

    @Test
    public void testQueenMovesDiagonally() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.QUEEN));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(1, 1), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue(valid);
    }

    @Test
    public void testQueenCannotMoveKnightStyle() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.QUEEN));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(2, 5), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse(valid);
    }

    @Test
    public void testKnightMovesLShape_2Up1Right() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.KNIGHT));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(2, 5), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue(valid);
    }

    @Test
    public void testKnightMovesLShape_1Up2Right() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.KNIGHT));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(3, 6), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue(valid);
    }

    @Test
    public void testKnightMovesLShape_2Down1Left() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.KNIGHT));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(6, 3), 
                                                   board.getPiece(new Position(4, 4)));
        assertTrue(valid);
    }

    @Test
    public void testKnightCannotMoveStraight() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.KNIGHT));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(4, 7), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse(valid);
    }

    @Test
    public void testKnightCannotMoveDiagonal() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.KNIGHT));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(1, 1), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse(valid);
    }

    @Test
    public void testPieceCannotMoveToItself() {
        board.setPiece(4, 4, new Piece(Piece.Color.WHITE, Piece.Type.ROOK));
        boolean valid = moveValidator.isValidMove(new Position(4, 4), new Position(4, 4), 
                                                   board.getPiece(new Position(4, 4)));
        assertFalse(valid);
    }
}

package org.example.rules;

import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;

/**
 * Rules layer: pure move-legality logic.
 *
 * Depends ONLY on the model (Board, Piece, Position) plus its own
 * ActiveMoveQuery port. It has zero knowledge of:
 * - How moves are scheduled or how time works (that's the engine layer)
 * - How the UI receives or displays commands (that's the adapters layer)
 * - Any concrete engine implementation
 *
 * Because the only "engine" capability this service needs is a yes/no
 * occupancy check, that need is expressed as the rules-owned ActiveMoveQuery
 * interface (see that file for the Dependency Inversion rationale). This is
 * what makes it possible to unit test move validation with a trivial fake
 * ActiveMoveQuery, without ever constructing a MovementEngine.
 *
 * Note: this class deliberately has NO knowledge of pawn promotion. Move
 * legality and promotion are two separate concerns - whether a move is legal
 * never depends on what happens to the piece after it lands, so this class
 * must never call PawnPromotionService (or anything like it). Promotion is
 * applied afterward, by whichever layer actually commits the completed move
 * to the board (see MovementEngine).
 */
public class MoveValidationService implements MoveValidationPort {
    private final Board board;
    private final ActiveMoveQuery activeMoveQuery;

    public MoveValidationService(Board board, ActiveMoveQuery activeMoveQuery) {
        this.board = board;
        this.activeMoveQuery = activeMoveQuery;
    }

    @Override
    public int calculateDistance(Position from, Position to) {
        int deltaRow = Math.abs(to.getRow() - from.getRow());
        int deltaCol = Math.abs(to.getCol() - from.getCol());
        return Math.max(deltaRow, deltaCol);
    }

    @Override
    public boolean isPathClearWithActiveMoves(Position from, Position to, Piece.Color pieceColor) {
        int startRow = from.getRow();
        int startCol = from.getCol();
        int endRow = to.getRow();
        int endCol = to.getCol();

        int stepRow = Integer.compare(endRow - startRow, 0);
        int stepCol = Integer.compare(endCol - startCol, 0);

        int currentRow = startRow + stepRow;
        int currentCol = startCol + stepCol;

        while (currentRow != endRow || currentCol != endCol) {
            Position currentPos = new Position(currentRow, currentCol);

            if (board.getPiece(currentPos) != null) return false;
            if (activeMoveQuery.isSquareOccupiedByActiveMove(currentPos, pieceColor)) return false;

            currentRow += stepRow;
            currentCol += stepCol;
        }
        return true;
    }

    @Override
    public boolean isValidMove(Position from, Position to, Piece piece) {
        if (from.equals(to)) return false;

        if (piece.getType() == Piece.Type.PAWN) {
            return isValidPawnMove(from, to, piece);
        }

        int deltaRow = to.getRow() - from.getRow();
        int deltaCol = to.getCol() - from.getCol();

        if (!piece.getType().isValidMoveShape(deltaRow, deltaCol)) return false;

        Piece targetPiece = board.getPiece(to);
        if (targetPiece != null && targetPiece.getColor() == piece.getColor()) return false;
        if (activeMoveQuery.isSquareOccupiedByActiveMove(to, piece.getColor())) return false;

        if (piece.getType() != Piece.Type.KNIGHT) {
            if (!isPathClearWithActiveMoves(from, to, piece.getColor())) return false;
        }

        return true;
    }

    // Pawn move-shape validation only - single step, double step from the
    // pawn's own starting row, and diagonal capture. This method never
    // inspects or triggers promotion; it only answers "is this move shape
    // legal", exactly like every other piece's validation path above.
    private boolean isValidPawnMove(Position from, Position to, Piece pawn) {
        int deltaRow = to.getRow() - from.getRow();
        int deltaCol = to.getCol() - from.getCol();
        int absRow = Math.abs(deltaRow);
        int absCol = Math.abs(deltaCol);

        int direction = (pawn.getColor() == Piece.Color.WHITE) ? -1 : 1;
        // The pawn's starting row is one row in from its own back edge - row
        // (height-2) for white, row 1 for black - never the literal edge row
        // itself. The edge row is the opposing side's promotion square, so a
        // pawn sitting there is not on its own starting rank and must not be
        // allowed a double-step from it.
        int startingRow = (pawn.getColor() == Piece.Color.WHITE) ? (board.getHeight() - 2) : 1;
        Piece targetPiece = board.getPiece(to);

        if (activeMoveQuery.isSquareOccupiedByActiveMove(to, pawn.getColor())) {
            return false;
        }

        if (targetPiece != null && targetPiece.getColor() == pawn.getColor()) {
            return false;
        }

        if (deltaCol == 0) {
            // Single step forward is always legal (regardless of starting row),
            // as long as the destination is empty. This check never looks at
            // startingRow, so it can never be mistaken for a promotion check.
            if (deltaRow != direction && deltaRow != 2 * direction) {
                return false;
            }

            if (absRow == 1) {
                return targetPiece == null;
            }

            if (absRow == 2 && from.getRow() == startingRow) {
                Position middlePos = new Position(from.getRow() + direction, from.getCol());
                if (board.getPiece(middlePos) != null || activeMoveQuery.isSquareOccupiedByActiveMove(middlePos, pawn.getColor())) {
                    return false;
                }
                return targetPiece == null;
            }
            return false;
        }

        // Capture: exactly one square diagonally FORWARD only - deltaRow must
        // equal direction exactly (never 0, which would be a sideways
        // "capture" onto the same row, and never -direction, which would be
        // a backward capture) and absCol must be exactly 1 (never 0, already
        // handled above, and never >1).
        if (deltaRow == direction && absCol == 1) {
            return targetPiece != null && targetPiece.getColor() != pawn.getColor();
        }

        return false;
    }
}

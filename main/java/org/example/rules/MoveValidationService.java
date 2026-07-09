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

    private boolean isValidPawnMove(Position from, Position to, Piece pawn) {
        int deltaRow = to.getRow() - from.getRow();
        int deltaCol = to.getCol() - from.getCol();
        int absRow = Math.abs(deltaRow);
        int absCol = Math.abs(deltaCol);

        int direction = (pawn.getColor() == Piece.Color.WHITE) ? -1 : 1;
        int startingRow = (pawn.getColor() == Piece.Color.WHITE) ? (board.getHeight() - 2) : 1;
        Piece targetPiece = board.getPiece(to);

        if (activeMoveQuery.isSquareOccupiedByActiveMove(to, pawn.getColor())) {
            return false;
        }

        if (targetPiece != null && targetPiece.getColor() == pawn.getColor()) {
            return false;
        }

        if (deltaCol == 0) {
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

        if (absRow <= 1 && absCol <= 1 && !(absRow == 0 && absCol == 0)) {
            return targetPiece != null && targetPiece.getColor() != pawn.getColor();
        }

        return false;
    }
}

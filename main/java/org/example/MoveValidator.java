package org.example;

public class MoveValidator {
    private final Board board;
    private final MovementEngine movementEngine;

    public MoveValidator(Board board, MovementEngine movementEngine) {
        this.board = board;
        this.movementEngine = movementEngine;
    }

    public int calculateDistance(Position from, Position to) {
        int deltaRow = Math.abs(to.getRow() - from.getRow());
        int deltaCol = Math.abs(to.getCol() - from.getCol());
        return Math.max(deltaRow, deltaCol);
    }

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
            if (movementEngine.isSquareOccupiedByActiveMove(currentPos, pieceColor)) return false;

            currentRow += stepRow;
            currentCol += stepCol;
        }
        return true;
    }

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
        if (movementEngine.isSquareOccupiedByActiveMove(to, piece.getColor())) return false;

        if (piece.getType() != Piece.Type.KNIGHT) {
            if (!isPathClearWithActiveMoves(from, to, piece.getColor())) return false;
        }

        return true;
    }

    private boolean isValidPawnMove(Position from, Position to, Piece pawn) {
        int deltaRow = to.getRow() - from.getRow();
        int deltaCol = to.getCol() - from.getCol();
        int absCol = Math.abs(deltaCol);

        int direction = (pawn.getColor() == Piece.Color.WHITE) ? -1 : 1;
        // White pawns start at row (height - 2), Black pawns start at row 1
        int startingRow = (pawn.getColor() == Piece.Color.WHITE) ? (board.getHeight() - 2) : 1;
        Piece targetPiece = board.getPiece(to);

        if (deltaCol == 0) {
            if (targetPiece != null || movementEngine.isSquareOccupiedByActiveMove(to, pawn.getColor())) {
                return false;
            }

            if (deltaRow == direction) {
                return true;
            }

            if (deltaRow == 2 * direction && from.getRow() == startingRow) {
                Position middlePos = new Position(from.getRow() + direction, from.getCol());
                if (board.getPiece(middlePos) != null || movementEngine.isSquareOccupiedByActiveMove(middlePos, pawn.getColor())) {
                    return false;
                }
                return true;
            }
            return false;
        }

        if (absCol == 1 && deltaRow == direction) {
            return targetPiece != null && targetPiece.getColor() != pawn.getColor();
        }

        return false;
    }
}
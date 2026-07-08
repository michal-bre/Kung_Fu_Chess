package org.example;

public class InteractionHandler {
    private final Board board;
    private final MovementEngine movementEngine;
    private final MoveValidator moveValidator;
    private Position selectedPosition;

    public InteractionHandler(Board board, MovementEngine movementEngine, MoveValidator moveValidator) {
        this.board = board;
        this.movementEngine = movementEngine;
        this.moveValidator = moveValidator;
        this.selectedPosition = null;
    }

    public void handleClick(int x, int y) {
        if (movementEngine.isGameOver()) return;

        int row = y / Board.CELL_SIZE;
        int col = x / Board.CELL_SIZE;
        Position clickedPos = new Position(row, col);

        if (!board.isWithinBounds(clickedPos)) return;

        Piece clickedPiece = board.getPiece(clickedPos);

        if (selectedPosition == null) {
            if (clickedPiece != null && !movementEngine.isPieceMovingFrom(clickedPos)) {
                selectedPosition = clickedPos;
            }
            return;
        }

        Piece selectedPiece = board.getPiece(selectedPosition);

        if (clickedPiece != null && clickedPiece.getColor() == selectedPiece.getColor()) {
            if (!movementEngine.isPieceMovingFrom(clickedPos)) {
                selectedPosition = clickedPos;
            }
        } else {
            Piece.Color opponentColor = (selectedPiece.getColor() == Piece.Color.WHITE) ? Piece.Color.BLACK : Piece.Color.WHITE;

            if (!movementEngine.isColorMoving(opponentColor) && !movementEngine.isPieceMovingTo(clickedPos) && moveValidator.isValidMove(selectedPosition, clickedPos, selectedPiece)) {
                int distance = moveValidator.calculateDistance(selectedPosition, clickedPos);
                long totalTravelTime = distance * MovementEngine.MOVE_DURATION_PER_SQUARE;
                long arrivalTime = movementEngine.getGameTimeMillis() + totalTravelTime;

                movementEngine.addMove(new ActiveMove(selectedPosition, clickedPos, selectedPiece, arrivalTime, false));
            }
            selectedPosition = null;
        }
    }

    public void handleJump(int x, int y) {
        if (movementEngine.isGameOver()) return;

        int row = y / Board.CELL_SIZE;
        int col = x / Board.CELL_SIZE;
        Position pos = new Position(row, col);

        if (!board.isWithinBounds(pos)) return;

        Piece piece = board.getPiece(pos);
        if (piece == null) return;

        if (movementEngine.isPieceMovingFrom(pos)) return;

        ActiveMove threateningEnemyMove = null;
        for (ActiveMove move : movementEngine.getActiveMoves()) {
            if (move.getTo().equals(pos) && move.getPiece().getColor() != piece.getColor()) {
                long moveStartTime = move.getArrivalTimeMillis() - (moveValidator.calculateDistance(move.getFrom(), move.getTo()) * MovementEngine.MOVE_DURATION_PER_SQUARE);
                if (movementEngine.getGameTimeMillis() > moveStartTime) {
                    threateningEnemyMove = move;
                    break;
                }
            }
        }

        if (threateningEnemyMove != null) {
            Piece targetPiece = board.getPiece(threateningEnemyMove.getTo());
            if (targetPiece != null && targetPiece.getType() == Piece.Type.KING) {
                movementEngine.setGameOver(true);
            }

            board.movePiece(threateningEnemyMove.getFrom(), threateningEnemyMove.getTo());
            movementEngine.handlePawnPromotion(threateningEnemyMove);
            movementEngine.removeMove(threateningEnemyMove);
            selectedPosition = null;
            return;
        }

        long arrivalTime = movementEngine.getGameTimeMillis() + MovementEngine.JUMP_DURATION;
        ActiveMove jump = new ActiveMove(pos, pos, piece, arrivalTime, true);
        movementEngine.addMove(jump);
        selectedPosition = null;
    }
}

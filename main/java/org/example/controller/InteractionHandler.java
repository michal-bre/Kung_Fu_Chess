package org.example.controller;

import org.example.engine.ActiveMove;
import org.example.engine.EnginePort;
import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;
import org.example.rules.MoveValidationPort;

/**
 * Controller layer: translates raw pixel clicks/jumps into calls against the
 * engine and rules layers.
 *
 * All three collaborators are supplied via constructor injection - this
 * class never instantiates its own dependencies. It depends on the engine
 * and rules ABSTRACTIONS (EnginePort, MoveValidationPort), plus the model's
 * Board directly (Board is a pure entity, visible to every layer, so no port
 * is needed just to read/write it).
 */
public class InteractionHandler {
    private final Board board;
    private final EnginePort engine;
    private final MoveValidationPort moveValidationService;
    private Position selectedPosition;

    public InteractionHandler(Board board, EnginePort engine, MoveValidationPort moveValidationService) {
        this.board = board;
        this.engine = engine;
        this.moveValidationService = moveValidationService;
        this.selectedPosition = null;
    }

    public void handleClick(int x, int y) {
        if (engine.isGameOver()) return;

        int row = y / Board.CELL_SIZE;
        int col = x / Board.CELL_SIZE;
        Position clickedPos = new Position(row, col);

        if (!board.isWithinBounds(clickedPos)) return;

        Piece clickedPiece = board.getPiece(clickedPos);

        if (selectedPosition == null) {
            if (clickedPiece != null && !engine.isPieceMovingFrom(clickedPos)) {
                selectedPosition = clickedPos;
            }
            return;
        }

        Piece selectedPiece = board.getPiece(selectedPosition);

        if (clickedPiece != null && clickedPiece.getColor() == selectedPiece.getColor()) {
            if (!engine.isPieceMovingFrom(clickedPos)) {
                selectedPosition = clickedPos;
            }
        } else {
            Piece.Color opponentColor = (selectedPiece.getColor() == Piece.Color.WHITE) ? Piece.Color.BLACK : Piece.Color.WHITE;

            // A new move cannot be created while the opponent already has a move in
            // flight (jumps are exempt - isColorMoving ignores them): only one side may
            // be actively moving at a time. This also blocks the target square if it's
            // already reserved by an active move of the SAME color.
            if (!engine.isColorMoving(opponentColor) &&
                !engine.isSquareOccupiedByActiveMove(clickedPos, selectedPiece.getColor()) &&
                moveValidationService.isValidMove(selectedPosition, clickedPos, selectedPiece)) {
                int distance = moveValidationService.calculateDistance(selectedPosition, clickedPos);
                long totalTravelTime = distance * EnginePort.MOVE_DURATION_PER_SQUARE;
                long arrivalTime = engine.getGameTimeMillis() + totalTravelTime;

                engine.addMove(new ActiveMove(selectedPosition, clickedPos, selectedPiece, arrivalTime, false));
            }
            selectedPosition = null;
        }
    }

    public void handleJump(int x, int y) {
        if (engine.isGameOver()) return;

        int row = y / Board.CELL_SIZE;
        int col = x / Board.CELL_SIZE;
        Position pos = new Position(row, col);

        if (!board.isWithinBounds(pos)) return;

        Piece piece = board.getPiece(pos);
        if (piece == null) return;

        // Prevent pieces that just completed a move from jumping
        if (engine.isPieceJustCompleted(pos)) return;

        if (engine.isPieceMovingFrom(pos)) return;

        ActiveMove threateningEnemyMove = null;
        for (ActiveMove move : engine.getActiveMoves()) {
            if (move.getTo().equals(pos) && move.getPiece().getColor() != piece.getColor()) {
                long moveStartTime = move.getArrivalTimeMillis() - (moveValidationService.calculateDistance(move.getFrom(), move.getTo()) * EnginePort.MOVE_DURATION_PER_SQUARE);
                if (engine.getGameTimeMillis() > moveStartTime) {
                    threateningEnemyMove = move;
                    break;
                }
            }
        }

        if (threateningEnemyMove != null) {
            Piece targetPiece = board.getPiece(threateningEnemyMove.getTo());
            if (targetPiece != null && targetPiece.getType() == Piece.Type.KING) {
                engine.setGameOver(true);
            }

            board.movePiece(threateningEnemyMove.getFrom(), threateningEnemyMove.getTo());

            engine.removeMove(threateningEnemyMove);
            selectedPosition = null;
            return;
        }

        long arrivalTime = engine.getGameTimeMillis() + EnginePort.JUMP_DURATION;
        ActiveMove jump = new ActiveMove(pos, pos, piece, arrivalTime, true);
        engine.addMove(jump);
        selectedPosition = null;
    }
}

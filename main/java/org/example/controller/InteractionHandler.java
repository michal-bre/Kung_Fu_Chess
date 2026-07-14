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
    // The exact Piece object present at selectedPosition at the moment it was
    // selected. Piece has no value-based equals()/hashCode(), so this is a
    // reference-identity token: it lets the second click verify that the
    // piece the player originally picked is still physically the one
    // occupying that square, instead of trusting whatever piece happens to
    // be there now. Board.movePiece/setPiece always swap in a new Piece
    // reference on capture/replace - they never mutate a Piece in place -
    // so a reference mismatch reliably means "the original piece is gone."
    private Piece selectedPiece;

    // Transient feedback for the view layer only: which square (if any) most
    // recently refused a move attempt, and when. A rejected move is a silent
    // no-op as far as the engine/model are concerned (nothing changes), which
    // is indistinguishable from "did my click even register?" without this -
    // BoardView reads it to flash the square briefly instead of leaving the
    // player guessing whether the click landed. Purely observational: nothing
    // in this class's own control flow depends on these fields.
    private Position lastRejectedPosition;
    private long lastRejectedAtMillis = Long.MIN_VALUE;

    public InteractionHandler(Board board, EnginePort engine, MoveValidationPort moveValidationService) {
        this.board = board;
        this.engine = engine;
        this.moveValidationService = moveValidationService;
        this.selectedPosition = null;
        this.selectedPiece = null;
    }

    public Position getLastRejectedPosition() {
        return lastRejectedPosition;
    }

    public long getLastRejectedAtMillis() {
        return lastRejectedAtMillis;
    }

    public void handleClick(int x, int y) {
        if (engine.isGameOver()) return;

        int row = y / Board.CELL_SIZE;
        int col = x / Board.CELL_SIZE;
        Position clickedPos = new Position(row, col);

        if (!board.isWithinBounds(clickedPos)) return;

        Piece clickedPiece = board.getPiece(clickedPos);

        if (selectedPosition == null) {
            if (clickedPiece != null && !engine.isPieceMovingFrom(clickedPos) && !engine.isPieceResting(clickedPos)) {
                selectedPosition = clickedPos;
                selectedPiece = clickedPiece;
            } else if (clickedPiece != null) {
                // A real piece was clicked but couldn't be selected (mid-flight
                // or still resting after its last action) - flag it the same
                // way a rejected move attempt is, so the player sees why
                // nothing happened instead of wondering if the click landed.
                lastRejectedPosition = clickedPos;
                lastRejectedAtMillis = engine.getGameTimeMillis();
            }
            return;
        }

        // Stale-selection guard: re-fetch whatever is at selectedPosition right
        // now and compare it by reference against the piece that was actually
        // selected. Time has passed since the first click (the engine may have
        // ticked and resolved captures in between), so selectedPosition alone
        // is not trustworthy - the piece it pointed to may have been captured
        // and replaced by an opponent's piece on the same square. Acting on the
        // coordinate without this check would silently execute the move using
        // the WRONG piece (e.g. the opponent's), rather than the one the player
        // actually clicked.
        Piece currentOccupant = board.getPiece(selectedPosition);
        if (currentOccupant != selectedPiece) {
            selectedPosition = null;
            selectedPiece = null;
            // The player's second click is still a meaningful action (e.g. they
            // clicked Piece B) - reprocess it as a fresh first click instead of
            // silently dropping it, so they don't have to click twice.
            handleClick(x, y);
            return;
        }

        if (clickedPiece != null && clickedPiece.getColor() == selectedPiece.getColor()) {
            if (!engine.isPieceMovingFrom(clickedPos) && !engine.isPieceResting(clickedPos)) {
                selectedPosition = clickedPos;
                selectedPiece = clickedPiece;
            } else {
                lastRejectedPosition = clickedPos;
                lastRejectedAtMillis = engine.getGameTimeMillis();
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
            } else {
                lastRejectedPosition = clickedPos;
                lastRejectedAtMillis = engine.getGameTimeMillis();
            }
            selectedPosition = null;
            selectedPiece = null;
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
            // This is a MOVE being forced to complete early by a jump threat,
            // not a jump itself - the piece being moved never called
            // handleJump, so it gets the same move -> long_rest duration a
            // normally-arriving move gets in MovementEngine.
            // resolveSimultaneousArrivals, not the shorter jump -> short_rest.
            engine.markResting(threateningEnemyMove.getTo(), EnginePort.REST_AFTER_MOVE_MS);

            engine.removeMove(threateningEnemyMove);
            selectedPosition = null;
            selectedPiece = null;
            return;
        }

        long arrivalTime = engine.getGameTimeMillis() + EnginePort.JUMP_DURATION;
        ActiveMove jump = new ActiveMove(pos, pos, piece, arrivalTime, true);
        engine.addMove(jump);
        selectedPosition = null;
        selectedPiece = null;
    }
}

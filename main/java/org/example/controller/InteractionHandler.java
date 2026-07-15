package org.example.controller;

import org.example.engine.ActiveMove;
import org.example.engine.EnginePort;
import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;
import org.example.rules.MoveValidationPort;
import org.example.rules.PieceScore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    // How long after an enemy's attacking move starts a defending jump can
    // still succeed. A jump created within this window of the attack's
    // start defeats it (see the air-capture check triggered by
    // engine.addMove in handleJump below); created any later, the jump
    // comes too late to plausibly be a real dodge, and the attacker's move
    // is instead forced to complete immediately, capturing the piece that
    // tried to jump. Chosen to comfortably cover a human's reaction time to
    // seeing an attack begin, while still meaningfully punishing a jump
    // that's reactive only in name.
    private static final long JUMP_DEFENSE_WINDOW_MS = 800;

    // Every move successfully created via handleClick, in the order they
    // were made - see MoveHistoryEntry and getMoveHistory. Jumps aren't
    // logged here; the table this feeds is meant to read like a standard
    // move log, and a jump has no chess-notation equivalent.
    private final List<MoveHistoryEntry> moveHistory = new ArrayList<>();

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

    public List<MoveHistoryEntry> getMoveHistory() {
        return Collections.unmodifiableList(moveHistory);
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
            // Both colors may have moves in flight at the same time - that's the
            // whole premise of a real-time ("kung fu") chess variant, as opposed
            // to a turn-based one. The only thing this blocks is the target
            // square already being reserved by an active move of the SAME
            // color - two of your own pieces can't be sent to the same square.
            if (!engine.isSquareOccupiedByActiveMove(clickedPos, selectedPiece.getColor()) &&
                moveValidationService.isValidMove(selectedPosition, clickedPos, selectedPiece)) {
                int distance = moveValidationService.calculateDistance(selectedPosition, clickedPos);
                long totalTravelTime = distance * EnginePort.MOVE_DURATION_PER_SQUARE;
                long arrivalTime = engine.getGameTimeMillis() + totalTravelTime;

                // clickedPiece != null here means an opponent piece currently
                // stands on the destination - i.e. this move is a capture.
                // Recorded from what's true right now, at click time, same as
                // the rest of this method's validation - not re-derived later
                // from whatever the board happens to look like on arrival.
                moveHistory.add(new MoveHistoryEntry(
                        selectedPiece.getColor(),
                        engine.getGameTimeMillis(),
                        formatNotation(selectedPiece, selectedPosition, clickedPos, clickedPiece != null)));

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

        // If an enemy move targeting this square has been in flight for
        // longer than JUMP_DEFENSE_WINDOW_MS, the jump comes too late: the
        // attacker's move is forced to complete immediately instead of
        // being defended against, capturing the piece that tried to jump.
        ActiveMove lateEnemyMove = null;
        for (ActiveMove move : engine.getActiveMoves()) {
            if (!move.isJump() && move.getTo().equals(pos) && move.getPiece().getColor() != piece.getColor()) {
                long distance = moveValidationService.calculateDistance(move.getFrom(), move.getTo());
                long moveStartTime = move.getArrivalTimeMillis() - (distance * EnginePort.MOVE_DURATION_PER_SQUARE);
                long elapsedSinceStart = engine.getGameTimeMillis() - moveStartTime;
                if (elapsedSinceStart > JUMP_DEFENSE_WINDOW_MS) {
                    lateEnemyMove = move;
                    break;
                }
            }
        }

        if (lateEnemyMove != null) {
            Piece targetPiece = board.getPiece(lateEnemyMove.getTo());
            if (targetPiece != null) {
                if (targetPiece.getType() == Piece.Type.KING) {
                    engine.setGameOver(true);
                }
                // This capture happens here directly (board.movePiece below),
                // not through any of MovementEngine's own capture-resolution
                // paths, so it has to report the score credit back in itself.
                engine.addScore(lateEnemyMove.getPiece().getColor(), PieceScore.valueOf(targetPiece.getType()));
            }

            board.movePiece(lateEnemyMove.getFrom(), lateEnemyMove.getTo());
            // This is a MOVE being forced to complete early because the
            // defending jump came too late, not a jump itself - the piece
            // being moved never called handleJump, so it gets the same
            // move -> long_rest duration a normally-arriving move gets in
            // MovementEngine.resolveSimultaneousArrivals, not the shorter
            // jump -> short_rest.
            engine.markResting(lateEnemyMove.getTo(), EnginePort.REST_AFTER_MOVE_MS);

            engine.removeMove(lateEnemyMove);
            selectedPosition = null;
            selectedPiece = null;
            return;
        }

        // Within the reaction window (or against no threat at all): jumping
        // here defends the square normally. engine.addMove below runs
        // MovementEngine.triggerAirCaptures, which checks every active jump
        // against every active move for a same-square, different-color
        // collision and removes the loser - that's where an incoming
        // attacker actually gets captured.
        long arrivalTime = engine.getGameTimeMillis() + EnginePort.JUMP_DURATION;
        ActiveMove jump = new ActiveMove(pos, pos, piece, arrivalTime, true);
        engine.addMove(jump);
        selectedPosition = null;
        selectedPiece = null;
    }

    /**
     * A simplified algebraic-style move description - see MoveHistoryEntry
     * for exactly what's (and isn't) covered.
     */
    private String formatNotation(Piece piece, Position from, Position to, boolean isCapture) {
        String destination = squareName(to);
        if (piece.getType() == Piece.Type.PAWN) {
            return isCapture ? (fileLetter(from.getCol()) + "x" + destination) : destination;
        }
        return piece.getType().getSymbol() + (isCapture ? "x" : "") + destination;
    }

    private String squareName(Position pos) {
        return "" + fileLetter(pos.getCol()) + rankNumber(pos.getRow());
    }

    private static char fileLetter(int col) {
        return (char) ('a' + col);
    }

    private int rankNumber(int row) {
        // Row 0 is the top of the board (the initial black back rank, i.e.
        // chess rank 8 - see GuiMain's starting position), so rank counts
        // down as row counts up.
        return board.getHeight() - row;
    }
}

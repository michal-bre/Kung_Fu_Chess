package org.example.controller;

import org.example.engine.GameEngine;
import org.example.engine.JumpResult;
import org.example.engine.MoveResult;
import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Controller layer: translates raw pixel clicks/jumps into calls against
 * GameEngine - and does NOTHING else. Per the architecture review's items 4,
 * 5 and 6: this class no longer duplicates pixel-to-square math (that's
 * BoardMapper's job), no longer performs rule validation, distance/duration
 * math, or direct Board mutation (that's GameEngine's job) - it only tracks
 * which square is currently selected and turns the result of a click into
 * exactly one GameEngine call.
 *
 * Still depends on Board directly, but ONLY for read-only board-dimension
 * queries used to format move notation (rankNumber) - never for getPiece,
 * setPiece, or movePiece. Per ArchitectureDoc, Board is a pure entity
 * visible to every layer, so this narrow a dependency is not a layering
 * violation; it is deliberately never widened back into a general-purpose
 * Board reference.
 */
public class InteractionHandler {
    private final Board board;
    private final GameEngine gameEngine;
    private final BoardMapper boardMapper;
    private Position selectedPosition;
    // The exact Piece object present at selectedPosition at the moment it was
    // selected. Piece has no value-based equals()/hashCode(), so this is a
    // reference-identity token: it lets the second click verify that the
    // piece the player originally picked is still physically the one
    // occupying that square, instead of trusting whatever piece happens to
    // be there now. GameEngine always swaps in a new Piece reference on
    // capture/replace - it never mutates a Piece in place - so a reference
    // mismatch reliably means "the original piece is gone."
    private Piece selectedPiece;

    // Transient feedback for the view layer only: which square (if any) most
    // recently refused a move attempt, and when. A rejected move is a silent
    // no-op as far as the engine/model are concerned (nothing changes), which
    // is indistinguishable from "did my click even register?" without this -
    // the renderer reads it (via GameSnapshot) to flash the square briefly
    // instead of leaving the player guessing whether the click landed.
    // Purely observational: nothing in this class's own control flow depends
    // on these fields.
    private Position lastRejectedPosition;
    private long lastRejectedAtMillis = Long.MIN_VALUE;

    // Every move successfully created via handleClick, in the order they
    // were made - see MoveHistoryEntry and getMoveHistory. Jumps aren't
    // logged here; the table this feeds is meant to read like a standard
    // move log, and a jump has no chess-notation equivalent.
    private final List<MoveHistoryEntry> moveHistory = new ArrayList<>();

    public InteractionHandler(Board board, GameEngine gameEngine, BoardMapper boardMapper) {
        this.board = board;
        this.gameEngine = gameEngine;
        this.boardMapper = boardMapper;
        this.selectedPosition = null;
        this.selectedPiece = null;
    }

    public Position getLastRejectedPosition() {
        return lastRejectedPosition;
    }

    public long getLastRejectedAtMillis() {
        return lastRejectedAtMillis;
    }

    /** Which square (if any) the player currently has selected - see GameSnapshot/selection highlight. */
    public Position getSelectedPosition() {
        return selectedPosition;
    }

    public List<MoveHistoryEntry> getMoveHistory() {
        return Collections.unmodifiableList(moveHistory);
    }

    public void handleClick(int x, int y) {
        if (gameEngine.isGameOver()) return;

        Optional<Position> mapped = boardMapper.toPosition(x, y);
        if (!mapped.isPresent()) return;
        Position clickedPos = mapped.get();

        Piece clickedPiece = gameEngine.pieceAt(clickedPos).orElse(null);

        if (selectedPosition == null) {
            if (clickedPiece != null && gameEngine.canSelect(clickedPos)) {
                selectedPosition = clickedPos;
                selectedPiece = clickedPiece;
            } else if (clickedPiece != null) {
                // A real piece was clicked but couldn't be selected (mid-flight
                // or still resting after its last action) - flag it the same
                // way a rejected move attempt is, so the player sees why
                // nothing happened instead of wondering if the click landed.
                lastRejectedPosition = clickedPos;
                lastRejectedAtMillis = gameEngine.getGameTimeMillis();
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
        Piece currentOccupant = gameEngine.pieceAt(selectedPosition).orElse(null);
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
            if (gameEngine.canSelect(clickedPos)) {
                selectedPosition = clickedPos;
                selectedPiece = clickedPiece;
            } else {
                lastRejectedPosition = clickedPos;
                lastRejectedAtMillis = gameEngine.getGameTimeMillis();
            }
        } else {
            // clickedPiece != null here means an opponent piece currently
            // stands on the destination - i.e. this move is a capture.
            // Recorded from what's true right now, at click time - not
            // re-derived later from whatever the board happens to look like
            // on arrival.
            boolean isCapture = clickedPiece != null;
            Piece movingPiece = selectedPiece;
            Position from = selectedPosition;

            MoveResult result = gameEngine.requestMove(selectedPosition, clickedPos);
            if (result.isAccepted()) {
                moveHistory.add(new MoveHistoryEntry(
                        movingPiece.getColor(),
                        gameEngine.getGameTimeMillis(),
                        formatNotation(movingPiece, from, clickedPos, isCapture)));
            } else {
                lastRejectedPosition = clickedPos;
                lastRejectedAtMillis = gameEngine.getGameTimeMillis();
            }
            selectedPosition = null;
            selectedPiece = null;
        }
    }

    public void handleJump(int x, int y) {
        if (gameEngine.isGameOver()) return;

        Optional<Position> mapped = boardMapper.toPosition(x, y);
        if (!mapped.isPresent()) return;
        Position pos = mapped.get();

        JumpResult result = gameEngine.requestJump(pos);

        // Selection is only cleared when the jump actually DID something -
        // either it succeeded, or it came too late and the enemy's attack
        // was forced to complete instead. Every other rejection reason (no
        // piece there, piece resting/moving, game over) leaves whatever the
        // player currently has selected via handleClick untouched, since a
        // right-click jump attempt is a separate action from a left-click
        // move-selection.
        if (result.isAccepted() || "too_late".equals(result.getReason())) {
            selectedPosition = null;
            selectedPiece = null;
        }
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

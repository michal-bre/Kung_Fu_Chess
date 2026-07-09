package org.example.engine;

import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;
import org.example.rules.ActiveMoveQuery;
import org.example.rules.AirCaptureService;
import org.example.rules.PawnPromotionService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine layer: real-time state management and game dynamics.
 *
 * Manages the passage of time (gameTimeMillis) and the set of moves
 * currently in flight, and orchestrates the state changes that result
 * (applying moves to the board, triggering captures, delegating to rules
 * services for legality/promotion decisions). Depends on model (Board)
 * directly - Board is a pure entity, so no port is needed for it - and on
 * the rules layer's services and its own EnginePort/ActiveMoveQuery ports.
 */
public class MovementEngine implements EnginePort, ActiveMoveQuery {
    private final Board board;
    private final PawnPromotionService pawnPromotionService;
    private final AirCaptureService airCaptureService;
    private final List<ActiveMove> activeMoves;
    private final List<ActiveMove> recentlyCompletedMoves;
    private long gameTimeMillis;
    private boolean isGameOver;

    // MOVE_DURATION_PER_SQUARE / JUMP_DURATION are inherited from EnginePort.

    public MovementEngine(Board board) {
        this.board = board;
        this.pawnPromotionService = new PawnPromotionService(board);
        this.airCaptureService = new AirCaptureService();
        this.activeMoves = new ArrayList<>();
        this.recentlyCompletedMoves = new ArrayList<>();
        this.gameTimeMillis = 0;
        this.isGameOver = false;
    }

    @Override
    public List<ActiveMove> getActiveMoves() {
        return activeMoves;
    }

    @Override
    public long getGameTimeMillis() {
        return gameTimeMillis;
    }

    @Override
    public boolean isGameOver() {
        return isGameOver;
    }

    @Override
    public void setGameOver(boolean gameOver) {
        this.isGameOver = gameOver;
    }

    @Override
    public void addMove(ActiveMove move) {
        activeMoves.add(move);
        triggerAirCaptures();
    }

    @Override
    public void removeMove(ActiveMove move) {
        activeMoves.remove(move);
    }

    @Override
    public boolean isPieceMovingFrom(Position pos) {
        for (ActiveMove move : activeMoves) {
            if (move.getFrom().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isPieceMovingTo(Position pos) {
        for (ActiveMove move : activeMoves) {
            if (move.getTo().equals(pos) && !move.isJump()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isColorMoving(Piece.Color color) {
        for (ActiveMove move : activeMoves) {
            if (move.getPiece().getColor() == color && !move.isJump()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isSquareOccupiedByActiveMove(Position pos, Piece.Color movingColor) {
        for (ActiveMove move : activeMoves) {
            if (move.getTo().equals(pos) && move.getPiece().getColor() == movingColor && !move.isJump()) {
                return true;
            }
        }
        return false;
    }

    public void triggerAirCaptures() {
        // Find all active jumps
        List<ActiveMove> activeJumps = new ArrayList<>();
        for (ActiveMove move : activeMoves) {
            if (move.isJump()) {
                activeJumps.add(move);
            }
        }

        if (activeJumps.isEmpty()) return;

        List<ActiveMove> toRemove = new ArrayList<>();

        // For each active jump, check if any enemy moves target the same cell
        for (ActiveMove activeJump : activeJumps) {
            for (ActiveMove move : activeMoves) {
                if (!move.isJump() && airCaptureService.isCapturedByJump(
                        move.getTo(), move.getPiece().getColor(),
                        activeJump.getTo(), activeJump.getPiece().getColor())) {
                    // An enemy piece is moving to the same cell as the jumping piece
                    // Remove the enemy piece from the board at its starting position
                    board.setPiece(move.getFrom().getRow(), move.getFrom().getCol(), null);
                    toRemove.add(move);
                }
            }
        }

        activeMoves.removeAll(toRemove);
    }

    @Override
    public void advanceTime(long millis) {
        if (millis <= 0) return;

        this.gameTimeMillis += millis;
        recentlyCompletedMoves.clear();

        List<ActiveMove> completedMoves = new ArrayList<>();
        List<ActiveMove> completedJumps = new ArrayList<>();
        List<ActiveMove> toRemove = new ArrayList<>();

        // Find all completed moves
        for (ActiveMove move : activeMoves) {
            if (move.isComplete(this.gameTimeMillis)) {
                if (move.isJump()) {
                    completedJumps.add(move);
                } else {
                    completedMoves.add(move);
                }
                toRemove.add(move);
            }
        }

        // Remove completed moves from active list
        activeMoves.removeAll(toRemove);

        // Check for air captures with moves still in transit
        for (ActiveMove completedJump : completedJumps) {
            List<ActiveMove> capturedInTransit = new ArrayList<>();
            for (ActiveMove activeMove : activeMoves) {
                if (!activeMove.isJump() && airCaptureService.isCapturedByJump(
                        activeMove.getTo(), activeMove.getPiece().getColor(),
                        completedJump.getTo(), completedJump.getPiece().getColor())) {
                    // This enemy piece will be captured
                    board.setPiece(activeMove.getFrom().getRow(), activeMove.getFrom().getCol(), null);
                    capturedInTransit.add(activeMove);
                }
            }
            activeMoves.removeAll(capturedInTransit);
        }

        // A move that was captured in-air by a simultaneous completed jump never lands
        // anywhere - drop it from the batch before resolving destinations.
        List<ActiveMove> survivingMoves = new ArrayList<>();
        for (ActiveMove normalMove : completedMoves) {
            boolean capturedInAir = false;

            for (ActiveMove jumpMove : completedJumps) {
                if (airCaptureService.isCapturedByJump(
                        normalMove.getTo(), normalMove.getPiece().getColor(),
                        jumpMove.getTo(), jumpMove.getPiece().getColor())) {
                    capturedInAir = true;
                    break;
                }
            }

            if (!capturedInAir) {
                survivingMoves.add(normalMove);
            }
        }

        resolveSimultaneousArrivals(survivingMoves);
    }

    /**
     * Applies every move that completes on this tick.
     *
     * Two or more moves can legitimately arrive at the same time: either at
     * different destinations (the common case) or racing for the SAME
     * destination (two different-colored pieces both targeting one square).
     * Resolving them one at a time - reading the board, mutating it, then
     * reading it again for the next move - is what corrupted state before:
     * one move's write bled into the next move's read, and a captured piece
     * was removed from the board without the capturing piece ever actually
     * being placed there.
     *
     * The fix uses a strict two-phase order instead:
     *   1. Clear every arriving move's source square first, using the piece
     *      reference each ActiveMove already carries (never re-reading the
     *      board), so nothing read in phase 2 is contaminated by a
     *      still-in-progress mutation.
     *   2. Group the survivors by destination and resolve each destination
     *      exactly once: the move that was added first (earliest in
     *      activeMoves/insertion order) wins the square; every other
     *      contender for that square, or whatever already stood on it, is
     *      captured according to the rules layer's capture predicate.
     */
    private void resolveSimultaneousArrivals(List<ActiveMove> survivingMoves) {
        if (survivingMoves.isEmpty()) return;

        // Phase 1: clear every source square before any destination is written.
        for (ActiveMove move : survivingMoves) {
            board.setPiece(move.getFrom().getRow(), move.getFrom().getCol(), null);
        }

        // Snapshot each contested destination's pre-tick occupant (unaffected by the
        // phase-1 clears, since a move's destination is never also a source this tick)
        // and group movers by destination so a race is resolved as one decision.
        Map<Position, Piece> destinationOccupants = new LinkedHashMap<>();
        Map<Position, List<ActiveMove>> movesByDestination = new LinkedHashMap<>();
        for (ActiveMove move : survivingMoves) {
            Position to = move.getTo();
            destinationOccupants.computeIfAbsent(to, board::getPiece);
            movesByDestination.computeIfAbsent(to, key -> new ArrayList<>()).add(move);
        }

        // Phase 2: resolve each contested destination exactly once.
        for (Map.Entry<Position, List<ActiveMove>> entry : movesByDestination.entrySet()) {
            Position destination = entry.getKey();
            List<ActiveMove> contenders = entry.getValue();
            Piece existingOccupant = destinationOccupants.get(destination);

            // Earliest-added mover wins the square; every other contender loses the
            // race and is captured outright, just like losing a normal capture.
            ActiveMove winner = contenders.get(0);
            for (ActiveMove loser : contenders.subList(1, contenders.size())) {
                if (loser.getPiece().getType() == Piece.Type.KING) {
                    isGameOver = true;
                }
            }

            if (existingOccupant != null) {
                if (!airCaptureService.isCapture(existingOccupant.getColor(), winner.getPiece().getColor())) {
                    // Defensive: a friendly piece already occupies this square. Valid,
                    // validated moves can't normally produce this, so refuse to
                    // overwrite it rather than risk corrupting the board.
                    continue;
                }
                if (existingOccupant.getType() == Piece.Type.KING) {
                    isGameOver = true;
                }
            }

            board.setPiece(destination.getRow(), destination.getCol(), winner.getPiece());
            handlePawnPromotion(winner);
            recentlyCompletedMoves.add(winner);
        }
    }

    @Override
    public boolean isPieceJustCompleted(Position pos) {
        for (ActiveMove move : recentlyCompletedMoves) {
            if (move.getTo().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void handlePawnPromotion(ActiveMove move) {
        pawnPromotionService.handlePawnPromotion(move.getPiece(), move.getTo());
    }
}

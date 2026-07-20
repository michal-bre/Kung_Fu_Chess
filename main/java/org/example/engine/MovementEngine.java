package org.example.engine;

import org.example.bus.EventBus;
import org.example.bus.GameEndedEvent;
import org.example.bus.GameStartedEvent;
import org.example.bus.ScoreUpdatedEvent;
import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;
import org.example.rules.ActiveMoveQuery;
import org.example.rules.AirCaptureService;
import org.example.rules.PawnPromotionService;
import org.example.rules.PieceScore;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine layer: real-time state management and game dynamics.
 *
 * Manages the passage of time (gameTimeMillis) and the set of moves
 * currently in flight, and orchestrates the state changes that result
 * (applying moves to the board, triggering captures). Depends on model
 * (Board) directly - Board is a pure entity, so no port is needed for it -
 * and on the rules layer's AirCaptureService, PawnPromotionService, and its
 * own EnginePort/ActiveMoveQuery ports.
 *
 * Promotion fires exactly once, at the moment a move resolves: whichever
 * piece wins a contested destination square in resolveSimultaneousArrivals is
 * checked immediately after being placed on the board. A pawn is only ever
 * promoted because it just arrived on its color's edge row via a completed
 * move - a pawn that is simply sitting on the edge row (e.g. placed there by
 * the initial board setup and never moved) must stay a pawn, so promotion is
 * deliberately NOT re-checked on every tick or for the whole board.
 */
public class MovementEngine implements EnginePort, ActiveMoveQuery {
    private final Board board;
    private final EventBus bus;
    private final AirCaptureService airCaptureService;
    private final PawnPromotionService pawnPromotionService;
    private final List<ActiveMove> activeMoves;
    private final List<ActiveMove> recentlyCompletedMoves;
    private final Map<Position, Long> restingUntilMillis;
    private final Map<Position, Long> restingDurationMillis;
    private final Map<Piece.Color, Integer> scores;
    private long gameTimeMillis;
    private boolean isGameOver;
    // Named winnerColor (not "winner") to avoid colliding with the several
    // local "ActiveMove winner" variables already used below (e.g. in
    // resolveSimultaneousArrivals) - those name the winning MOVE of a
    // contested square, a different concept from the winning COLOR of the
    // whole game.
    private Piece.Color winnerColor;

    // MOVE_DURATION_PER_SQUARE / JUMP_DURATION / REST_AFTER_MOVE_MS /
    // REST_AFTER_JUMP_MS are inherited from EnginePort.

    /** Local-mode convenience constructor: wires up a private EventBus that nothing outside this instance can observe. Production wiring (GuiMain) uses the two-arg constructor with a shared bus instead. */
    public MovementEngine(Board board) {
        this(board, new EventBus());
    }

    public MovementEngine(Board board, EventBus bus) {
        this.board = board;
        this.bus = bus;
        this.airCaptureService = new AirCaptureService();
        this.pawnPromotionService = new PawnPromotionService(board);
        this.activeMoves = new ArrayList<>();
        this.recentlyCompletedMoves = new ArrayList<>();
        this.restingUntilMillis = new HashMap<>();
        this.restingDurationMillis = new HashMap<>();
        this.scores = new EnumMap<>(Piece.Color.class);
        this.scores.put(Piece.Color.WHITE, 0);
        this.scores.put(Piece.Color.BLACK, 0);
        this.gameTimeMillis = 0;
        this.isGameOver = false;
        // Engine construction and "the game is now playable" are the same
        // moment in this local-mode wiring - see GameStartedEvent's class
        // doc for why a networked server will publish this differently.
        this.bus.publish(new GameStartedEvent());
    }

    @Override
    public int getScore(Piece.Color color) {
        return scores.getOrDefault(color, 0);
    }

    @Override
    public void addScore(Piece.Color color, int points) {
        scores.merge(color, points, Integer::sum);
        bus.publish(new ScoreUpdatedEvent(color, scores.get(color)));
    }

    @Override
    public boolean isPieceResting(Position pos) {
        Long until = restingUntilMillis.get(pos);
        return until != null && gameTimeMillis < until;
    }

    @Override
    public void markResting(Position pos, long durationMillis) {
        // A fresh arrival always overwrites whatever stale entry (if any)
        // was left by a previous, unrelated occupant of this square - the
        // square can only ever hold one piece at a time, so there is never a
        // reason to keep an older timer around once a new one is set here.
        restingUntilMillis.put(pos, gameTimeMillis + durationMillis);
        restingDurationMillis.put(pos, durationMillis);
    }

    @Override
    public long getRestingUntilMillis(Position pos) {
        Long until = restingUntilMillis.get(pos);
        return until != null ? until : -1;
    }

    @Override
    public long getRestingDurationMillis(Position pos) {
        Long duration = restingDurationMillis.get(pos);
        return duration != null ? duration : -1;
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
    public Piece.Color getWinner() {
        return winnerColor;
    }

    @Override
    public void setWinner(Piece.Color winner) {
        this.winnerColor = winner;
        // A game only ever transitions TO having a winner, never back to
        // having none (there's no reset/draw path today - see the class
        // doc's promotion note for the same "fires exactly once" shape) -
        // so publishing only on a non-null winner is exactly "the game just
        // ended", not merely "this field was touched".
        if (winner != null) {
            bus.publish(new GameEndedEvent(winner));
        }
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
                    addScore(activeJump.getPiece().getColor(), PieceScore.valueOf(move.getPiece().getType()));
                    if (move.getPiece().getType() == Piece.Type.KING) {
                        setGameOver(true);
                        setWinner(activeJump.getPiece().getColor());
                    }
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

        // A completed jump never relocates its piece (from == to - see
        // InteractionHandler.handleJump), so unlike a normal move it has no
        // "arrival at a destination" moment inside resolveSimultaneousArrivals
        // to hang a rest period off of. Set it here instead: jump -> short_rest.
        for (ActiveMove completedJump : completedJumps) {
            markResting(completedJump.getTo(), EnginePort.REST_AFTER_JUMP_MS);
        }

        // Deliberately NO check here for "completed jump vs. still-active
        // (not-yet-arrived) enemy moves": a jump's capture power only exists
        // while it is genuinely in flight (see triggerAirCaptures, invoked on
        // every addMove - that's what lets a jump capture an incoming move
        // the instant either one is created while the other is still
        // active/airborne) or at the exact instant it lands in the same tick
        // as an enemy's arrival (the simultaneous-completion check just
        // below). Once a jump has completed and this tick's processing
        // reaches this point, it is just a resting piece standing on a
        // square - an enemy move that is STILL in transit at that moment is
        // not defeated by it; that enemy will simply capture it normally,
        // like any other piece, once its own move naturally arrives.

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
                    addScore(jumpMove.getPiece().getColor(), PieceScore.valueOf(normalMove.getPiece().getType()));
                    if (normalMove.getPiece().getType() == Piece.Type.KING) {
                        setGameOver(true);
                        setWinner(jumpMove.getPiece().getColor());
                    }
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

        // Snapshot the pre-tick occupant of every square this batch touches - both
        // sources AND destinations - before any mutation happens. This matters even
        // though same-tick opposite-color moves can no longer coexist (InteractionHandler
        // blocks starting a new move while the opponent already has one in flight): it
        // guards against the case where one move's destination equals another same-tick
        // move's source, so clearing sources first can never silently erase the piece a
        // different move in this batch was about to capture there.
        Map<Position, Piece> preTickOccupants = new LinkedHashMap<>();
        for (ActiveMove move : survivingMoves) {
            preTickOccupants.computeIfAbsent(move.getFrom(), board::getPiece);
            preTickOccupants.computeIfAbsent(move.getTo(), board::getPiece);
        }

        // Phase 1: clear every source square before any destination is written.
        for (ActiveMove move : survivingMoves) {
            board.setPiece(move.getFrom().getRow(), move.getFrom().getCol(), null);
        }

        // Group movers by destination so a race for the same square is resolved as one
        // decision instead of overwriting each other one at a time.
        Map<Position, List<ActiveMove>> movesByDestination = new LinkedHashMap<>();
        for (ActiveMove move : survivingMoves) {
            movesByDestination.computeIfAbsent(move.getTo(), key -> new ArrayList<>()).add(move);
        }

        // Phase 2: resolve each contested destination exactly once.
        for (Map.Entry<Position, List<ActiveMove>> entry : movesByDestination.entrySet()) {
            Position destination = entry.getKey();
            List<ActiveMove> contenders = entry.getValue();
            Piece existingOccupant = preTickOccupants.get(destination);

            // Earliest-added mover wins the square; every other contender loses the
            // race and is captured outright, just like losing a normal capture.
            ActiveMove winner = contenders.get(0);
            for (ActiveMove loser : contenders.subList(1, contenders.size())) {
                if (loser.getPiece().getType() == Piece.Type.KING) {
                    setGameOver(true);
                    setWinner(winner.getPiece().getColor());
                }
                addScore(winner.getPiece().getColor(), PieceScore.valueOf(loser.getPiece().getType()));
            }

            if (existingOccupant != null) {
                if (!airCaptureService.isCapture(existingOccupant.getColor(), winner.getPiece().getColor())) {
                    // Defensive: a friendly piece already occupies this square. Valid,
                    // validated moves can't normally produce this, so refuse to
                    // overwrite it rather than risk corrupting the board.
                    continue;
                }
                if (existingOccupant.getType() == Piece.Type.KING) {
                    setGameOver(true);
                    setWinner(winner.getPiece().getColor());
                }
                addScore(winner.getPiece().getColor(), PieceScore.valueOf(existingOccupant.getType()));
            }

            board.setPiece(destination.getRow(), destination.getCol(), winner.getPiece());
            recentlyCompletedMoves.add(winner);
            // survivingMoves only ever contains normal moves (see advanceTime,
            // which routes completed jumps down a separate path below) - so a
            // winner here always just finished a MOVE, never a jump, and
            // always gets the move -> long_rest duration.
            markResting(destination, EnginePort.REST_AFTER_MOVE_MS);

            // Only the piece that just arrived is eligible - never a blanket
            // board scan, which would also promote a pawn that merely started
            // a scenario sitting on the edge row without ever moving there.
            pawnPromotionService.handlePawnPromotion(winner.getPiece(), destination);
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
}

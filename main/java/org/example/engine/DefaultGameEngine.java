package org.example.engine;

import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;
import org.example.rules.MoveValidationPort;
import org.example.rules.PieceScore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Engine layer: the default GameEngine implementation - the application
 * service that mediates between the model (Board), the rules layer
 * (MoveValidationPort) and the real-time arbiter (EnginePort).
 *
 * Everything InteractionHandler used to do beyond "interpret a click" now
 * lives here: application guards (game-over), rule validation, distance/
 * duration math, scheduling moves with the arbiter, the jump reaction-window
 * rule, and all direct Board mutation. It is also the ONLY place that builds
 * a GameSnapshot - the pixel-position/visual-state computation that used to
 * live in BoardView (a business-logic duplication the architecture review
 * flagged specifically) is done exactly once, here, from the arbiter's own
 * state - never re-derived by the view.
 */
public final class DefaultGameEngine implements GameEngine {

    // How long after an enemy's attacking move starts a defending jump can
    // still succeed - see GameEngine.requestJump's class doc.
    private static final long JUMP_DEFENSE_WINDOW_MS = 800;

    private final Board board;
    private final EnginePort engine;
    private final MoveValidationPort moveValidationService;
    private final int cellSize;

    public DefaultGameEngine(Board board, EnginePort engine, MoveValidationPort moveValidationService, int cellSize) {
        this.board = board;
        this.engine = engine;
        this.moveValidationService = moveValidationService;
        this.cellSize = cellSize;
    }

    @Override
    public MoveResult requestMove(Position source, Position destination) {
        if (engine.isGameOver()) {
            return MoveResult.rejected("game_over");
        }

        Piece piece = board.getPiece(source);
        if (piece == null) {
            return MoveResult.rejected("no_piece");
        }
        if (engine.isPieceMovingFrom(source) || engine.isPieceResting(source)) {
            return MoveResult.rejected("piece_not_ready");
        }

        // Both colors may have moves in flight at the same time - that's the
        // whole premise of a real-time ("kung fu") chess variant, as opposed
        // to a turn-based one. The only thing this blocks is the
        // destination square already being reserved by an active move of
        // the SAME color - two of your own pieces can't be sent to the same
        // square.
        if (engine.isSquareOccupiedByActiveMove(destination, piece.getColor())) {
            return MoveResult.rejected("square_reserved");
        }
        if (!moveValidationService.isValidMove(source, destination, piece)) {
            return MoveResult.rejected("invalid_move");
        }

        int distance = moveValidationService.calculateDistance(source, destination);
        long totalTravelTime = distance * EnginePort.MOVE_DURATION_PER_SQUARE;
        long arrivalTime = engine.getGameTimeMillis() + totalTravelTime;

        engine.addMove(new ActiveMove(source, destination, piece, arrivalTime, false));
        return MoveResult.accepted();
    }

    @Override
    public JumpResult requestJump(Position position) {
        if (engine.isGameOver()) {
            return JumpResult.rejected("game_over");
        }

        Piece piece = board.getPiece(position);
        if (piece == null) {
            return JumpResult.rejected("no_piece");
        }

        // A piece resting after its last action (move or jump) cannot jump
        // again until the rest period ends.
        if (engine.isPieceResting(position)) {
            return JumpResult.rejected("piece_resting");
        }
        if (engine.isPieceMovingFrom(position)) {
            return JumpResult.rejected("piece_moving");
        }

        // If an enemy move targeting this square has been in flight for
        // longer than JUMP_DEFENSE_WINDOW_MS, the jump comes too late: the
        // attacker's move is forced to complete immediately instead of
        // being defended against, capturing the piece that tried to jump.
        ActiveMove lateEnemyMove = null;
        for (ActiveMove move : engine.getActiveMoves()) {
            if (!move.isJump() && move.getTo().equals(position) && move.getPiece().getColor() != piece.getColor()) {
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
                    engine.setWinner(lateEnemyMove.getPiece().getColor());
                }
                // This capture happens here directly (board.movePiece below),
                // not through any of the arbiter's own capture-resolution
                // paths, so it has to report the score credit back in itself.
                engine.addScore(lateEnemyMove.getPiece().getColor(), PieceScore.valueOf(targetPiece.getType()));
            }

            board.movePiece(lateEnemyMove.getFrom(), lateEnemyMove.getTo());
            // This is a MOVE being forced to complete early because the
            // defending jump came too late, not a jump itself - it gets the
            // same move -> long_rest duration a normally-arriving move gets,
            // not the shorter jump -> short_rest.
            engine.markResting(lateEnemyMove.getTo(), EnginePort.REST_AFTER_MOVE_MS);
            engine.removeMove(lateEnemyMove);

            return JumpResult.rejected("too_late");
        }

        // Within the reaction window (or against no threat at all): jumping
        // here defends the square normally. engine.addMove below runs the
        // arbiter's own air-capture check, which handles an incoming
        // attacker colliding with this jump.
        long arrivalTime = engine.getGameTimeMillis() + EnginePort.JUMP_DURATION;
        engine.addMove(new ActiveMove(position, position, piece, arrivalTime, true));
        return JumpResult.accepted();
    }

    @Override
    public void advanceTime(long millis) {
        engine.advanceTime(millis);
    }

    @Override
    public boolean isGameOver() {
        return engine.isGameOver();
    }

    @Override
    public long getGameTimeMillis() {
        return engine.getGameTimeMillis();
    }

    @Override
    public Optional<Piece> pieceAt(Position position) {
        return Optional.ofNullable(board.getPiece(position));
    }

    @Override
    public boolean canSelect(Position position) {
        return !engine.isPieceMovingFrom(position) && !engine.isPieceResting(position);
    }

    @Override
    public int getScore(Piece.Color color) {
        return engine.getScore(color);
    }

    @Override
    public GameSnapshot snapshot(Position selectedPosition, Position rejectedPosition, long rejectedAtMillis) {
        long now = engine.getGameTimeMillis();
        List<PieceSnapshot> pieces = new ArrayList<>();

        // A traveling (non-jump) move's piece is NOT reflected in the
        // model's grid until the move actually completes, so its origin
        // square still returns the piece via board.getPiece for the whole
        // trip - skip it in the resting-piece pass below and add it
        // separately, interpolated along its path.
        Set<Position> travelingFrom = new HashSet<>();
        Map<Position, ActiveMove> jumpingAt = new HashMap<>();
        for (ActiveMove move : engine.getActiveMoves()) {
            if (move.isJump()) {
                jumpingAt.put(move.getFrom(), move);
            } else {
                travelingFrom.add(move.getFrom());
            }
        }

        for (int row = 0; row < board.getHeight(); row++) {
            for (int col = 0; col < board.getWidth(); col++) {
                Position pos = new Position(row, col);
                if (travelingFrom.contains(pos)) continue;

                Piece piece = board.getPiece(pos);
                if (piece == null) continue;

                double pixelX = col * (double) cellSize;
                double pixelY = row * (double) cellSize;

                boolean resting = engine.isPieceResting(pos);
                long restDuration = resting ? engine.getRestingDurationMillis(pos) : -1;
                long restUntil = resting ? engine.getRestingUntilMillis(pos) : -1;

                VisualState visualState;
                long elapsed;
                ActiveMove jump = jumpingAt.get(pos);
                if (jump != null) {
                    visualState = VisualState.JUMP;
                    long jumpStart = jump.getArrivalTimeMillis() - EnginePort.JUMP_DURATION;
                    elapsed = now - jumpStart;
                } else if (resting && restDuration > 0) {
                    visualState = (restDuration == EnginePort.REST_AFTER_JUMP_MS)
                            ? VisualState.SHORT_REST
                            : VisualState.LONG_REST;
                    elapsed = now - (restUntil - restDuration);
                } else {
                    visualState = VisualState.IDLE;
                    elapsed = now;
                }

                pieces.add(new PieceSnapshot(piece.getId(), piece.getColor(), piece.getType(),
                        pixelX, pixelY, visualState, elapsed));
            }
        }

        for (ActiveMove move : engine.getActiveMoves()) {
            // Jumps never leave their square - already added with the JUMP
            // state in the pass above, so they need no travel interpolation.
            if (move.isJump()) continue;

            int distance = chebyshevDistance(move.getFrom(), move.getTo());
            long totalDuration = distance * EnginePort.MOVE_DURATION_PER_SQUARE;
            long moveStart = move.getArrivalTimeMillis() - totalDuration;
            double progress = totalDuration <= 0
                    ? 1.0
                    : Math.max(0.0, Math.min(1.0, (now - moveStart) / (double) totalDuration));

            double fromX = move.getFrom().getCol() * (double) cellSize;
            double fromY = move.getFrom().getRow() * (double) cellSize;
            double toX = move.getTo().getCol() * (double) cellSize;
            double toY = move.getTo().getRow() * (double) cellSize;

            double pixelX = fromX + (toX - fromX) * progress;
            double pixelY = fromY + (toY - fromY) * progress;

            Piece piece = move.getPiece();
            pieces.add(new PieceSnapshot(piece.getId(), piece.getColor(), piece.getType(),
                    pixelX, pixelY, VisualState.MOVE, now - moveStart));
        }

        return new GameSnapshot(board.getWidth(), board.getHeight(), pieces,
                selectedPosition, rejectedPosition, rejectedAtMillis,
                now, engine.isGameOver(), engine.getWinner());
    }

    private static int chebyshevDistance(Position from, Position to) {
        int deltaRow = Math.abs(to.getRow() - from.getRow());
        int deltaCol = Math.abs(to.getCol() - from.getCol());
        return Math.max(deltaRow, deltaCol);
    }
}

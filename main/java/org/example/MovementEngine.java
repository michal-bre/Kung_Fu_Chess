package org.example;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class MovementEngine {
    private final Board board;
    private final List<ActiveMove> activeMoves;
    private long gameTimeMillis;
    private boolean isGameOver;

    public static final long MOVE_DURATION_PER_SQUARE = 1000;
    public static final long JUMP_DURATION = 1000;

    public MovementEngine(Board board) {
        this.board = board;
        this.activeMoves = new ArrayList<>();
        this.gameTimeMillis = 0;
        this.isGameOver = false;
    }

    public List<ActiveMove> getActiveMoves() {
        return activeMoves;
    }

    public long getGameTimeMillis() {
        return gameTimeMillis;
    }

    public boolean isGameOver() {
        return isGameOver;
    }

    public void setGameOver(boolean gameOver) {
        this.isGameOver = gameOver;
    }

    public void addMove(ActiveMove move) {
        activeMoves.add(move);
        triggerAirCaptures();
    }

    public void removeMove(ActiveMove move) {
        activeMoves.remove(move);
    }

    public boolean isPieceMovingFrom(Position pos) {
        for (ActiveMove move : activeMoves) {
            if (move.getFrom().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    public boolean isPieceMovingTo(Position pos) {
        for (ActiveMove move : activeMoves) {
            if (move.getTo().equals(pos) && !move.isJump()) {
                return true;
            }
        }
        return false;
    }

    public boolean isColorMoving(Piece.Color color) {
        for (ActiveMove move : activeMoves) {
            if (move.getPiece().getColor() == color && !move.isJump()) {
                return true;
            }
        }
        return false;
    }

    public boolean isSquareOccupiedByActiveMove(Position pos, Piece.Color movingColor) {
        for (ActiveMove move : activeMoves) {
            if (move.getTo().equals(pos) && move.getPiece().getColor() == movingColor && !move.isJump()) {
                return true;
            }
        }
        return false;
    }

    public void triggerAirCaptures() {
        ActiveMove activeJump = null;
        for (ActiveMove move : activeMoves) {
            if (move.isJump()) {
                activeJump = move;
                break;
            }
        }

        if (activeJump == null) return;

        Iterator<ActiveMove> iterator = activeMoves.iterator();
        while (iterator.hasNext()) {
            ActiveMove move = iterator.next();
            if (!move.isJump() && move.getTo().equals(activeJump.getTo()) && move.getPiece().getColor() != activeJump.getPiece().getColor()) {
                long enemyDistance = Math.max(Math.abs(move.getTo().getRow() - move.getFrom().getRow()), Math.abs(move.getTo().getCol() - move.getFrom().getCol()));
                long enemyStartTime = move.getArrivalTimeMillis() - (enemyDistance * MOVE_DURATION_PER_SQUARE);
                long jumpStartTime = activeJump.getArrivalTimeMillis() - JUMP_DURATION;

                if (jumpStartTime == enemyStartTime) {
                    board.setPiece(move.getFrom().getRow(), move.getFrom().getCol(), null);
                    iterator.remove();
                }
            }
        }
    }

    public void advanceTime(long millis) {
        if (millis <= 0) return;

        this.gameTimeMillis += millis;

        List<ActiveMove> completedMoves = new ArrayList<>();
        List<ActiveMove> completedJumps = new ArrayList<>();

        Iterator<ActiveMove> iterator = activeMoves.iterator();
        while (iterator.hasNext()) {
            ActiveMove move = iterator.next();
            if (move.isComplete(this.gameTimeMillis)) {
                if (move.isJump()) {
                    completedJumps.add(move);
                } else {
                    completedMoves.add(move);
                }
                iterator.remove();
            }
        }

        for (ActiveMove normalMove : completedMoves) {
            boolean capturedInAir = false;

            for (ActiveMove jumpMove : completedJumps) {
                if (jumpMove.getTo().equals(normalMove.getTo()) && jumpMove.getPiece().getColor() != normalMove.getPiece().getColor()) {
                    capturedInAir = true;
                    break;
                }
            }

            if (capturedInAir) {
                continue;
            }

            Piece targetPiece = board.getPiece(normalMove.getTo());
            if (targetPiece != null && targetPiece.getType() == Piece.Type.KING) {
                isGameOver = true;
            }

            board.movePiece(normalMove.getFrom(), normalMove.getTo());
            handlePawnPromotion(normalMove);
        }
    }

    public void handlePawnPromotion(ActiveMove move) {
        Piece movedPiece = move.getPiece();
        if (movedPiece.getType() == Piece.Type.PAWN) {
            int targetRow = move.getTo().getRow();
            boolean isWhitePromotion = (movedPiece.getColor() == Piece.Color.WHITE && targetRow == 0);
            boolean isBlackPromotion = (movedPiece.getColor() == Piece.Color.BLACK && targetRow == board.getHeight() - 1);

            if (isWhitePromotion || isBlackPromotion) {
                board.setPiece(targetRow, move.getTo().getCol(), new Piece(movedPiece.getColor(), Piece.Type.QUEEN));
            }
        }
    }
}
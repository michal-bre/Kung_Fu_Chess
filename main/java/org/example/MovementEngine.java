package org.example;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class MovementEngine {
    private final Board board;
    private final List<ActiveMove> activeMoves;
    private final List<ActiveMove> recentlyCompletedMoves;
    private long gameTimeMillis;
    private boolean isGameOver;

    public static final long MOVE_DURATION_PER_SQUARE = 1000;
    public static final long JUMP_DURATION = 1000;

    public MovementEngine(Board board) {
        this.board = board;
        this.activeMoves = new ArrayList<>();
        this.recentlyCompletedMoves = new ArrayList<>();
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
                if (!move.isJump() && move.getTo().equals(activeJump.getTo()) && move.getPiece().getColor() != activeJump.getPiece().getColor()) {
                    // An enemy piece is moving to the same cell as the jumping piece
                    // Remove the enemy piece from the board at its starting position
                    board.setPiece(move.getFrom().getRow(), move.getFrom().getCol(), null);
                    toRemove.add(move);
                }
            }
        }
        
        activeMoves.removeAll(toRemove);
    }

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
                if (!activeMove.isJump() && activeMove.getTo().equals(completedJump.getTo()) && 
                    activeMove.getPiece().getColor() != completedJump.getPiece().getColor()) {
                    // This enemy piece will be captured
                    board.setPiece(activeMove.getFrom().getRow(), activeMove.getFrom().getCol(), null);
                    capturedInTransit.add(activeMove);
                }
            }
            activeMoves.removeAll(capturedInTransit);
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
            
            // If there's a target piece, this is a capture
            if (targetPiece != null && targetPiece.getColor() != normalMove.getPiece().getColor()) {
                // Remove the captured piece from the target location
                board.setPiece(normalMove.getTo().getRow(), normalMove.getTo().getCol(), null);
                
                // Check if the captured piece was a KING (game over)
                if (targetPiece.getType() == Piece.Type.KING) {
                    isGameOver = true;
                }
            } else {
                // Normal move (no capture) - move the piece
                board.movePiece(normalMove.getFrom(), normalMove.getTo());
                handlePawnPromotion(normalMove);
            }
            
            recentlyCompletedMoves.add(normalMove);
        }
    }

    public boolean isPieceJustCompleted(Position pos) {
        for (ActiveMove move : recentlyCompletedMoves) {
            if (move.getTo().equals(pos)) {
                return true;
            }
        }
        return false;
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
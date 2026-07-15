package org.example.engine;

import org.example.model.Piece;
import org.example.model.Position;

import java.util.Collections;
import java.util.List;

/**
 * Engine layer: a single, self-contained, read-only picture of everything a
 * renderer needs to draw one frame - and nothing else.
 *
 * This is the fix for the architecture review's core complaint about the
 * previous design: BoardView used to hold live references to Board,
 * EnginePort AND GameController simultaneously, and had to "reassemble" the
 * game's state itself on every repaint (which squares have pieces, which
 * moves are in flight, which piece is resting, whether the game is over) -
 * meaning any change to how the engine represents that state also forced a
 * change to the view. A GameSnapshot inverts this: DefaultGameEngine (the
 * one place that actually knows how state is represented) builds ONE
 * complete, immutable snapshot per frame; the renderer only ever reads
 * values off of it and never touches Board, EnginePort, or the controller.
 *
 * Everything here is already resolved to renderer-ready values - pixel
 * positions instead of Position + travel math, an explicit winner instead of
 * "scan the board for which king survived", an explicit selected/rejected
 * square instead of reaching into the controller for it.
 */
public final class GameSnapshot {
    private final int boardWidth;
    private final int boardHeight;
    private final List<PieceSnapshot> pieces;
    private final Position selectedPosition;
    private final Position rejectedPosition;
    private final long rejectedAtMillis;
    private final long gameTimeMillis;
    private final boolean gameOver;
    private final Piece.Color winner;

    public GameSnapshot(int boardWidth, int boardHeight, List<PieceSnapshot> pieces,
                         Position selectedPosition, Position rejectedPosition, long rejectedAtMillis,
                         long gameTimeMillis, boolean gameOver, Piece.Color winner) {
        this.boardWidth = boardWidth;
        this.boardHeight = boardHeight;
        this.pieces = Collections.unmodifiableList(pieces);
        this.selectedPosition = selectedPosition;
        this.rejectedPosition = rejectedPosition;
        this.rejectedAtMillis = rejectedAtMillis;
        this.gameTimeMillis = gameTimeMillis;
        this.gameOver = gameOver;
        this.winner = winner;
    }

    public int getBoardWidth() {
        return boardWidth;
    }

    public int getBoardHeight() {
        return boardHeight;
    }

    public List<PieceSnapshot> getPieces() {
        return pieces;
    }

    public Position getSelectedPosition() {
        return selectedPosition;
    }

    public Position getRejectedPosition() {
        return rejectedPosition;
    }

    public long getRejectedAtMillis() {
        return rejectedAtMillis;
    }

    public long getGameTimeMillis() {
        return gameTimeMillis;
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public Piece.Color getWinner() {
        return winner;
    }
}

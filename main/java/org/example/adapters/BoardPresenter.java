package org.example.adapters;

import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;

/**
 * Output Adapter: Presents board state to the console.
 * Isolates all System.out calls from the model/rules/engine/controller layers.
 * This is the ONLY place where UI output concerns should exist.
 *
 * Clean Architecture: Adapter layer (outermost). The controller layer never
 * references this class - the composition root (Main) uses it directly.
 */
public class BoardPresenter {
    private final Board board;

    public BoardPresenter(Board board) {
        this.board = board;
    }

    public void printBoard() {
        for (int r = 0; r < board.getHeight(); r++) {
            StringBuilder rowStr = new StringBuilder();
            for (int c = 0; c < board.getWidth(); c++) {
                Position pos = new Position(r, c);
                Piece piece = board.getPiece(pos);

                if (piece == null) {
                    rowStr.append(".");
                } else {
                    rowStr.append(piece.toString());
                }

                if (c < board.getWidth() - 1) {
                    rowStr.append(" ");
                }
            }
            System.out.println(rowStr);
        }
    }
}

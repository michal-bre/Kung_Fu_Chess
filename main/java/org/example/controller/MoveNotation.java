package org.example.controller;

import org.example.model.Piece;
import org.example.model.Position;

/**
 * The simplified algebraic-style move notation used throughout this project
 * (e.g. "Nc6", "Bxc6", "e4", "exd5") - extracted from InteractionHandler so
 * Room (the networked server's move handler - see its class doc) can build
 * the exact same notation for a MOVE it accepts as InteractionHandler always
 * has for a local click, instead of duplicating this formatting logic (and
 * risking it silently drifting out of sync between the two).
 *
 * Not a full implementation of Standard Algebraic Notation: no disambiguation
 * between two like pieces that could reach the same square, no check/
 * checkmate suffix (this engine has no check-detection at all), and no
 * castling (the King's move shape only ever allows a single square, so
 * castling can't occur here) - see MoveHistoryEntry's class doc for why that
 * approximation is enough for this project's purposes.
 */
public final class MoveNotation {

    private MoveNotation() {
    }

    public static String format(Piece piece, Position from, Position to, boolean isCapture, int boardHeight) {
        String destination = squareName(to, boardHeight);
        if (piece.getType() == Piece.Type.PAWN) {
            return isCapture ? (fileLetter(from.getCol()) + "x" + destination) : destination;
        }
        return piece.getType().getSymbol() + (isCapture ? "x" : "") + destination;
    }

    public static String squareName(Position pos, int boardHeight) {
        return "" + fileLetter(pos.getCol()) + rankNumber(pos.getRow(), boardHeight);
    }

    private static char fileLetter(int col) {
        return (char) ('a' + col);
    }

    private static int rankNumber(int row, int boardHeight) {
        // Row 0 is the top of the board (the initial black back rank, i.e.
        // chess rank 8 - see GuiMain's starting position), so rank counts
        // down as row counts up.
        return boardHeight - row;
    }
}

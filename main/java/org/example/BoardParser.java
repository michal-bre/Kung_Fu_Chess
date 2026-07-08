package org.example;

import java.util.ArrayList;
import java.util.List;

public class BoardParser {

    // Parses raw text lines into a validated Board object
    public static Board parse(List<String> boardLines) throws IllegalArgumentException {
        if (boardLines == null || boardLines.isEmpty()) {
            throw new IllegalArgumentException("ERROR ROW_WIDTH_MISMATCH");
        }

        // Infer board dimensions
        int height = boardLines.size();
        String[] firstRowTokens = boardLines.get(0).trim().split("\\s+");
        int width = firstRowTokens.length;

        Board board = new Board(width, height);

        // Process each cell on the board
        for (int r = 0; r < height; r++) {
            String[] tokens = boardLines.get(r).trim().split("\\s+");

            // Validate that all rows have the exact same width
            if (tokens.length != width) {
                throw new IllegalArgumentException("ERROR ROW_WIDTH_MISMATCH");
            }

            for (int c = 0; c < width; c++) {
                String token = tokens[c];

                // If it's a dot, the square is empty (leave as null)
                if (token.equals(".")) {
                    continue;
                }

                // Validate token length for a standard piece (e.g., "wK")
                if (token.length() != 2) {
                    throw new IllegalArgumentException("ERROR UNKNOWN_TOKEN");
                }

                Piece.Color color = Piece.Color.fromChar(token.charAt(0));
                Piece.Type type = Piece.Type.fromChar(token.charAt(1));

                // Validate if both color and type are recognized
                if (color == null || type == null) {
                    throw new IllegalArgumentException("ERROR UNKNOWN_TOKEN");
                }

                board.setPiece(r, c, new Piece(color, type));
            }
        }

        return board;
    }
}
package org.example;

public class Board {
    private final int width;
    private final int height;
    private final Piece[][] grid; // Dynamic 2D array to hold the board state

    // Initializes the board dynamically based on inferred dimensions
    public Board(int width, int height) {
        this.width = width;
        this.height = height;
        this.grid = new Piece[height][width]; // Rows come first (height), then columns (width)
    }

    // Places a piece at a specific coordinate on the grid
    public void setPiece(int row, int col, Piece piece) {
        grid[row][col] = piece;
    }

    // Prints the board configuration precisely as expected by the VPL platform
    public void print() {
        for (int r = 0; r < height; r++) {
            StringBuilder rowStr = new StringBuilder();
            for (int c = 0; c < width; c++) {
                if (grid[r][c] == null) {
                    rowStr.append("."); // Prints a dot for empty squares
                } else {
                    rowStr.append(grid[r][c].toString()); // Prints the piece token (e.g., "wK")
                }

                // appends a single space between elements, ensuring no trailing space at the end of a line
                if (c < width - 1) {
                    rowStr.append(" ");
                }
            }
            System.out.println(rowStr); // Prints the completed row
        }
    }

    // Constants for pixel calculations (flexible for future changes)
    public static final int CELL_SIZE = 100;

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    // Safely checks if a given row and column are within the board boundaries
    public boolean isWithinBounds(int row, int col) {
        return row >= 0 && row < height && col >= 0 && col < width;
    }

    // Overloaded method to check bounds using a Position object
    public boolean isWithinBounds(Position pos) {
        return isWithinBounds(pos.getRow(), pos.getCol());
    }

    // Retrieves a piece at a specific position
    public Piece getPiece(Position pos) {
        if (!isWithinBounds(pos)) {
            return null;
        }
        return grid[pos.getRow()][pos.getCol()];
    }

    // Moves a piece from one position to another, clearing the old position
    public void movePiece(Position from, Position to) {
        if (!isWithinBounds(from) || !isWithinBounds(to)) {
            return;
        }
        Piece pieceToMove = getPiece(from);
        grid[to.getRow()][to.getCol()] = pieceToMove;
        grid[from.getRow()][from.getCol()] = null; // Clear old square
    }

}
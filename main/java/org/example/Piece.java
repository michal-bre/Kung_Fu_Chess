package org.example;

public class Piece {

    public enum Color {
        WHITE('w'), BLACK('b');

        private final char symbol;

        Color(char symbol) {
            this.symbol = symbol;
        }

        public char getSymbol() {
            return symbol;
        }

        public static Color fromChar(char c) {
            if (c == 'w') return WHITE;
            if (c == 'b') return BLACK;
            return null;
        }
    }

    public enum Type {
        KING('K'), QUEEN('Q'), ROOK('R'), KNIGHT('N'), BISHOP('B'), PAWN('P');

        private final char symbol;

        Type(char symbol) {
            this.symbol = symbol;
        }

        public char getSymbol() {
            return symbol;
        }

        public static Type fromChar(char c) {
            for (Type t : Type.values()) {
                if (t.symbol == c) return t;
            }
            return null;
        }

        // Validates if the movement geometry is valid for this specific piece type
        public boolean isValidMoveShape(int deltaRow, int deltaCol) {
            int absRow = Math.abs(deltaRow);
            int absCol = Math.abs(deltaCol);

            switch (this) {
                case KING:
                    // King moves exactly 1 square in any direction
                    return absRow <= 1 && absCol <= 1 && (absRow != 0 || absCol != 0);

                case ROOK:
                    // Rook moves horizontally or vertically, but not both
                    return (absRow > 0 && absCol == 0) || (absRow == 0 && absCol > 0);

                case BISHOP:
                    // Bishop moves diagonally (equal row and column change)
                    return absRow == absCol && absRow > 0;

                case QUEEN:
                    // Queen combines Rook and Bishop logic
                    return ((absRow > 0 && absCol == 0) || (absRow == 0 && absCol > 0)) || (absRow == absCol && absRow > 0);

                case KNIGHT:
                    // Knight moves in an L-shape (2x1 or 1x2)
                    return (absRow == 2 && absCol == 1) || (absRow == 1 && absCol == 2);

                case PAWN:
                    // Pawn movement is not required/validated for Iteration 3, return true/false based on target implementation
                    return true;

                default:
                    return false;
            }
        }
    }

    private final Color color;
    private final Type type;

    public Piece(Color color, Type type) {
        this.color = color;
        this.type = type;
    }

    public Color getColor() {
        return color;
    }

    public Type getType() {
        return type;
    }

    @Override
    public String toString() {
        return "" + color.getSymbol() + type.getSymbol();
    }
}
package org.example.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Model layer: pure domain entity, zero dependencies on any other layer.
 */
public class Piece {

    // A per-instance identity distinct from (color, type) - two different
    // pieces of the same color/type (e.g. two white pawns) are otherwise
    // indistinguishable by value. Used only so outer layers (e.g. a
    // rendering snapshot) can refer to "this specific piece" without leaking
    // a live Piece reference - see org.example.engine.PieceSnapshot. Purely
    // additive: every existing two-argument constructor call keeps working
    // unchanged, and nothing in model/rules/engine/controller reads this
    // field for game logic.
    private static final AtomicLong NEXT_ID = new AtomicLong(1);
    private final long id;

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
        this.id = NEXT_ID.getAndIncrement();
    }

    public Color getColor() {
        return color;
    }

    public Type getType() {
        return type;
    }

    public String getId() {
        return "p" + id;
    }

    @Override
    public String toString() {
        return "" + color.getSymbol() + type.getSymbol();
    }
}

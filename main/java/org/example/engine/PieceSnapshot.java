package org.example.engine;

import org.example.model.Piece;

/**
 * Engine layer: a read-only description of exactly what a renderer needs to
 * draw one piece for one frame - nothing more. See GameSnapshot's class doc
 * for the full rationale (this is the per-piece half of that snapshot).
 *
 * Deliberately does NOT expose the live Piece or a Position: a renderer never
 * needs to know "where this piece's origin/destination square is" or "how
 * far along its move it is" - only the final pixel coordinate it should be
 * drawn at right now. All of that arithmetic (distance, travel duration,
 * interpolation) is computed once, by DefaultGameEngine when it builds the
 * snapshot - never re-derived by the view layer. See ArchitectureDoc / the
 * project's code-review notes on why duplicating that math in the renderer
 * is a business-rule duplication bug waiting to happen.
 */
public final class PieceSnapshot {
    private final String id;
    private final Piece.Color color;
    private final Piece.Type type;
    private final double pixelX;
    private final double pixelY;
    private final VisualState visualState;
    private final long stateElapsedMillis;

    public PieceSnapshot(String id, Piece.Color color, Piece.Type type, double pixelX, double pixelY,
                          VisualState visualState, long stateElapsedMillis) {
        this.id = id;
        this.color = color;
        this.type = type;
        this.pixelX = pixelX;
        this.pixelY = pixelY;
        this.visualState = visualState;
        this.stateElapsedMillis = stateElapsedMillis;
    }

    public String getId() {
        return id;
    }

    public Piece.Color getColor() {
        return color;
    }

    public Piece.Type getType() {
        return type;
    }

    public double getPixelX() {
        return pixelX;
    }

    public double getPixelY() {
        return pixelY;
    }

    public VisualState getVisualState() {
        return visualState;
    }

    public long getStateElapsedMillis() {
        return stateElapsedMillis;
    }
}

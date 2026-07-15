package org.example.controller;

import org.example.model.Position;

import java.util.Optional;

/**
 * Controller layer: Coordinate Adapter. The ONLY place raw pixel coordinates
 * are converted into a logical board Position.
 *
 * Before this class existed, "row = y / cellSize, col = x / cellSize" was
 * duplicated between InteractionHandler.handleClick and handleJump - and
 * cellSize itself was read off Board.CELL_SIZE, meaning a purely logical 8x8
 * grid had to know how many pixels wide a square is on screen. Neither of
 * those is really a game-rules concern: how big a square is drawn, and
 * whether there's a scaling/offset/viewport between screen pixels and board
 * squares, is presentation configuration - so it lives here, injected once
 * at composition-root time, instead of being baked into Board or repeated at
 * every call site that needs to interpret a click.
 *
 * Returns Optional.empty() (rather than an out-of-bounds Position) for a
 * click outside the board entirely, so callers don't each need their own
 * bounds check on top of Board.isWithinBounds.
 */
public final class BoardMapper {
    private final int cellSize;
    private final int rows;
    private final int columns;

    public BoardMapper(int cellSize, int rows, int columns) {
        this.cellSize = cellSize;
        this.rows = rows;
        this.columns = columns;
    }

    public Optional<Position> toPosition(int pixelX, int pixelY) {
        if (pixelX < 0 || pixelY < 0) {
            return Optional.empty();
        }

        int col = pixelX / cellSize;
        int row = pixelY / cellSize;

        if (row >= rows || col >= columns) {
            return Optional.empty();
        }

        return Optional.of(new Position(row, col));
    }

    public int getCellSize() {
        return cellSize;
    }
}

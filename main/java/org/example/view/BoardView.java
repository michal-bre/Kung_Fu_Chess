package org.example.view;

import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;
import org.example.view.imglib.Img;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * View layer: renders the chessboard and, as of this phase, every piece
 * currently on it. Built on top of org.example.view.imglib.Img, the
 * vendored image library from CTD26-main, per the design brief (no
 * third-party design/UI library).
 *
 * A JPanel is used - rather than Img.show()'s throwaway JLabel/JFrame - so
 * that later phases can keep redrawing onto the same live component (move
 * animation, highlighted squares, drag feedback) instead of rebuilding the
 * window from scratch every frame. paintComponent re-reads the board on
 * every call rather than caching piece positions, so a future repaint()
 * after a move always reflects current state.
 *
 * Depends on the model layer's Board directly, the same way BoardPresenter
 * (the console adapter) does: Board is a pure entity visible to every
 * layer, so no port is needed just to read it. BoardView never mutates the
 * board, only reads it.
 */
public class BoardView extends JPanel {

    private static final String BOARD_IMAGE_ASSET = "assets/board.png";

    // Leaves a margin around each piece sprite inside its cell so pieces
    // don't visually collide with their square's border.
    private static final int PIECE_PADDING_PX = 10;

    private final Board board;
    private final BufferedImage boardImage;
    private final PieceSprites pieceSprites = new PieceSprites();

    public BoardView(Board board) {
        this.board = board;

        int pixelWidth = board.getWidth() * Board.CELL_SIZE;
        int pixelHeight = board.getHeight() * Board.CELL_SIZE;

        // Stretched to exactly fill the logical grid (aspect NOT preserved).
        // InteractionHandler already derives row/col from raw pixels as
        // x / Board.CELL_SIZE, y / Board.CELL_SIZE - if the board image were
        // letterboxed to preserve its own aspect ratio instead, the picture
        // the player sees would be offset from the square math the engine
        // uses, and clicks would land on the wrong square once mouse input
        // is wired up in a later phase.
        Img loadedBoard = new Img().read(
                AssetPaths.resolve(BOARD_IMAGE_ASSET).getPath(),
                new Dimension(pixelWidth, pixelHeight),
                false,
                null);
        this.boardImage = loadedBoard.get();

        setPreferredSize(new Dimension(pixelWidth, pixelHeight));
        setOpaque(true);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(boardImage, 0, 0, null);
        paintPieces((Graphics2D) g);
    }

    private void paintPieces(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (int row = 0; row < board.getHeight(); row++) {
            for (int col = 0; col < board.getWidth(); col++) {
                Piece piece = board.getPiece(new Position(row, col));
                if (piece == null) continue;

                BufferedImage sprite = pieceSprites.get(piece.getColor(), piece.getType());
                paintSpriteCenteredInCell(g, sprite, row, col);
            }
        }
    }

    private void paintSpriteCenteredInCell(Graphics2D g, BufferedImage sprite, int row, int col) {
        int maxSize = Board.CELL_SIZE - 2 * PIECE_PADDING_PX;

        double scale = Math.min(
                maxSize / (double) sprite.getWidth(),
                maxSize / (double) sprite.getHeight());
        int drawWidth = (int) Math.round(sprite.getWidth() * scale);
        int drawHeight = (int) Math.round(sprite.getHeight() * scale);

        int cellX = col * Board.CELL_SIZE;
        int cellY = row * Board.CELL_SIZE;
        int drawX = cellX + (Board.CELL_SIZE - drawWidth) / 2;
        int drawY = cellY + (Board.CELL_SIZE - drawHeight) / 2;

        g.drawImage(sprite, drawX, drawY, drawWidth, drawHeight, null);
    }
}

package org.example.view;

import org.example.controller.GameController;
import org.example.engine.ActiveMove;
import org.example.engine.EnginePort;
import org.example.model.Board;
import org.example.model.Piece;
import org.example.model.Position;
import org.example.view.imglib.Img;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;

/**
 * View layer: renders the chessboard and every piece currently on or
 * traveling across it. Built on top of org.example.view.imglib.Img, the
 * vendored image library from CTD26-main, per the design brief (no
 * third-party design/UI library).
 *
 * A JPanel is used - rather than Img.show()'s throwaway JLabel/JFrame - so
 * that later phases can keep redrawing onto the same live component
 * (highlighted squares, drag feedback) instead of rebuilding the window
 * from scratch every frame. paintComponent re-reads the board/engine on
 * every call rather than caching state, so each GameLoop tick's repaint()
 * always reflects current state.
 *
 * Depends on the model layer's Board directly, the same way BoardPresenter
 * (the console adapter) does: Board is a pure entity visible to every
 * layer, so no port is needed just to read it. It also depends on the
 * engine layer's EnginePort ABSTRACTION (never the concrete
 * MovementEngine) - the same dependency-inversion the controller layer
 * already follows - purely to read in-flight move state for animation. It
 * additionally depends on GameController itself (not just an abstraction -
 * BoardInputListener already calls into it directly, so the view layer
 * already treats it as its facade into the controller layer), solely to
 * read transient click-rejection feedback for the flash effect below.
 *
 * BoardView never mutates the board, engine, or controller - it only reads
 * them.
 */
public class BoardView extends JPanel {

    private static final String BOARD_IMAGE_ASSET = "assets/board.png";

    // Leaves a margin around each piece sprite inside its cell so pieces
    // don't visually collide with their square's border.
    private static final int PIECE_PADDING_PX = 10;

    // How long a rejected-move flash stays visible, fading out over this
    // window rather than disappearing abruptly.
    private static final long REJECTION_FLASH_DURATION_MS = 400;

    // Cool blue-gray tint drawn under a resting piece, distinct from the
    // rejection flash's red so the two aren't confused for one another.
    private static final Color RESTING_TINT = new Color(40, 60, 120, 90);

    private final Board board;
    private final EnginePort engine;
    private final GameController gameController;
    private final BufferedImage boardImage;
    private final PieceSprites pieceSprites = new PieceSprites();

    public BoardView(Board board, EnginePort engine, GameController gameController) {
        this.board = board;
        this.engine = engine;
        this.gameController = gameController;

        int pixelWidth = board.getWidth() * Board.CELL_SIZE;
        int pixelHeight = board.getHeight() * Board.CELL_SIZE;

        // Loaded at native size (no resize yet) so BoardImageCropper can
        // find and strip off any decorative border/frame baked into the
        // asset BEFORE the checkerboard itself gets stretched to fill the
        // grid - see BoardImageCropper's class doc for why that order
        // matters. What's left after cropping is stretched to exactly fill
        // the logical grid (aspect NOT preserved). InteractionHandler
        // already derives row/col from raw pixels as x / Board.CELL_SIZE,
        // y / Board.CELL_SIZE - if the board image were letterboxed to
        // preserve its own aspect ratio instead, the picture the player
        // sees would be offset from the square math the engine uses, and
        // clicks would land on the wrong square.
        BufferedImage rawBoard = new Img().read(AssetPaths.resolve(BOARD_IMAGE_ASSET).getPath()).get();
        BufferedImage checkerboardOnly = BoardImageCropper.cropToCheckerboard(rawBoard);
        this.boardImage = scaleTo(checkerboardOnly, pixelWidth, pixelHeight);

        setPreferredSize(new Dimension(pixelWidth, pixelHeight));
        setOpaque(true);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(boardImage, 0, 0, null);
        paintPieces((Graphics2D) g);
        paintRejectionFeedback((Graphics2D) g);
        paintGameOverCaption((Graphics2D) g);
    }

    /**
     * Briefly flashes red over a square whose move attempt was just
     * rejected (illegal move shape, blocked path, opponent mid-move, etc.).
     * A rejected click is otherwise a total no-op - nothing on the board
     * changes - which is indistinguishable from "the click didn't register
     * at all" without some feedback. Fades out over
     * REJECTION_FLASH_DURATION_MS rather than disappearing on the next
     * frame, so a single rejected click is still visible even between two
     * GameLoop ticks.
     */
    private void paintRejectionFeedback(Graphics2D g) {
        Position rejected = gameController.getLastRejectedPosition();
        if (rejected == null) return;

        long elapsed = engine.getGameTimeMillis() - gameController.getLastRejectedAtMillis();
        if (elapsed < 0 || elapsed >= REJECTION_FLASH_DURATION_MS) return;

        float fade = 1f - (elapsed / (float) REJECTION_FLASH_DURATION_MS);
        int alpha = Math.round(160 * fade);

        g.setColor(new Color(220, 30, 30, alpha));
        g.fillRect(
                rejected.getCol() * Board.CELL_SIZE,
                rejected.getRow() * Board.CELL_SIZE,
                Board.CELL_SIZE,
                Board.CELL_SIZE);
    }

    /**
     * Draws a "game over" caption across the board once the engine reports
     * the game has ended (currently: a king was captured - see
     * MovementEngine.resolveSimultaneousArrivals and InteractionHandler.
     * handleJump). EnginePort only exposes a boolean, with no notion of "who
     * won", so the winner is inferred here from which king is still on the
     * board - a purely visual, read-only inference, never written back to
     * the model.
     */
    private void paintGameOverCaption(Graphics2D g) {
        if (!engine.isGameOver()) return;

        int width = getWidth();
        int height = getHeight();

        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(0, 0, width, height);

        String caption = resolveGameOverCaption();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(getFont().deriveFont(Font.BOLD, 42f));
        FontMetrics metrics = g.getFontMetrics();
        int textX = (width - metrics.stringWidth(caption)) / 2;
        int textY = (height + metrics.getAscent()) / 2;

        g.setColor(Color.BLACK);
        g.drawString(caption, textX + 2, textY + 2);
        g.setColor(Color.WHITE);
        g.drawString(caption, textX, textY);
    }

    private String resolveGameOverCaption() {
        boolean whiteKingAlive = false;
        boolean blackKingAlive = false;

        for (int row = 0; row < board.getHeight(); row++) {
            for (int col = 0; col < board.getWidth(); col++) {
                Piece piece = board.getPiece(new Position(row, col));
                if (piece != null && piece.getType() == Piece.Type.KING) {
                    if (piece.getColor() == Piece.Color.WHITE) whiteKingAlive = true;
                    else blackKingAlive = true;
                }
            }
        }

        if (whiteKingAlive && !blackKingAlive) return "GAME OVER — WHITE WINS";
        if (blackKingAlive && !whiteKingAlive) return "GAME OVER — BLACK WINS";
        return "GAME OVER";
    }

    private void paintPieces(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // The model's grid array is NOT updated while a (non-jump) move is in
        // flight - MovementEngine only writes the source/destination squares
        // once the move actually completes (see MovementEngine.
        // resolveSimultaneousArrivals) - so board.getPiece(from) still returns
        // the traveling piece for the whole trip. Without this, the piece
        // would render motionless at its origin square for the entire travel
        // time and then instantly teleport to its destination the moment the
        // move resolves. Squares that are the source of an in-flight move are
        // skipped in the resting-piece pass below and drawn separately,
        // interpolated along their path, further down.
        Set<Position> travelingFrom = new HashSet<>();
        for (ActiveMove move : engine.getActiveMoves()) {
            if (!move.isJump()) {
                travelingFrom.add(move.getFrom());
            }
        }

        for (int row = 0; row < board.getHeight(); row++) {
            for (int col = 0; col < board.getWidth(); col++) {
                Position pos = new Position(row, col);
                if (travelingFrom.contains(pos)) continue;

                Piece piece = board.getPiece(pos);
                if (piece == null) continue;

                int cellX = col * Board.CELL_SIZE;
                int cellY = row * Board.CELL_SIZE;

                // A piece that just finished a move/jump can't start a new
                // move until it stops resting (see EnginePort.isPieceResting /
                // REST_AFTER_MOVE_MS / REST_AFTER_JUMP_MS) - tint its square so
                // that cooldown is actually visible, not just enforced.
                if (engine.isPieceResting(pos)) {
                    g.setColor(RESTING_TINT);
                    g.fillRect(cellX, cellY, Board.CELL_SIZE, Board.CELL_SIZE);
                }

                BufferedImage sprite = pieceSprites.get(piece.getColor(), piece.getType());
                paintSpriteCentered(g, sprite, cellX, cellY);
            }
        }

        long now = engine.getGameTimeMillis();
        for (ActiveMove move : engine.getActiveMoves()) {
            // Jumps have from == to (see InteractionHandler.handleJump) - the
            // piece never leaves its square, so it needs no interpolation and
            // was already drawn as a normal resting piece above.
            if (move.isJump()) continue;

            double progress = travelProgress(move, now);

            int fromX = move.getFrom().getCol() * Board.CELL_SIZE;
            int fromY = move.getFrom().getRow() * Board.CELL_SIZE;
            int toX = move.getTo().getCol() * Board.CELL_SIZE;
            int toY = move.getTo().getRow() * Board.CELL_SIZE;

            int cellX = fromX + (int) Math.round((toX - fromX) * progress);
            int cellY = fromY + (int) Math.round((toY - fromY) * progress);

            BufferedImage sprite = pieceSprites.get(move.getPiece().getColor(), move.getPiece().getType());
            paintSpriteCentered(g, sprite, cellX, cellY);
        }
    }

    /**
     * How far along [0.0, 1.0] this move is between its source and
     * destination squares, recomputed independently from the same inputs
     * InteractionHandler used to schedule it (distance in squares x
     * EnginePort.MOVE_DURATION_PER_SQUARE), so the view never drifts out of
     * sync with the engine's own arrival time without needing a dedicated
     * "move started at" field on ActiveMove.
     */
    private static double travelProgress(ActiveMove move, long nowMillis) {
        int distance = chebyshevDistance(move.getFrom(), move.getTo());
        long totalDuration = distance * EnginePort.MOVE_DURATION_PER_SQUARE;
        if (totalDuration <= 0) return 1.0;

        long startTime = move.getArrivalTimeMillis() - totalDuration;
        double progress = (nowMillis - startTime) / (double) totalDuration;
        return Math.max(0.0, Math.min(1.0, progress));
    }

    private static int chebyshevDistance(Position from, Position to) {
        int deltaRow = Math.abs(to.getRow() - from.getRow());
        int deltaCol = Math.abs(to.getCol() - from.getCol());
        return Math.max(deltaRow, deltaCol);
    }

    /**
     * Scales an already-loaded image to an exact target size, matching the
     * bilinear quality Img.read's own resize path uses. Needed here (rather
     * than just passing a target Dimension straight to Img.read, as before)
     * because BoardImageCropper has to run on the raw, native-size image
     * BEFORE any resize happens.
     */
    private static BufferedImage scaleTo(BufferedImage src, int width, int height) {
        BufferedImage dst = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose();
        return dst;
    }

    private void paintSpriteCentered(Graphics2D g, BufferedImage sprite, int cellX, int cellY) {
        int maxSize = Board.CELL_SIZE - 2 * PIECE_PADDING_PX;

        double scale = Math.min(
                maxSize / (double) sprite.getWidth(),
                maxSize / (double) sprite.getHeight());
        int drawWidth = (int) Math.round(sprite.getWidth() * scale);
        int drawHeight = (int) Math.round(sprite.getHeight() * scale);

        int drawX = cellX + (Board.CELL_SIZE - drawWidth) / 2;
        int drawY = cellY + (Board.CELL_SIZE - drawHeight) / 2;

        g.drawImage(sprite, drawX, drawY, drawWidth, drawHeight, null);
    }
}

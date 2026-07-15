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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
 *
 * Display scaling: all internal drawing math (piece cells, the board image,
 * the rejection flash, the game-over caption) is computed in a fixed LOGICAL
 * pixel space of exactly board.getWidth()*CELL_SIZE x
 * board.getHeight()*CELL_SIZE - the same space InteractionHandler's
 * row = y / CELL_SIZE, col = x / CELL_SIZE math assumes. That logical space
 * can be larger than the screen (e.g. an 8-row board is 800px tall before
 * any window chrome, which some laptop/VM screens can't fit alongside a
 * title bar and taskbar), so the panel is scaled down to fit the display's
 * available work area via a single Graphics2D.scale() applied once at the
 * top of paintComponent - every existing pixel calculation below keeps
 * working unmodified in logical space; only the actual on-screen size
 * (getPreferredSize) and the final rendered pixels are affected. Mouse
 * input has to be scaled back the other way before it reaches the
 * controller - see getScale() and BoardInputListener.
 *
 * The scale computed in the constructor is only a first guess, made before
 * any native window exists: it estimates window-chrome size via
 * SCREEN_CHROME_MARGIN_PX because a title bar's real height (which varies
 * by OS, theme, and DPI scaling) isn't knowable yet. Implementing
 * ScreenFittable lets GameWindow correct that guess with the real numbers
 * once its frame actually exists - see GameWindow's class doc.
 */
public class BoardView extends JPanel implements ScreenFittable {

    private static final String BOARD_IMAGE_ASSET = "assets/board.png";

    // Leaves a margin around each piece sprite inside its cell so pieces
    // don't visually collide with their square's border.
    private static final int PIECE_PADDING_PX = 10;

    // How long a rejected-move flash stays visible, fading out over this
    // window rather than disappearing abruptly.
    private static final long REJECTION_FLASH_DURATION_MS = 400;

    // Cool blue-gray tint drawn under a resting piece, distinct from the
    // rejection flash's red so the two aren't confused for one another.
    private static final Color RESTING_TINT = new Color(243, 208, 31, 116);

    // Reserves room around the scaled board for the window's title bar and
    // the OS taskbar/dock when computing how large the board can be drawn
    // without exceeding the screen - see computeDisplayScale.
    private static final int SCREEN_CHROME_MARGIN_PX = 96;

    private final Board board;
    private final EnginePort engine;
    private final GameController gameController;
    private final BufferedImage boardImage;
    private final PieceSprites pieceSprites = new PieceSprites();
    private final int logicalWidth;
    private final int logicalHeight;

    // Not final: constrainToContentArea (called by GameWindow once the real
    // frame insets are known) may shrink this further after construction -
    // see the class doc and ScreenFittable.
    private double scale;

    public BoardView(Board board, EnginePort engine, GameController gameController) {
        this.board = board;
        this.engine = engine;
        this.gameController = gameController;

        this.logicalWidth = board.getWidth() * Board.CELL_SIZE;
        this.logicalHeight = board.getHeight() * Board.CELL_SIZE;
        this.scale = computeDisplayScale(logicalWidth, logicalHeight);

        // The board asset is assumed to be the checkerboard itself, edge to
        // edge, with no decorative border baked in - it's stretched to
        // exactly fill the LOGICAL grid (aspect NOT preserved) - display
        // scaling (if any) is applied uniformly afterward in
        // paintComponent, so it never distorts this stretch.
        // InteractionHandler already derives row/col from raw pixels as
        // x / Board.CELL_SIZE, y / Board.CELL_SIZE - if the board image were
        // letterboxed to preserve its own aspect ratio instead, the picture
        // the player sees would be offset from the square math the engine
        // uses, and clicks would land on the wrong square.
        BufferedImage rawBoard = new Img().read(AssetPaths.resolve(BOARD_IMAGE_ASSET).getPath()).get();
        this.boardImage = scaleTo(rawBoard, logicalWidth, logicalHeight);

        setPreferredSize(new Dimension(
                (int) Math.round(logicalWidth * scale),
                (int) Math.round(logicalHeight * scale)));
        setOpaque(true);
    }

    /**
     * How much smaller than logical size the board must be drawn to fit the
     * current display's usable work area (screen minus taskbar/dock), 1.0
     * meaning no scaling is needed. Never scales UP past 1.0 - a board that
     * already fits is drawn at its native, crispest resolution.
     */
    private static double computeDisplayScale(int logicalWidth, int logicalHeight) {
        try {
            Rectangle screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            double scaleX = screenBounds.width / (double) logicalWidth;
            double scaleY = (screenBounds.height - SCREEN_CHROME_MARGIN_PX) / (double) logicalHeight;
            return Math.min(1.0, Math.min(scaleX, scaleY));
        } catch (HeadlessException e) {
            // No display to measure against (e.g. running under a headless
            // test harness) - fall back to native size.
            return 1.0;
        }
    }

    /**
     * How much the on-screen board is shrunk relative to logical pixel
     * space. BoardInputListener divides raw mouse coordinates by this
     * before handing them to the controller, so a click still lands on the
     * same logical square regardless of display scaling.
     */
    public double getScale() {
        return scale;
    }

    /**
     * Called by GameWindow after its first pack(), once the frame's real
     * insets (actual title bar height, borders) and the screen's real work
     * area are both known. If the constructor's chrome-margin guess left
     * the board still too big for the real available space, this shrinks
     * scale further and updates the preferred size accordingly; GameWindow
     * then packs a second time against the corrected size. Never grows
     * scale past what the constructor already picked - only tightens it.
     */
    @Override
    public void constrainToContentArea(int maxWidth, int maxHeight) {
        double fitScale = Math.min(
                maxWidth / (double) logicalWidth,
                maxHeight / (double) logicalHeight);
        double newScale = Math.min(scale, fitScale);
        if (newScale >= scale) return;

        scale = newScale;
        setPreferredSize(new Dimension(
                (int) Math.round(logicalWidth * scale),
                (int) Math.round(logicalHeight * scale)));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Everything below this point draws in LOGICAL pixel coordinates;
        // this single transform is what makes the final on-screen result
        // fit the display. See the class doc.
        g2.scale(scale, scale);

        g2.drawImage(boardImage, 0, 0, null);
        paintPieces(g2);
        paintRejectionFeedback(g2);
        paintGameOverCaption(g2);
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

        // Logical dimensions, not getWidth()/getHeight() - this method draws
        // through the g2.scale(scale, scale) transform already applied in
        // paintComponent, so using the panel's actual (post-scale) on-screen
        // size here would shrink the overlay by scale a second time.
        int width = logicalWidth;
        int height = logicalHeight;

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

        long now = engine.getGameTimeMillis();

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
        //
        // A jump, by contrast, has from == to (see InteractionHandler.
        // handleJump) - the piece never leaves its square - so it IS drawn in
        // the pass below, just with the JUMP animation state instead of
        // IDLE/rest, for as long as the jump remains in flight.
        Set<Position> travelingFrom = new HashSet<>();
        Map<Position, ActiveMove> jumpingAt = new HashMap<>();
        for (ActiveMove move : engine.getActiveMoves()) {
            if (move.isJump()) {
                jumpingAt.put(move.getFrom(), move);
            } else {
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

                boolean resting = engine.isPieceResting(pos);
                long restDuration = resting ? engine.getRestingDurationMillis(pos) : -1;
                long restUntil = resting ? engine.getRestingUntilMillis(pos) : -1;

                // A piece that just finished a move/jump can't start a new move
                // until it stops resting (see EnginePort.isPieceResting /
                // REST_AFTER_MOVE_MS / REST_AFTER_JUMP_MS). Rather than a flat
                // tint for the whole cooldown, the tint's height counts down
                // with the remaining time - like a draining level, top edge
                // sinking toward the bottom of the cell - so the moment the
                // piece becomes movable again (tint fully drained) is visible
                // at a glance instead of the cooldown being an invisible timer.
                if (resting && restDuration > 0) {
                    long remaining = restUntil - now;
                    double remainingFraction = Math.max(0.0, Math.min(1.0, remaining / (double) restDuration));
                    int tintHeight = (int) Math.round(Board.CELL_SIZE * remainingFraction);
                    if (tintHeight > 0) {
                        Object previousAntialiasHint = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
                        // Antialiasing softens a flat rectangle's edges under the
                        // display-scale transform, which reads as the tint not
                        // lining up exactly with the checker square underneath -
                        // drawn crisp instead so its edges land exactly on the
                        // same pixels the square itself occupies.
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
                        g.setColor(RESTING_TINT);
                        g.fillRect(cellX, cellY + (Board.CELL_SIZE - tintHeight), Board.CELL_SIZE, tintHeight);
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previousAntialiasHint);
                    }
                }

                PieceSprites.State state;
                long elapsed;
                ActiveMove jump = jumpingAt.get(pos);
                if (jump != null) {
                    state = PieceSprites.State.JUMP;
                    long jumpStart = jump.getArrivalTimeMillis() - EnginePort.JUMP_DURATION;
                    elapsed = now - jumpStart;
                } else if (resting) {
                    state = (restDuration == EnginePort.REST_AFTER_JUMP_MS)
                            ? PieceSprites.State.SHORT_REST
                            : PieceSprites.State.LONG_REST;
                    elapsed = now - (restUntil - restDuration);
                } else {
                    state = PieceSprites.State.IDLE;
                    elapsed = now;
                }

                BufferedImage sprite = pieceSprites.get(piece.getColor(), piece.getType(), state, elapsed);
                if (sprite != null) {
                    paintSpriteCentered(g, sprite, cellX, cellY);
                }
            }
        }

        for (ActiveMove move : engine.getActiveMoves()) {
            // Jumps never leave their square - already drawn with the JUMP
            // state in the pass above, so they need no travel interpolation.
            if (move.isJump()) continue;

            double progress = travelProgress(move, now);

            int fromX = move.getFrom().getCol() * Board.CELL_SIZE;
            int fromY = move.getFrom().getRow() * Board.CELL_SIZE;
            int toX = move.getTo().getCol() * Board.CELL_SIZE;
            int toY = move.getTo().getRow() * Board.CELL_SIZE;

            int cellX = fromX + (int) Math.round((toX - fromX) * progress);
            int cellY = fromY + (int) Math.round((toY - fromY) * progress);

            int distance = chebyshevDistance(move.getFrom(), move.getTo());
            long moveStart = move.getArrivalTimeMillis() - (distance * EnginePort.MOVE_DURATION_PER_SQUARE);
            long elapsed = now - moveStart;

            BufferedImage sprite = pieceSprites.get(move.getPiece().getColor(), move.getPiece().getType(), PieceSprites.State.MOVE, elapsed);
            if (sprite != null) {
                paintSpriteCentered(g, sprite, cellX, cellY);
            }
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
     * bilinear quality Img.read's own resize path uses.
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

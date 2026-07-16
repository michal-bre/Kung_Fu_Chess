package org.example.view;

import org.example.engine.EnginePort;
import org.example.engine.GameSnapshot;
import org.example.engine.PieceSnapshot;
import org.example.engine.VisualState;
import org.example.model.Piece;
import org.example.model.Position;
import org.example.view.imglib.Img;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * View layer: the default Renderer - turns a GameSnapshot into pixels using
 * ONLY org.example.view.imglib.Img (per the design brief) and PieceSprites
 * (asset loading/caching). Never touches Board, EnginePort, or
 * GameController - everything it draws comes from the snapshot handed to
 * render().
 *
 * This is the concrete fix for architecture-review items 1, 2, 7 and 8:
 * - Item 1/7: no live Board/EnginePort/GameController references at all.
 * - Item 2: pixel positions and travel progress are read directly off each
 *   PieceSnapshot - never recomputed from distance/speed/start-time here.
 * - Item 3 (BoardView's half of it): the game-over caption reads
 *   snapshot.getWinner() directly - no board-scanning.
 * - Item 8: draws a selection-highlight border around
 *   snapshot.getSelectedPosition(), which previously had no visual feedback
 *   at all despite being tracked by the controller.
 */
public final class ImgRenderer implements Renderer {

    private static final String BOARD_IMAGE_ASSET = "assets/board.png";


    // How long a rejected-move flash stays visible, fading out over this
    // window rather than disappearing abruptly.
    private static final long REJECTION_FLASH_DURATION_MS = 400;

    private static final Color RESTING_TINT = new Color(248, 237, 20, 157);
    private static final Color REJECTION_TINT_BASE = new Color(220, 30, 30, 240);
    private static final Color SELECTION_BORDER = new Color(157, 248, 45, 221);
    private static final int SELECTION_BORDER_THICKNESS = 4;

    // Game-over caption palette - shares the warm-gold/cool-blue accent
    // language GamePanel uses for White/Black's score bar and history
    // panel, so the same "which side is this" visual cue reads correctly
    // even inside the rendered board image itself.
    private static final Color GAME_OVER_PANEL_BG = new Color(18, 18, 22, 235);
    private static final Color GAME_OVER_BORDER = new Color(198, 161, 91);
    private static final Color GAME_OVER_TITLE = new Color(240, 240, 245);
    private static final Color WHITE_WIN_ACCENT = new Color(230, 200, 120);
    private static final Color BLACK_WIN_ACCENT = new Color(120, 170, 230);
    private static final Color DRAW_ACCENT = new Color(205, 205, 210);

    private final int cellSize;
    private final PieceSprites pieceSprites = new PieceSprites();

    // Loaded and scaled once, on the first render() call, from whatever
    // board size that first snapshot reports - a game's board dimensions
    // never change mid-game, so this is safe to cache rather than reloading
    // the same file from disk on every frame.
    private Img cachedBoardBackground;

    public ImgRenderer(int cellSize) {
        this.cellSize = cellSize;
    }

    @Override
    public Img render(GameSnapshot snapshot) {
        int pixelWidth = snapshot.getBoardWidth() * cellSize;
        int pixelHeight = snapshot.getBoardHeight() * cellSize;

        Img canvas = boardBackground(pixelWidth, pixelHeight).copy();

        drawSelection(canvas, snapshot);
        drawPieces(canvas, snapshot);
        drawRejectionFeedback(canvas, snapshot);
        drawGameOver(canvas, snapshot);

        return canvas;
    }

    private Img boardBackground(int pixelWidth, int pixelHeight) {
        if (cachedBoardBackground == null) {
            // The board asset is assumed to be the checkerboard itself, edge
            // to edge, with no decorative border baked in - stretched to
            // exactly fill the pixel grid (aspect NOT preserved, keepAspect
            // = false) so it lines up with BoardMapper's row = y / cellSize,
            // col = x / cellSize math.
            cachedBoardBackground = new Img().read(
                    AssetPaths.resolve(BOARD_IMAGE_ASSET).getPath(),
                    new Dimension(pixelWidth, pixelHeight), false, null);
        }
        return cachedBoardBackground;
    }

    private void drawSelection(Img canvas, GameSnapshot snapshot) {
        Position selected = snapshot.getSelectedPosition();
        if (selected == null) return;

        int x = selected.getCol() * cellSize;
        int y = selected.getRow() * cellSize;
        int t = SELECTION_BORDER_THICKNESS;

        canvas.fillRect(x, y, cellSize, t, SELECTION_BORDER);
        canvas.fillRect(x, y + cellSize - t, cellSize, t, SELECTION_BORDER);
        canvas.fillRect(x, y, t, cellSize, SELECTION_BORDER);
        canvas.fillRect(x + cellSize - t, y, t, cellSize, SELECTION_BORDER);
    }

    private void drawPieces(Img canvas, GameSnapshot snapshot) {
        for (PieceSnapshot piece : snapshot.getPieces()) {
            int cellX = (int) Math.round(piece.getPixelX());
            int cellY = (int) Math.round(piece.getPixelY());

            drawRestingTint(canvas, piece, cellX, cellY);

            // PieceSprites.State and engine.VisualState share the exact same
            // constant names by design (IDLE/MOVE/JUMP/SHORT_REST/LONG_REST)
            // - see VisualState's class doc - so this mapping is safe as
            // long as the two stay in sync.
            PieceSprites.State state = PieceSprites.State.valueOf(piece.getVisualState().name());
            BufferedImage sprite = pieceSprites.get(piece.getColor(), piece.getType(), state, piece.getStateElapsedMillis());
            if (sprite != null) {
                drawSpriteCentered(canvas, sprite, cellX, cellY);
            }
        }
    }

    /**
     * A piece resting after its last action can't start a new move until it
     * stops resting - see EnginePort.isPieceResting/REST_AFTER_MOVE_MS/
     * REST_AFTER_JUMP_MS. Rather than a flat tint for the whole cooldown,
     * the tint's height counts down with the remaining time - like a
     * draining level, top edge sinking toward the bottom of the cell - so
     * the moment the piece becomes movable again (tint fully drained) is
     * visible at a glance instead of the cooldown being an invisible timer.
     */
    private void drawRestingTint(Img canvas, PieceSnapshot piece, int cellX, int cellY) {
        long restDuration;
        if (piece.getVisualState() == VisualState.SHORT_REST) {
            restDuration = EnginePort.REST_AFTER_JUMP_MS;
        } else if (piece.getVisualState() == VisualState.LONG_REST) {
            restDuration = EnginePort.REST_AFTER_MOVE_MS;
        } else {
            return;
        }
        if (restDuration <= 0) return;

        long remaining = restDuration - piece.getStateElapsedMillis();
        double remainingFraction = Math.max(0.0, Math.min(1.0, remaining / (double) restDuration));
        int tintHeight = (int) Math.round(cellSize * remainingFraction);
        if (tintHeight <= 0) return;

        canvas.fillRect(cellX, cellY + (cellSize - tintHeight), cellSize, tintHeight, RESTING_TINT);
    }

    /**
     * Briefly flashes red over a square whose move attempt was just
     * rejected. A rejected click is otherwise a total no-op - nothing on the
     * board changes - which is indistinguishable from "the click didn't
     * register at all" without some feedback. Fades out over
     * REJECTION_FLASH_DURATION_MS rather than disappearing on the next
     * frame.
     */
    private void drawRejectionFeedback(Img canvas, GameSnapshot snapshot) {
        Position rejected = snapshot.getRejectedPosition();
        if (rejected == null) return;

        long elapsed = snapshot.getGameTimeMillis() - snapshot.getRejectedAtMillis();
        if (elapsed < 0 || elapsed >= REJECTION_FLASH_DURATION_MS) return;

        float fade = 1f - (elapsed / (float) REJECTION_FLASH_DURATION_MS);
        int alpha = Math.round(160 * fade);
        Color tint = new Color(REJECTION_TINT_BASE.getRed(), REJECTION_TINT_BASE.getGreen(),
                REJECTION_TINT_BASE.getBlue(), alpha);

        canvas.fillRect(rejected.getCol() * cellSize, rejected.getRow() * cellSize, cellSize, cellSize, tint);
    }

    /**
     * Draws a "game over" caption once the snapshot reports the game has
     * ended. Reads snapshot.getWinner() directly - the engine tracks who
     * won explicitly at the moment a king is actually captured (see
     * MovementEngine/DefaultGameEngine) - this method never inspects piece
     * positions to guess.
     *
     * Rendered as a bordered card (not bare text over the dimmed board) so
     * it reads as a deliberate UI element: a bold "GAME OVER" title over a
     * smaller "<COLOR> WINS" subtitle in that side's own accent color
     * (or a neutral "DRAW" if there's no winner). Both lines are centered
     * using Img.textWidth's exact measurement rather than the old
     * characters-times-average-width guess, so the card is never
     * lopsided.
     */
    private void drawGameOver(Img canvas, GameSnapshot snapshot) {
        if (!snapshot.isGameOver()) return;

        int width = snapshot.getBoardWidth() * cellSize;
        int height = snapshot.getBoardHeight() * cellSize;

        canvas.fillRect(0, 0, width, height, new Color(0, 0, 0, 189));

        String title = "GAME OVER";
        boolean draw = snapshot.getWinner() == null;
        String subtitle = draw ? "DRAW" : snapshot.getWinner().name() + " WINS";
        Color subtitleColor = draw
                ? DRAW_ACCENT
                : (snapshot.getWinner() == Piece.Color.WHITE ? WHITE_WIN_ACCENT : BLACK_WIN_ACCENT);

        float titleSize = 4.0f;
        float subtitleSize = 2.4f;

        int titleWidth = canvas.textWidth(title, titleSize, true);
        int subtitleWidth = canvas.textWidth(subtitle, subtitleSize, true);
        int contentWidth = Math.max(titleWidth, subtitleWidth);

        int paddingX = 50;
        int paddingY = 32;
        int lineGap = 16;
        int titleHeight = Math.round(titleSize * 12);
        int subtitleHeight = Math.round(subtitleSize * 12);

        int panelWidth = Math.min(width - 24, contentWidth + paddingX * 2);
        int panelHeight = titleHeight + subtitleHeight + lineGap + paddingY * 2;
        int panelX = (width - panelWidth) / 2;
        int panelY = (height - panelHeight) / 2;

        canvas.fillRoundRect(panelX, panelY, panelWidth, panelHeight, 26, 26, GAME_OVER_PANEL_BG);
        canvas.drawRoundRect(panelX, panelY, panelWidth, panelHeight, 26, 26, GAME_OVER_BORDER, 3);

        int titleY = panelY + paddingY + titleHeight - 8;
        int titleX = panelX + (panelWidth - titleWidth) / 2;
        canvas.putText(title, titleX + 2, titleY + 2, titleSize, new Color(0, 0, 0, 140), true);
        canvas.putText(title, titleX, titleY, titleSize, GAME_OVER_TITLE, true);

        int subtitleY = titleY + lineGap + subtitleHeight - 6;
        int subtitleX = panelX + (panelWidth - subtitleWidth) / 2;
        canvas.putText(subtitle, subtitleX, subtitleY, subtitleSize, subtitleColor, true);
    }

    private void drawSpriteCentered(Img canvas, BufferedImage sprite, int cellX, int cellY) {
        int maxSize = cellSize - 2;

        double scale = Math.min(
                maxSize / (double) sprite.getWidth(),
                maxSize / (double) sprite.getHeight());
        int drawWidth = (int) Math.round(sprite.getWidth() * scale);
        int drawHeight = (int) Math.round(sprite.getHeight() * scale);

        int drawX = cellX + (cellSize - drawWidth) / 2;
        int drawY = cellY + (cellSize - drawHeight) / 2;

        BufferedImage toDraw = (drawWidth == sprite.getWidth() && drawHeight == sprite.getHeight())
                ? sprite
                : scaleTo(sprite, drawWidth, drawHeight);

        Img.wrap(toDraw).drawOn(canvas, drawX, drawY);
    }

    /**
     * Scales an already-loaded image to an exact target size, matching the
     * bilinear quality Img.read's own resize path uses. Img.drawOn draws at
     * native size with no scaling of its own, so sprites (already loaded and
     * cached by PieceSprites) are pre-scaled here before compositing.
     */
    private static BufferedImage scaleTo(BufferedImage src, int width, int height) {
        BufferedImage dst = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose();
        return dst;
    }
}

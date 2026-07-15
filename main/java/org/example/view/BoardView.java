package org.example.view;

import org.example.engine.GameSnapshot;
import org.example.view.imglib.Img;

import javax.swing.*;
import java.awt.*;
import java.util.function.Supplier;

/**
 * View layer: hosts the board inside a Swing component and draws whatever
 * Renderer.render(GameSnapshot) returns.
 *
 * This is the fix for architecture-review items 1, 2, 7 and 8: BoardView
 * previously held live references to Board, EnginePort AND GameController,
 * and had to re-derive pixel positions, visual states, and the game-over
 * winner from raw engine state on every repaint. It now holds neither - only
 * a Renderer (which itself only ever sees a GameSnapshot - see ImgRenderer)
 * and a Supplier<GameSnapshot> to pull the latest one from. This class's
 * only remaining job is Swing plumbing: sizing, scaling to fit the screen,
 * and painting whatever image the Renderer hands back - it is a thin host,
 * not a renderer itself.
 *
 * Display scaling: drawing happens in a fixed LOGICAL pixel space of exactly
 * boardWidthCells*cellSize x boardHeightCells*cellSize - the same space
 * BoardMapper's row = y / cellSize, col = x / cellSize math assumes. That
 * logical space can be larger than the screen (e.g. an 8-row board is 800px
 * tall before any window chrome, which some laptop/VM screens can't fit
 * alongside a title bar and taskbar), so the panel is scaled down to fit the
 * display's available work area via a single Graphics2D.scale() applied once
 * at the top of paintComponent. Mouse input has to be scaled back the other
 * way before it reaches the controller - see getScale() and
 * BoardInputListener.
 *
 * The scale computed in the constructor is only a first guess, made before
 * any native window exists: it estimates window-chrome size via
 * SCREEN_CHROME_MARGIN_PX because a title bar's real height (which varies
 * by OS, theme, and DPI scaling) isn't knowable yet. Implementing
 * ScreenFittable lets GameWindow correct that guess with the real numbers
 * once its frame actually exists - see GameWindow's class doc.
 */
public class BoardView extends JPanel implements ScreenFittable {

    // Reserves room around the scaled board for the window's title bar and
    // the OS taskbar/dock when computing how large the board can be drawn
    // without exceeding the screen - see computeDisplayScale.
    private static final int SCREEN_CHROME_MARGIN_PX = 96;

    private final Renderer renderer;
    private final Supplier<GameSnapshot> snapshotSupplier;
    private final int logicalWidth;
    private final int logicalHeight;

    // Not final: constrainToContentArea (called by GameWindow once the real
    // frame insets are known) may shrink this further after construction -
    // see the class doc and ScreenFittable.
    private double scale;

    public BoardView(Renderer renderer, Supplier<GameSnapshot> snapshotSupplier,
                      int boardWidthCells, int boardHeightCells, int cellSize) {
        this.renderer = renderer;
        this.snapshotSupplier = snapshotSupplier;

        this.logicalWidth = boardWidthCells * cellSize;
        this.logicalHeight = boardHeightCells * cellSize;
        this.scale = computeDisplayScale(logicalWidth, logicalHeight);

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
        // Everything the Renderer draws is in LOGICAL pixel coordinates;
        // this single transform is what makes the final on-screen result
        // fit the display. See the class doc.
        g2.scale(scale, scale);

        GameSnapshot snapshot = snapshotSupplier.get();
        Img frame = renderer.render(snapshot);
        g2.drawImage(frame.get(), 0, 0, null);
    }
}

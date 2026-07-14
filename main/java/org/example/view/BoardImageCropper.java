package org.example.view;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Detects the pixel bounding box of the actual 8x8 checkerboard playing
 * surface within a board image that may have a decorative border/frame
 * baked in around it (e.g. a wooden rim), and crops down to just that
 * region.
 *
 * BoardView needs the checker grid to occupy the WHOLE image it draws,
 * pixel-for-pixel, because it stretches that image to exactly
 * board.getWidth()*CELL_SIZE x board.getHeight()*CELL_SIZE and places every
 * piece using that same CELL_SIZE grid math. A board image with a border
 * baked in - its checker squares occupying only an inset sub-region, not
 * the full image - breaks that assumption: stretching the WHOLE image
 * (border included) makes the squares smaller than a cell and shifts them
 * away from where pieces get drawn, which is exactly "pieces not landing on
 * the squares".
 *
 * The border width isn't known ahead of time, so it's found here at
 * runtime: candidate margins (the SAME fraction on all four sides - a
 * "picture frame" style border is symmetric in every real asset this has
 * been tested against, and trying to fit each side independently turned
 * out to be a mistake, see below) are tested, and whichever one makes the
 * resulting 8x8 grid alternate most consistently between two colors - the
 * one property a real checkerboard has that a wood-grain frame around it
 * does not - wins.
 *
 * An earlier version of this class tried to fit the left/right/top/bottom
 * margins independently, on the theory that a decorative frame might not
 * be perfectly even. That backfired: scoring only 8 sample points per axis
 * mostly measures the TOTAL border width, not how it's split between the
 * two sides, so near-zero-on-one-side/all-on-the-other splits could score
 * just as well as the correct even split - which is exactly the lopsided
 * crop that showed up as a solid uncropped strip on one edge. A single
 * shared margin can't produce that failure mode, at the cost of assuming
 * the border really is even - true for every asset seen so far.
 *
 * Because many margins right around the true border width tend to tie for
 * the best score (the same effect above, just along one axis instead of
 * two), the midpoint of that tying range - not the smallest margin that
 * reaches it - is used as the final answer, so a thin sliver of leftover
 * border can't survive at one end of the tie.
 */
final class BoardImageCropper {

    private static final int SQUARES_PER_SIDE = 8;
    private static final double MAX_MARGIN_FRACTION = 0.30;
    private static final double MARGIN_STEP_FRACTION = 0.005;

    private BoardImageCropper() {
    }

    static BufferedImage cropToCheckerboard(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();

        int bestScore = -1;
        for (double margin = 0; margin <= MAX_MARGIN_FRACTION; margin += MARGIN_STEP_FRACTION) {
            int score = checkerboardScore(source, width, height, margin);
            if (score > bestScore) bestScore = score;
        }

        // Take the midpoint of every margin that reaches the best score,
        // rather than the first (smallest) one - see class doc.
        double firstMargin = -1;
        double lastMargin = -1;
        for (double margin = 0; margin <= MAX_MARGIN_FRACTION; margin += MARGIN_STEP_FRACTION) {
            int score = checkerboardScore(source, width, height, margin);
            if (score == bestScore) {
                if (firstMargin < 0) firstMargin = margin;
                lastMargin = margin;
            }
        }
        double bestMargin = (firstMargin + lastMargin) / 2.0;

        int marginXpx = (int) Math.round(width * bestMargin);
        int marginYpx = (int) Math.round(height * bestMargin);
        int gridWidth = width - 2 * marginXpx;
        int gridHeight = height - 2 * marginYpx;
        if (gridWidth <= 0 || gridHeight <= 0) return source;

        return source.getSubimage(marginXpx, marginYpx, gridWidth, gridHeight);
    }

    /**
     * How many of the 64 sampled cells match a perfect two-color
     * checkerboard pattern (whichever polarity fits better), for a grid
     * inset by this margin fraction on every side. The margin fraction is
     * applied independently to width and height (rather than assuming a
     * square source image), so a non-square board asset is still handled
     * correctly.
     */
    private static int checkerboardScore(BufferedImage source, int width, int height, double marginFraction) {
        int marginXpx = (int) Math.round(width * marginFraction);
        int marginYpx = (int) Math.round(height * marginFraction);
        int gridWidth = width - 2 * marginXpx;
        int gridHeight = height - 2 * marginYpx;
        if (gridWidth <= 0 || gridHeight <= 0) return 0;

        double cellWidth = gridWidth / (double) SQUARES_PER_SIDE;
        double cellHeight = gridHeight / (double) SQUARES_PER_SIDE;

        int[] luminance = new int[SQUARES_PER_SIDE * SQUARES_PER_SIDE];
        for (int row = 0; row < SQUARES_PER_SIDE; row++) {
            for (int col = 0; col < SQUARES_PER_SIDE; col++) {
                int x = marginXpx + (int) ((col + 0.5) * cellWidth);
                int y = marginYpx + (int) ((row + 0.5) * cellHeight);
                luminance[row * SQUARES_PER_SIDE + col] = luminanceOf(source.getRGB(x, y));
            }
        }

        // The median is a self-calibrating light/dark threshold: a real
        // checkerboard always has exactly 32 light and 32 dark squares out
        // of 64, so the median sits right between the two color clusters
        // regardless of the board's actual color tones.
        int median = median(luminance);

        int agree = 0;
        for (int row = 0; row < SQUARES_PER_SIDE; row++) {
            for (int col = 0; col < SQUARES_PER_SIDE; col++) {
                boolean isLight = luminance[row * SQUARES_PER_SIDE + col] >= median;
                boolean expectedLight = (row + col) % 2 == 0;
                if (isLight == expectedLight) agree++;
            }
        }

        // The polarity (which parity counts as "light") is arbitrary, so
        // score by whichever assignment fits better.
        return Math.max(agree, SQUARES_PER_SIDE * SQUARES_PER_SIDE - agree);
    }

    private static int luminanceOf(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (int) (0.2126 * r + 0.7152 * g + 0.0722 * b);
    }

    private static int median(int[] values) {
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}

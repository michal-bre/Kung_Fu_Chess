package org.example.view;

import org.example.model.Piece;
import org.example.view.imglib.Img;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;

/**
 * View layer: loads and caches one static sprite per (color, type)
 * combination, sourced from the vendored CTD26 "pieces1" art set (the
 * "idle" state's first frame), and prepares each one for compositing onto
 * the checkered board.
 *
 * Asset choice: CTD26 ships two sprite sets. pieces2 has proper transparent
 * (RGBA) PNGs, but its art is incomplete - several piece codes (KB, RW, RB,
 * PB) have no image files at all, in any state, so it cannot cover a full
 * starting position. pieces1 IS complete for all twelve codes, but its
 * renders are opaque (RGB, no alpha) on a flat white background. This class
 * makes pieces1 usable by keying the white background out to transparency
 * itself, using only standard JDK pixel access (BufferedImage.getRGB/
 * setRGB) - no third-party library, consistent with the design brief.
 *
 * Naive global "any near-white pixel becomes transparent" thresholding was
 * deliberately avoided: these renders use a flat highlight tone that is
 * itself very close to white inside the piece silhouette (a specular
 * highlight), so a global threshold would punch stray transparent flecks
 * into the piece body wherever that highlight appears. Instead, only the
 * near-white region that is reachable from the image's outer border by
 * flood fill is keyed out - i.e. the actual background - so enclosed
 * near-white pixels inside the piece are left alone.
 *
 * Separately, every pieces1 render also has a debug watermark burned into
 * the pixels - the state name and frame number ("idle" / "1") stamped in
 * solid blue with a green outline across the middle of the piece. The rest
 * of the artwork is strictly grayscale (white/black/gray only - every
 * sample is r==g==b), so the watermark is trivially distinguishable by
 * being the only saturated color in the image. It is removed BEFORE the
 * white-background key-out (a multi-source BFS inpaint: watermark pixels
 * are repeatedly replaced with the average of their already-resolved
 * neighbors, growing inward from the edge of the watermark), so that the
 * area it covers is reconstructed back to plain background/body color and
 * the subsequent white-keying step sees a normal image.
 */
final class PieceSprites {

    private static final String SPRITE_DIR = "assets/pieces/";

    // A pixel counts as "background white" if every channel is within this
    // distance of 255. The renders' body highlight tone (~230,230,230) sits
    // well outside this band, so it is never mistaken for background.
    private static final int WHITE_CHANNEL_FLOOR = 245;

    // A pixel counts as "watermark" if its channels disagree by more than
    // this much. Every non-watermark pixel in these renders is perfectly
    // neutral gray (r==g==b) regardless of how light or dark it is, so even
    // a small saturation reliably means "this is the blue/green stamp",
    // with no risk of catching real artwork.
    private static final int WATERMARK_SATURATION_THRESHOLD = 15;

    private final Map<Piece.Color, Map<Piece.Type, BufferedImage>> cache = new EnumMap<>(Piece.Color.class);

    /**
     * Returns the cached, cleaned-up sprite for this color/type, loading and
     * processing it from disk on first use.
     */
    BufferedImage get(Piece.Color color, Piece.Type type) {
        return cache
                .computeIfAbsent(color, c -> new EnumMap<>(Piece.Type.class))
                .computeIfAbsent(type, t -> loadAndClean(color, type));
    }

    private BufferedImage loadAndClean(Piece.Color color, Piece.Type type) {
        char colorLetter = (color == Piece.Color.WHITE) ? 'W' : 'B';
        String fileName = SPRITE_DIR + type.getSymbol() + colorLetter + ".png";
        File file = AssetPaths.resolve(fileName);

        BufferedImage raw = new Img().read(file.getPath()).get();
        BufferedImage dewatermarked = removeColorWatermark(raw);
        return keyOutBorderConnectedWhite(dewatermarked);
    }

    /**
     * Replaces every saturated ("watermark") pixel with the average color of
     * its already-resolved neighbors, propagating inward from the edge of
     * the watermark until none remain. A multi-source BFS: every unresolved
     * pixel touching a resolved one is resolvable immediately, which is
     * exactly the queue-driven flood-fill shape used below in
     * keyOutBorderConnectedWhite, just growing "inward" instead of
     * "outward from the border".
     */
    private static BufferedImage removeColorWatermark(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();

        int[][] color = new int[height][width];
        boolean[][] unresolved = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = src.getRGB(x, y);
                color[y][x] = argb;
                unresolved[y][x] = isSaturated(argb);
            }
        }

        boolean[][] queued = new boolean[height][width];
        Deque<int[]> queue = new ArrayDeque<>();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (unresolved[y][x] && hasResolvedNeighbor(unresolved, x, y, width, height)) {
                    queue.add(new int[]{x, y});
                    queued[y][x] = true;
                }
            }
        }

        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            int x = p[0], y = p[1];

            int sumR = 0, sumG = 0, sumB = 0, count = 0;
            for (int[] n : neighbors(x, y, width, height)) {
                if (!unresolved[n[1]][n[0]]) {
                    int c = color[n[1]][n[0]];
                    sumR += (c >> 16) & 0xFF;
                    sumG += (c >> 8) & 0xFF;
                    sumB += c & 0xFF;
                    count++;
                }
            }
            // count is always >= 1: this pixel was only enqueued because it
            // had a resolved neighbor, and resolved pixels never revert.
            color[y][x] = 0xFF000000 | ((sumR / count) << 16) | ((sumG / count) << 8) | (sumB / count);
            unresolved[y][x] = false;

            for (int[] n : neighbors(x, y, width, height)) {
                int nx = n[0], ny = n[1];
                if (unresolved[ny][nx] && !queued[ny][nx]) {
                    queue.add(new int[]{nx, ny});
                    queued[ny][nx] = true;
                }
            }
        }

        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, color[y][x]);
            }
        }
        return out;
    }

    private static boolean hasResolvedNeighbor(boolean[][] unresolved, int x, int y, int width, int height) {
        for (int[] n : neighbors(x, y, width, height)) {
            if (!unresolved[n[1]][n[0]]) return true;
        }
        return false;
    }

    private static int[][] neighbors(int x, int y, int width, int height) {
        java.util.List<int[]> result = new java.util.ArrayList<>(4);
        if (x + 1 < width) result.add(new int[]{x + 1, y});
        if (x - 1 >= 0) result.add(new int[]{x - 1, y});
        if (y + 1 < height) result.add(new int[]{x, y + 1});
        if (y - 1 >= 0) result.add(new int[]{x, y - 1});
        return result.toArray(new int[0][]);
    }

    private static boolean isSaturated(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return (max - min) > WATERMARK_SATURATION_THRESHOLD;
    }

    private static BufferedImage keyOutBorderConnectedWhite(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();

        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, 0xFF000000 | (src.getRGB(x, y) & 0x00FFFFFF));
            }
        }

        boolean[][] visited = new boolean[height][width];
        Deque<int[]> frontier = new ArrayDeque<>();

        for (int x = 0; x < width; x++) {
            frontier.add(new int[]{x, 0});
            frontier.add(new int[]{x, height - 1});
        }
        for (int y = 0; y < height; y++) {
            frontier.add(new int[]{0, y});
            frontier.add(new int[]{width - 1, y});
        }

        while (!frontier.isEmpty()) {
            int[] p = frontier.poll();
            int x = p[0], y = p[1];

            if (x < 0 || x >= width || y < 0 || y >= height || visited[y][x]) continue;
            visited[y][x] = true;

            if (!isNearWhite(out.getRGB(x, y))) continue;

            out.setRGB(x, y, 0x00000000);

            frontier.add(new int[]{x + 1, y});
            frontier.add(new int[]{x - 1, y});
            frontier.add(new int[]{x, y + 1});
            frontier.add(new int[]{x, y - 1});
        }

        return out;
    }

    private static boolean isNearWhite(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return r >= WHITE_CHANNEL_FLOOR && g >= WHITE_CHANNEL_FLOOR && b >= WHITE_CHANNEL_FLOOR;
    }
}

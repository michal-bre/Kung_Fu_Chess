package org.example.view;

import org.example.model.Piece;
import org.example.view.imglib.Img;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * View layer: loads and caches one animation-frame sequence per
 * (color, type, state) combination from
 * assets/pieces/&lt;TypeColor&gt;/states/&lt;state&gt;/sprites/&lt;n&gt;.png
 * (e.g. assets/pieces/KW/states/move/sprites/1.png), and prepares each frame
 * for compositing onto the checkered board.
 *
 * State names (idle/move/jump/short_rest/long_rest - see the State enum)
 * mirror EnginePort's own move/jump/rest model 1:1 (MovementEngine already
 * distinguishes REST_AFTER_MOVE_MS -> long_rest from REST_AFTER_JUMP_MS ->
 * short_rest - see its markResting call sites), so BoardView can derive
 * which state to render straight from engine queries, with no separate
 * view-side state machine.
 *
 * Sprites are expected to normally be well-formed, pre-cut RGBA PNGs (a
 * real alpha channel already separating the piece from its background) -
 * that's the common case, and such an image is used completely as-is, with
 * zero processing. See loadAndClean/hasRealTransparency.
 *
 * The one exception is a specific legacy asset shape this class also still
 * supports: CTD26's vendored "pieces1" set, whose renders are fully OPAQUE
 * (no alpha at all) on a flat white background, AND have a debug watermark
 * (the state name and frame number, e.g. "idle"/"1") stamped in solid blue
 * with a green outline across the middle of the piece. For an image with no
 * real transparency, this class assumes it's that shape and cleans it up:
 *
 * 1. Watermark removal: the rest of a pieces1 render is strictly grayscale
 *    (white/black/gray only - every non-watermark sample has r==g==b), so
 *    the watermark is trivially distinguishable as the only saturated color
 *    in the image. Removed via a multi-source BFS inpaint - watermarked
 *    pixels are repeatedly replaced with the average of their already-
 *    resolved neighbors, growing inward from the edge of the watermark.
 * 2. White-background key-out: only the near-white region reachable from
 *    the image's outer border by flood fill is made transparent - i.e. the
 *    actual background - not a naive global "any near-white pixel"
 *    threshold, which would also punch holes in the specular highlight
 *    tone inside the piece body that happens to sit close to white too.
 *
 * Both steps use only standard JDK pixel access (BufferedImage.getRGB/
 * setRGB) - no third-party library, consistent with the design brief - and
 * neither ever runs on an image that already has real transparency, since
 * both would corrupt one (watermark-removal would treat the piece's own
 * color as "watermark" almost everywhere, and forcing full opacity on every
 * pixel would erase whatever shape the original alpha channel cut out).
 *
 * Missing sprites are handled gracefully rather than crashing the game: if
 * a piece has no frames at all for the requested state, get() falls back
 * through FALLBACK_ORDER to whatever state that piece DOES have frames for;
 * if it has no frames in any state, get() returns null and the caller
 * (BoardView) simply skips drawing that piece for this frame instead of
 * throwing.
 */
final class PieceSprites {

    /** One animation state per states/&lt;folderName&gt; sprite folder. */
    enum State {
        IDLE("idle"), MOVE("move"), JUMP("jump"), SHORT_REST("short_rest"), LONG_REST("long_rest");

        final String folderName;

        State(String folderName) {
            this.folderName = folderName;
        }
    }

    private static final String SPRITE_DIR = "assets/pieces/";

    // How long each animation frame is shown before advancing to the next
    // one, looping back to frame 0 once the sequence ends. A state with
    // only a single sprite (common in the current asset set) is unaffected
    // by this - it always just resolves to that one frame.
    private static final long FRAME_DURATION_MILLIS = 120;

    // If the requested state has no frames for this piece, fall back
    // through these states in order rather than rendering nothing. IDLE
    // first, as the calmest/most representative pose; the rest ensures a
    // piece with frames in ANY state is still drawn as something.
    private static final State[] FALLBACK_ORDER = {
            State.IDLE, State.LONG_REST, State.SHORT_REST, State.MOVE, State.JUMP
    };

    // A pixel counts as "background white" if every channel is within this
    // distance of 255. The legacy renders' body highlight tone
    // (~230,230,230) sits well outside this band, so it is never mistaken
    // for background.
    private static final int WHITE_CHANNEL_FLOOR = 245;

    // A pixel counts as "watermark" if its channels disagree by more than
    // this much. Every non-watermark pixel in the legacy renders is
    // perfectly neutral gray (r==g==b) regardless of how light or dark it
    // is, so even a small saturation reliably means "this is the blue/green
    // stamp", with no risk of catching real artwork - in THOSE renders only;
    // this whole path is skipped for anything with real transparency.
    private static final int WATERMARK_SATURATION_THRESHOLD = 15;

    private final Map<Piece.Color, Map<Piece.Type, Map<State, List<BufferedImage>>>> cache = new EnumMap<>(Piece.Color.class);

    /**
     * Returns the animation frame to draw for this piece right now, given
     * how long (in milliseconds) it has continuously been in {@code state}.
     * elapsedMillis is simply divided into FRAME_DURATION_MILLIS-sized
     * steps and wrapped to the available frame count, so the animation
     * loops for as long as the piece remains in that state - the caller
     * does not need to track "which frame was last shown" itself.
     *
     * Returns null if this piece has no frames in any state at all; the
     * caller should skip drawing that piece for this frame rather than
     * treat it as an error.
     */
    BufferedImage get(Piece.Color color, Piece.Type type, State state, long elapsedMillis) {
        List<BufferedImage> frames = framesFor(color, type, state);

        if (frames.isEmpty()) {
            for (State fallback : FALLBACK_ORDER) {
                if (fallback == state) continue;
                frames = framesFor(color, type, fallback);
                if (!frames.isEmpty()) break;
            }
        }

        if (frames.isEmpty()) return null;

        long safeElapsed = Math.max(0, elapsedMillis);
        int index = (int) ((safeElapsed / FRAME_DURATION_MILLIS) % frames.size());
        return frames.get(index);
    }

    private List<BufferedImage> framesFor(Piece.Color color, Piece.Type type, State state) {
        return cache
                .computeIfAbsent(color, c -> new EnumMap<>(Piece.Type.class))
                .computeIfAbsent(type, t -> new EnumMap<>(State.class))
                .computeIfAbsent(state, s -> loadFrames(color, type, s));
    }

    private List<BufferedImage> loadFrames(Piece.Color color, Piece.Type type, State state) {
        char colorLetter = (color == Piece.Color.WHITE) ? 'W' : 'B';
        String statePath = SPRITE_DIR + type.getSymbol() + colorLetter + "/states/" + state.folderName + "/sprites";
        File dir = AssetPaths.resolveDirOrNull(statePath);
        if (dir == null) return Collections.emptyList();

        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (files == null || files.length == 0) return Collections.emptyList();

        // Sort by the numeric value of the filename ("2.png" before
        // "10.png"), not lexicographically, so frames play back in the
        // order they were authored regardless of digit count.
        Arrays.sort(files, Comparator.comparingInt(PieceSprites::leadingNumber));

        List<BufferedImage> frames = new ArrayList<>(files.length);
        for (File file : files) {
            frames.add(loadAndClean(file));
        }
        return frames;
    }

    private static int leadingNumber(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        try {
            return Integer.parseInt(stem);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private BufferedImage loadAndClean(File file) {
        BufferedImage raw = new Img().read(file.getPath()).get();

        if (hasRealTransparency(raw)) {
            return raw;
        }

        BufferedImage dewatermarked = removeColorWatermark(raw);
        return keyOutBorderConnectedWhite(dewatermarked);
    }

    /**
     * True if this image already has at least one non-fully-opaque pixel -
     * i.e. it's a normal pre-cut sprite that needs no further processing.
     * A fully opaque image (no alpha channel at all, or an alpha channel
     * that's 0xFF everywhere) is what triggers the legacy pieces1 cleanup
     * path instead.
     */
    private static boolean hasRealTransparency(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) return false;

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0xFF) {
                    return true;
                }
            }
        }
        return false;
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
        List<int[]> result = new ArrayList<>(4);
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

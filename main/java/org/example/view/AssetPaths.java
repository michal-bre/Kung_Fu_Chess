package org.example.view;

import java.io.File;

/**
 * View layer: resolves the on-disk location of view assets (board/piece
 * images) supplied under the project's assets/ directory.
 *
 * org.example.view.imglib.Img loads images via a plain filesystem File
 * path (it has no classpath/resource-stream support), and this project has
 * no build step that copies resources onto the classpath - so assets are
 * simply shipped as files under <project root>/assets and located here by
 * walking up from the JVM's working directory. This keeps the view layer
 * working whether it's launched with the working directory set to the
 * project root (the common case) or to a module subfolder (e.g. main/),
 * without hard-coding an absolute path.
 */
final class AssetPaths {

    private AssetPaths() {
    }

    /**
     * Resolves a path like "assets/board.png" to an existing file, searching
     * the current working directory and a few parent directories.
     *
     * @throws IllegalStateException if the asset cannot be found anywhere in
     *                                the search path, with the list of
     *                                locations that were tried.
     */
    static File resolve(String relativePath) {
        File dir = new File(".").getAbsoluteFile();
        StringBuilder tried = new StringBuilder();

        for (int depth = 0; depth < 5 && dir != null; depth++) {
            File candidate = new File(dir, relativePath);
            tried.append(candidate.getPath()).append(System.lineSeparator());
            if (candidate.isFile()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }

        throw new IllegalStateException(
                "Could not locate view asset \"" + relativePath + "\". Tried:" +
                System.lineSeparator() + tried +
                "Make sure the project's assets/ directory is present and the " +
                "application is run from within the project tree.");
    }

    /**
     * Same upward search as resolve(), but for a directory, and returns null
     * instead of throwing when nothing is found. Used for per-state sprite
     * folders (assets/pieces/&lt;TypeColor&gt;/states/&lt;state&gt;/sprites/),
     * where a missing or empty folder is an expected, recoverable case (that
     * animation state simply has no frames yet) rather than a fatal
     * misconfiguration - unlike the board image or a legacy single-file
     * sprite, which resolve() correctly treats as required.
     */
    static File resolveDirOrNull(String relativePath) {
        File dir = new File(".").getAbsoluteFile();

        for (int depth = 0; depth < 5 && dir != null; depth++) {
            File candidate = new File(dir, relativePath);
            if (candidate.isDirectory()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }

        return null;
    }
}

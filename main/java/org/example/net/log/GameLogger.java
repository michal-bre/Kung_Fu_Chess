package org.example.net.log;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Phase 5's "dual-side logging": a small, dependency-free timestamped
 * logger, used by BOTH GameServer/Room (the server side) and GameClient
 * (the client side) - "dual-side" specifically meaning each side keeps its
 * OWN independent log of what IT saw happen, from its own vantage point.
 * That's deliberate: the server's log says what it sent and when; the
 * client's log says what it received and when. When something looks wrong
 * over the network, having both sides' independent records side by side is
 * what actually lets you tell whether the bug is in what the server sent,
 * what got lost/reordered in transit, or how the client interpreted what it
 * received - a single shared log (or trusting only one side's account)
 * can't distinguish those.
 *
 * Every line is written to BOTH stdout/stderr (so interactive runs still
 * see it live, matching every other class in this project) and appended to
 * a per-session file under logs/ - a plain, greppable text format on
 * purpose, not JSON or anything structured, since these logs exist to be
 * read by a human debugging a specific session, not machine-parsed.
 */
public final class GameLogger {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_SUFFIX_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final PrintWriter fileWriter;
    private final String label;

    private GameLogger(PrintWriter fileWriter, String label) {
        this.fileWriter = fileWriter;
        this.label = label;
    }

    /**
     * Creates a logger writing to {@code logs/<filePrefix>-<timestamp>.log}
     * (the logs/ directory is created if it doesn't exist yet). {@code label}
     * is prefixed onto every line written through this instance (e.g. the
     * room id on the server side, the username on the client side), so
     * interleaved console output stays attributable at a glance.
     *
     * Falls back to a console-only, no-file logger (rather than throwing) if
     * the logs/ directory can't be created or opened for writing - a
     * misbehaving log file must never be the reason a game session can't
     * start.
     */
    public static GameLogger create(String filePrefix, String label) {
        try {
            Path logsDir = Paths.get("logs");
            Files.createDirectories(logsDir);
            String fileName = filePrefix + "-" + LocalDateTime.now().format(FILE_SUFFIX_FORMAT) + ".log";
            PrintWriter writer = new PrintWriter(Files.newBufferedWriter(logsDir.resolve(fileName)), true);
            return new GameLogger(writer, label);
        } catch (IOException | UncheckedIOException e) {
            System.err.println("[GameLogger] could not open a log file for '" + filePrefix
                    + "' (" + e.getMessage() + ") - continuing with console-only logging.");
            return new GameLogger(null, label);
        }
    }

    /** Logs one line, timestamped and labeled, to the console and (if available) this logger's file. */
    public void log(String message) {
        String line = "[" + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "] [" + label + "] " + message;
        System.out.println(line);
        if (fileWriter != null) {
            fileWriter.println(line);
        }
    }
}

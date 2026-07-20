package org.example.bus;

import org.example.controller.MoveHistoryEntry;

/**
 * Published once per accepted move, right when InteractionHandler records
 * it in the move history - carries the same MoveHistoryEntry the history
 * table stores, plus an isCapture flag the entry itself doesn't expose
 * (only baked into its notation string, e.g. "Nxe5") so a subscriber like
 * SoundPlayer can pick a different sound for a capture without re-parsing
 * notation text.
 */
public final class MoveLoggedEvent {
    private final MoveHistoryEntry entry;
    private final boolean capture;

    public MoveLoggedEvent(MoveHistoryEntry entry, boolean capture) {
        this.entry = entry;
        this.capture = capture;
    }

    public MoveHistoryEntry getEntry() {
        return entry;
    }

    public boolean isCapture() {
        return capture;
    }
}

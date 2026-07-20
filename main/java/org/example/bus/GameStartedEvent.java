package org.example.bus;

/**
 * Published once, when a MovementEngine is constructed - in today's local
 * wiring, engine construction and "the game is now playable" are the same
 * moment. A later networked/room-based server can publish this at its own
 * more meaningful moment instead (e.g. once a room has both a White and a
 * Black player) without MovementEngine needing to change at all - nothing
 * about this event ties it to being raised from the engine specifically.
 */
public final class GameStartedEvent {
}

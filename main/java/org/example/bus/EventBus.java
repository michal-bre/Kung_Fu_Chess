package org.example.bus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A minimal, thread-safe publish/subscribe bus: any part of the system can
 * publish an event object, and any part can subscribe to a specific event
 * type, without either side knowing about the other.
 *
 * This replaces the "everything polls everything" pattern the local game
 * used until now - GamePanel's old refresh() ran on every GameLoop tick and
 * re-diffed gameController.getScore()/getMoveHistory() to work out what had
 * changed. The engine/controller layer now PUSHES a discrete event exactly
 * once, at the moment something worth telling the outside world about
 * actually happens (a score changed, a move was logged, a jump landed, the
 * game started/ended) - subscribers react only when there's really
 * something new, instead of re-deriving "what changed since last time"
 * themselves every 16ms.
 *
 * Deliberately generic and not chess-specific: nothing in this class
 * mentions Piece, Board, or GameSnapshot, so the exact same EventBus can be
 * reused unchanged once a networked server needs to fan the same events out
 * to remote clients - a WebSocket broadcaster is just another subscriber.
 *
 * Thread-safe by design even though today's only publisher (the Swing
 * javax.swing.Timer-driven GameLoop) fires everything on the EDT: a future
 * WebSocket server will publish from network I/O threads, and this bus
 * needs to already be correct for that without a rewrite.
 */
public final class EventBus {

    private final Map<Class<?>, List<Consumer<Object>>> subscribersByType = new ConcurrentHashMap<>();

    /**
     * Registers {@code listener} to be called with every future event whose
     * runtime type is exactly {@code eventType} - not for subtypes or
     * supertypes; each event class is its own topic. Returns nothing to
     * unsubscribe with, on purpose: every current subscriber (GamePanel,
     * SoundPlayer, ...) lives exactly as long as the bus itself, so there
     * has been no need yet for unsubscription - that can be added
     * additively if/when a subscriber with a shorter lifetime shows up
     * (e.g. a per-connection WebSocket session in the networked server).
     */
    @SuppressWarnings("unchecked")
    public <T> void subscribe(Class<T> eventType, Consumer<T> listener) {
        subscribersByType
                .computeIfAbsent(eventType, type -> new CopyOnWriteArrayList<>())
                .add((Consumer<Object>) listener);
    }

    /**
     * Delivers {@code event} synchronously, in subscription order, to every
     * listener registered for its exact runtime type. A listener that
     * throws does not prevent the remaining listeners from still running -
     * one broken subscriber (e.g. a sound driver failing on a headless
     * machine) must never be able to stop the score label or move-log
     * table from updating.
     */
    public void publish(Object event) {
        List<Consumer<Object>> listeners = subscribersByType.get(event.getClass());
        if (listeners == null) return;

        for (Consumer<Object> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                System.err.println("EventBus subscriber threw for " + event.getClass().getSimpleName() + ": " + e);
            }
        }
    }
}

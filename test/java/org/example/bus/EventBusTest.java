package org.example.bus;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Pure pub/sub mechanics for EventBus itself - subscription, delivery order,
 * exact-type matching, and exception isolation - with no engine, controller,
 * or game domain objects involved at all. EventBusIntegrationTest (in
 * org.example) already locks in that the real engine actually PUBLISHES
 * through this bus at the right moments; this file is the "publish/subscribe
 * mechanics work in isolation" coverage that class's own doc comment
 * explicitly called out as not needing its own test "yet" - Phase 6's
 * integration pass is exactly the point at which that became worth adding,
 * now that the bus also carries Room's SCORE/MOVE_LOG/GAME_STARTED/GAME_ENDED
 * broadcasts over the network, not just local Swing UI updates.
 */
public class EventBusTest {

    /** Two unrelated leaf event types - deliberately not chess/game domain classes, so these tests exercise EventBus generically. */
    private static class AlphaEvent {
        final String payload;
        AlphaEvent(String payload) { this.payload = payload; }
    }

    private static final class BetaEvent {
    }

    /** A subclass of AlphaEvent, used only to prove the bus keys subscriptions by *exact* runtime type, not by assignability. */
    private static final class AlphaSubEvent extends AlphaEvent {
        AlphaSubEvent(String payload) { super(payload); }
    }

    @Test
    public void aSubscriberReceivesAnEventPublishedAfterItSubscribed() {
        EventBus bus = new EventBus();
        List<AlphaEvent> received = new ArrayList<>();
        bus.subscribe(AlphaEvent.class, received::add);

        bus.publish(new AlphaEvent("hello"));

        assertEquals(1, received.size());
        assertEquals("hello", received.get(0).payload);
    }

    @Test
    public void multipleSubscribersToTheSameTypeAllReceiveItInSubscriptionOrder() {
        EventBus bus = new EventBus();
        List<String> callOrder = new ArrayList<>();
        bus.subscribe(AlphaEvent.class, e -> callOrder.add("first"));
        bus.subscribe(AlphaEvent.class, e -> callOrder.add("second"));
        bus.subscribe(AlphaEvent.class, e -> callOrder.add("third"));

        bus.publish(new AlphaEvent("x"));

        assertEquals(Arrays.asList("first", "second", "third"), callOrder);
    }

    @Test
    public void aSubscriberOnlyReceivesEventsOfItsExactRegisteredType() {
        // A subclass instance must NOT reach a supertype's subscriber - each
        // event class is its own topic (see EventBus's class doc), which
        // matters in practice because GameEndedEvent/GameStartedEvent/etc.
        // are all unrelated leaf classes precisely so no such ambiguity can
        // arise in production; this test proves the mechanism itself, not
        // just that today's event classes happen to avoid the question.
        EventBus bus = new EventBus();
        List<AlphaEvent> received = new ArrayList<>();
        bus.subscribe(AlphaEvent.class, received::add);

        bus.publish(new AlphaSubEvent("subtype"));

        assertTrue("a subscriber registered for the supertype must not receive a subtype event",
                received.isEmpty());
    }

    @Test
    public void subscribingToOneEventTypeDoesNotReceiveAnUnrelatedType() {
        EventBus bus = new EventBus();
        List<Object> received = new ArrayList<>();
        bus.subscribe(AlphaEvent.class, received::add);

        bus.publish(new BetaEvent());

        assertTrue(received.isEmpty());
    }

    @Test
    public void aSubscriberThatThrowsDoesNotPreventLaterSubscribersFromRunning() {
        EventBus bus = new EventBus();
        List<String> received = new ArrayList<>();
        bus.subscribe(AlphaEvent.class, e -> { throw new RuntimeException("boom"); });
        bus.subscribe(AlphaEvent.class, e -> received.add("still ran"));

        bus.publish(new AlphaEvent("x")); // must not propagate the subscriber's exception

        assertEquals(Arrays.asList("still ran"), received);
    }

    @Test
    public void publishingWithNoSubscribersForThatTypeIsANoOp() {
        EventBus bus = new EventBus();

        bus.publish(new AlphaEvent("nobody is listening")); // must not throw
    }
}

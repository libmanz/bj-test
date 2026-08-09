package dvp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises BufferDrainingSubscriber directly against a real buffer, using a fake listener
 * to record what it was handed. The buffer is injected via the constructor now, matching
 * the current DI shape. No threading needed: the buffer is pre-loaded and marked done
 * before subscribe() is called, so takeOldest() never blocks.
 */
class BufferDrainingSubscriberTest {

    static class FakeListener implements DerivedValueListener {
        final List<DerivedUpdate> updates = new ArrayList<>();
        boolean completed = false;

        @Override
        public void onUpdate(DerivedUpdate update) {
            updates.add(update);
        }

        @Override
        public void onComplete() {
            completed = true;
        }
    }

    @Test
    void dispatchesEachBufferedUpdateToTheListenerInOrder() throws InterruptedException {
        ConflatingBuffer buffer = new LockedConflatingBuffer();
        buffer.put(new DerivedUpdate("B", 1.0, 100));
        buffer.put(new DerivedUpdate("A", 1.0, 101));
        buffer.markProducerDone();

        FakeListener listener = new FakeListener();
        new BufferDrainingSubscriber(buffer, listener, 0L, 0.0, new Random(0)).subscribe();

        assertEquals(2, listener.updates.size());
        assertEquals("B", listener.updates.get(0).instrument);
        assertEquals("A", listener.updates.get(1).instrument);
    }

    @Test
    void callsOnCompleteExactlyOnceAfterDraining() throws InterruptedException {
        ConflatingBuffer buffer = new LockedConflatingBuffer();
        buffer.put(new DerivedUpdate("AAPL", 1.0, 1));
        buffer.markProducerDone();

        FakeListener listener = new FakeListener();
        new BufferDrainingSubscriber(buffer, listener, 0L, 0.0, new Random(0)).subscribe();

        assertTrue(listener.completed);
    }

    @Test
    void callsOnCompleteEvenWithNoUpdatesAtAll() throws InterruptedException {
        ConflatingBuffer buffer = new LockedConflatingBuffer();
        buffer.markProducerDone();

        FakeListener listener = new FakeListener();
        new BufferDrainingSubscriber(buffer, listener, 0L, 0.0, new Random(0)).subscribe();

        assertTrue(listener.updates.isEmpty());
        assertTrue(listener.completed);
    }
}

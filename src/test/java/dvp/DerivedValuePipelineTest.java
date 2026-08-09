package dvp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies DerivedValuePipeline's orchestration itself -- both threads actually run, both
 * get joined, shutdown drains correctly, and the buffer's conflation/fairness contract
 * survives the trip through the pipeline -- using trivial fake MarketDataPublisher /
 * DerivedValueSubscriber implementations. No file I/O, no real timing.
 *
 * DerivedValuePipeline holds a DerivedValueComputer reference to forward into
 * publisher.publish(computer). FakePublisher here doesn't use one at all -- it pushes
 * directly into its own buffer reference -- but a real DerivedValueComputer still has to be
 * constructed to satisfy the pipeline's constructor. AuditLog is an interface specifically
 * so that requirement can be satisfied with NoOpAuditLog rather than a real file-writing
 * CsvAuditLog, keeping these tests fully in-memory despite the coupling.
 */
class DerivedValuePipelineTest {

    /** Pushes a fixed list of updates into a constructor-injected buffer, then marks the
     *  producer done. Ignores the DerivedValueComputer passed to publish() entirely. */
    static class FakePublisher implements MarketDataPublisher {
        private final ConflatingBuffer buffer;
        private final List<DerivedUpdate> updatesToPublish;

        FakePublisher(ConflatingBuffer buffer, List<DerivedUpdate> updatesToPublish) {
            this.buffer = buffer;
            this.updatesToPublish = updatesToPublish;
        }

        @Override
        public void publish(DerivedValueComputer computer) {
            for (DerivedUpdate update : updatesToPublish) {
                buffer.put(update);
            }
            buffer.markProducerDone();
        }
    }

    /** Drains a constructor-injected buffer into an in-memory list instead of writing a
     *  file. Confined to the subscriber thread while running; safely visible to the test
     *  thread afterward via DerivedValuePipeline#run()'s Thread.join(). */
    static class FakeSubscriber implements DerivedValueSubscriber {
        private final ConflatingBuffer buffer;
        final List<DerivedUpdate> received = new ArrayList<>();

        FakeSubscriber(ConflatingBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void subscribe() throws InterruptedException {
            while (true) {
                DerivedUpdate update = buffer.takeOldest();
                if (update == null) {
                    break;
                }
                received.add(update);
            }
        }
    }

    /** A throwaway DerivedValueComputer to satisfy the pipeline's constructor -- FakePublisher
     *  never touches it. NoOpAuditLog keeps this fully in-memory, no file I/O. */
    private static DerivedValueComputer unusedComputer() {
        return new DerivedValueComputer(new LockedConflatingBuffer(), new NoOpAuditLog());
    }

    @Test
    void runsPublisherAndSubscriberToCompletion() throws InterruptedException {
        ConflatingBuffer buffer = new LockedConflatingBuffer();
        List<DerivedUpdate> toPublish = List.of(
                new DerivedUpdate("AAPL", 1.0, 100),
                new DerivedUpdate("MSFT", 2.0, 200));

        FakePublisher publisher = new FakePublisher(buffer, toPublish);
        FakeSubscriber subscriber = new FakeSubscriber(buffer);

        new DerivedValuePipeline(publisher, subscriber, unusedComputer()).run();

        assertEquals(2, subscriber.received.size());
        assertTrue(subscriber.received.stream().anyMatch(u -> u.instrument.equals("AAPL") && u.value == 1.0));
        assertTrue(subscriber.received.stream().anyMatch(u -> u.instrument.equals("MSFT") && u.value == 2.0));
    }

    @Test
    void preservesConflationAndFairnessThroughThePipeline() throws InterruptedException {
        // B goes dirty first; A updates several times after -- the pipeline should still
        // deliver B first and only A's LATEST value, same contract as the buffer alone.
        ConflatingBuffer buffer = new LockedConflatingBuffer();
        List<DerivedUpdate> toPublish = List.of(
                new DerivedUpdate("B", 1.0, 100),
                new DerivedUpdate("A", 1.0, 101),
                new DerivedUpdate("A", 2.0, 102),
                new DerivedUpdate("A", 3.0, 103));

        FakePublisher publisher = new FakePublisher(buffer, toPublish);
        FakeSubscriber subscriber = new FakeSubscriber(buffer);

        new DerivedValuePipeline(publisher, subscriber, unusedComputer()).run();

        assertEquals(2, subscriber.received.size(), "A's 3 updates should conflate into 1");
        assertEquals("B", subscriber.received.get(0).instrument);
        assertEquals("A", subscriber.received.get(1).instrument);
        assertEquals(3.0, subscriber.received.get(1).value, 1e-9);
    }

    @Test
    void runsCleanlyWithNoUpdatesAtAll() throws InterruptedException {
        ConflatingBuffer buffer = new LockedConflatingBuffer();
        FakePublisher publisher = new FakePublisher(buffer, List.of());
        FakeSubscriber subscriber = new FakeSubscriber(buffer);

        new DerivedValuePipeline(publisher, subscriber, unusedComputer()).run();

        assertTrue(subscriber.received.isEmpty());
    }

    @Test
    void worksWithTheLockFreeBufferToo() throws InterruptedException {
        ConflatingBuffer buffer = new LockFreeConflatingBuffer();
        List<DerivedUpdate> toPublish = List.of(new DerivedUpdate("AAPL", 1.0, 100));

        FakePublisher publisher = new FakePublisher(buffer, toPublish);
        FakeSubscriber subscriber = new FakeSubscriber(buffer);

        new DerivedValuePipeline(publisher, subscriber, unusedComputer()).run();

        assertEquals(1, subscriber.received.size());
        assertEquals("AAPL", subscriber.received.get(0).instrument);
    }
}

package dvp;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The same behavioral contract, run against LockedConflatingBuffer and
 * LockFreeConflatingBuffer via @ParameterizedTest -- proving both concurrency strategies
 * satisfy identical semantics rather than just "both compile". Each test method gets a
 * fresh instance of each implementation, since JUnit re-invokes the @MethodSource per
 * parameterized test method.
 */
class ConflatingBufferContractTest {

    static List<ConflatingBuffer> implementations() {
        return List.of(new LockedConflatingBuffer(), new LockFreeConflatingBuffer());
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void conflatesMultipleUpdatesToSameInstrumentIntoOne(ConflatingBuffer buffer) throws InterruptedException {
        assertNull(buffer.put(new DerivedUpdate("AAPL", 1.0, 100)), "first put for a fresh instrument overwrites nothing");
        DerivedUpdate overwrittenBy2 = buffer.put(new DerivedUpdate("AAPL", 2.0, 200));
        assertNotNull(overwrittenBy2, "second put should report the first value as overwritten");
        assertEquals(1.0, overwrittenBy2.value, 1e-9);
        DerivedUpdate overwrittenBy3 = buffer.put(new DerivedUpdate("AAPL", 3.0, 300)); // only this one should ever be seen
        assertNotNull(overwrittenBy3, "third put should report the second value as overwritten");
        assertEquals(2.0, overwrittenBy3.value, 1e-9);
        buffer.markProducerDone();

        DerivedUpdate first = buffer.takeOldest();
        assertNotNull(first);
        assertEquals("AAPL", first.instrument);
        assertEquals(3.0, first.value, 1e-9);

        assertNull(buffer.takeOldest(), "buffer should be drained after the one conflated update");
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void preservesFirstDirtyOrderAcrossInstruments(ConflatingBuffer buffer) throws InterruptedException {
        // B goes dirty first; A updates several times after that. B must still be sent
        // first, and A must carry its latest value, not an intermediate one.
        buffer.put(new DerivedUpdate("B", 1.0, 100));
        buffer.put(new DerivedUpdate("A", 1.0, 101));
        buffer.put(new DerivedUpdate("A", 2.0, 102));
        buffer.put(new DerivedUpdate("A", 3.0, 103));
        buffer.put(new DerivedUpdate("A", 4.0, 104));
        buffer.markProducerDone();

        DerivedUpdate firstOut = buffer.takeOldest();
        DerivedUpdate secondOut = buffer.takeOldest();

        assertEquals("B", firstOut.instrument, "B was dirty first and must be sent first");
        assertEquals("A", secondOut.instrument);
        assertEquals(4.0, secondOut.value, 1e-9, "A should carry its LATEST value");
        assertNull(buffer.takeOldest());
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void instrumentReDirtiedAfterSendRejoinsAtBackOfLine(ConflatingBuffer buffer) throws InterruptedException {
        buffer.put(new DerivedUpdate("A", 1.0, 100));
        buffer.put(new DerivedUpdate("B", 1.0, 101));

        assertEquals("A", buffer.takeOldest().instrument); // A sent, its slot is now clean

        // A updates again AFTER being sent -- it must go to the back of the line, behind B,
        // which was already waiting. A high-update-rate instrument must never "cut the line".
        // This is NOT a conflation (A's prior value was already delivered, not overwritten
        // while pending), so put() must report null here, not the already-sent value.
        DerivedUpdate overwritten = buffer.put(new DerivedUpdate("A", 2.0, 200));
        assertNull(overwritten, "re-dirtying after send is not a conflation");
        buffer.markProducerDone();

        assertEquals("B", buffer.takeOldest().instrument);
        DerivedUpdate aAgain = buffer.takeOldest();
        assertEquals("A", aAgain.instrument);
        assertEquals(2.0, aAgain.value, 1e-9);
        assertNull(buffer.takeOldest());
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void takeOldestBlocksUntilProducerPutsSomething(ConflatingBuffer buffer) throws Exception {
        AtomicReference<DerivedUpdate> received = new AtomicReference<>();
        CountDownLatch consumerStarted = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            try {
                consumerStarted.countDown();
                received.set(buffer.takeOldest());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        consumerStarted.await();

        // Give the consumer a moment to actually be parked waiting, not just started.
        Thread.sleep(50);
        assertNull(received.get(), "consumer should still be blocked with nothing published yet");

        buffer.put(new DerivedUpdate("AAPL", 42.0, 1));
        consumer.join(2000);

        assertNotNull(received.get(), "consumer should unblock once an update is available");
        assertEquals(42.0, received.get().value, 1e-9);
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void takeOldestReturnsNullOnceDoneAndDrained(ConflatingBuffer buffer) throws InterruptedException {
        buffer.markProducerDone();
        assertNull(buffer.takeOldest(), "nothing was ever published, should drain to null immediately");
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void drainsAllDistinctInstrumentsBeforeReturningNull(ConflatingBuffer buffer) throws InterruptedException {
        buffer.put(new DerivedUpdate("A", 1.0, 100));
        buffer.put(new DerivedUpdate("B", 1.0, 101));
        buffer.put(new DerivedUpdate("C", 1.0, 102));
        buffer.markProducerDone();

        assertNotNull(buffer.takeOldest());
        assertNotNull(buffer.takeOldest());
        assertNotNull(buffer.takeOldest());
        assertNull(buffer.takeOldest(), "all three distinct instruments drained, should be null now");
    }
}

package dvp;

import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stress-tests LockFreeConflatingBuffer under real concurrent producer/consumer threads,
 * rather than the single-call-per-thread scenarios in ConflatingBufferContractTest. This
 * specifically targets the race described in LockFreeConflatingBuffer's Javadoc: the
 * consumer clearing a slot's "queued" flag can race a producer update, causing a redundant
 * re-enqueue. The per-slot version number exists to catch that and suppress the resulting
 * duplicate send -- this test tries to actually trigger the race at volume rather than only
 * reasoning about it, and checks two invariants hold regardless:
 *
 *   1. no lost updates: the LAST value produced for each instrument is always eventually
 *      delivered, even though intermediate values are legitimately conflated away.
 *   2. no duplicate delivery: the exact same produced update (identified by a globally
 *      unique sequence number, carried as both value and sourceTsMs) is never delivered
 *      more than once.
 *
 * Real thread timing is inherently non-deterministic, so this runs multiple times
 * (@RepeatedTest) at high volume to raise confidence rather than to prove correctness
 * outright -- a single green run here is weaker evidence than for the deterministic tests
 * elsewhere in this suite.
 */
class LockFreeConflatingBufferStressTest {

    private static final int INSTRUMENT_COUNT = 5;
    private static final int TOTAL_UPDATES = 25_000; // spread randomly across the instruments

    @RepeatedTest(10)
    void noLostUpdatesAndNoDuplicateDeliveryUnderConcurrentLoad() throws InterruptedException {
        LockFreeConflatingBuffer buffer = new LockFreeConflatingBuffer();
        String[] instruments = new String[INSTRUMENT_COUNT];
        for (int i = 0; i < INSTRUMENT_COUNT; i++) {
            instruments[i] = "INSTR-" + i;
        }

        AtomicLong globalSeq = new AtomicLong(0);
        // Written only by the producer thread, read only by the test thread after
        // producer.join() -- safe by the same thread-confinement + happens-before reasoning
        // used elsewhere in this codebase (e.g. Slot.lastSentVersion).
        Map<String, Long> lastProducedPerInstrument = new HashMap<>();

        Thread producer = new Thread(() -> {
            Random rng = new Random();
            for (int i = 0; i < TOTAL_UPDATES; i++) {
                String instrument = instruments[rng.nextInt(INSTRUMENT_COUNT)];
                long seq = globalSeq.incrementAndGet();
                lastProducedPerInstrument.put(instrument, seq);
                buffer.put(new DerivedUpdate(instrument, seq, seq));
            }
            buffer.markProducerDone();
        }, "stress-producer");

        // Written only by the consumer thread, read only by the test thread after
        // consumer.join() -- same reasoning.
        List<DerivedUpdate> received = new ArrayList<>();
        Thread consumer = new Thread(() -> {
            try {
                while (true) {
                    DerivedUpdate update = buffer.takeOldest();
                    if (update == null) {
                        break;
                    }
                    received.add(update);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "stress-consumer");

        consumer.start();
        producer.start();
        producer.join();
        consumer.join();

        // Invariant 1: no lost updates.
        Map<String, Long> lastReceivedPerInstrument = new HashMap<>();
        for (DerivedUpdate update : received) {
            lastReceivedPerInstrument.put(update.instrument, (long) update.value);
        }
        for (String instrument : instruments) {
            assertEquals(lastProducedPerInstrument.get(instrument), lastReceivedPerInstrument.get(instrument),
                    "final value for " + instrument + " should have been delivered");
        }

        // Invariant 2: no duplicate delivery -- every produced update (identified by its
        // globally unique seq, carried in `value`) is delivered at most once.
        Map<Long, Integer> deliveryCounts = new HashMap<>();
        for (DerivedUpdate update : received) {
            deliveryCounts.merge((long) update.value, 1, Integer::sum);
        }
        List<Long> duplicates = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : deliveryCounts.entrySet()) {
            if (entry.getValue() > 1) {
                duplicates.add(entry.getKey());
            }
        }
        assertTrue(duplicates.isEmpty(), "seq(s) delivered more than once: " + duplicates);
    }
}

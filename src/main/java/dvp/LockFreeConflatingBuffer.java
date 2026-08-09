package dvp;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * No lock anywhere. Per-instrument value lives in an AtomicReference (last-write-wins); a
 * CAS'd AtomicBoolean dedups whether an instrument is currently queued to send; order of
 * sends is a ConcurrentLinkedQueue. A Semaphore is used purely as a wake-up signal (permits
 * released 1:1 with successful enqueues) so the consumer parks instead of busy-spinning --
 * it is not a mutex guarding a critical section.
 *
 * Trade-off vs the locked version: marking a slot not-queued happens before reading its
 * value, so a producer update landing in that narrow window causes a harmless duplicate send
 * of an unchanged value on the next cycle. It never loses an update.
 *
 * Audit caveat: put()'s return value (used to detect and count conflation) is exact for
 * LockedConflatingBuffer but best-effort here, for the same reason as the duplicate-send
 * race above -- a conflation reported here is real, but under heavy concurrent contention a
 * small number of conflations could theoretically go uncounted. Treat this buffer's
 * conflation count as a lower bound, not an exact figure.
 */
public class LockFreeConflatingBuffer implements ConflatingBuffer {

    /**
     * Value + a monotonically increasing per-slot version, written together as one
     * immutable object so the consumer never sees a torn (version, value) pair.
     */
    private static class VersionedUpdate {
        final DerivedUpdate update;
        final long version;

        VersionedUpdate(DerivedUpdate update, long version) {
            this.update = update;
            this.version = version;
        }
    }

    private static class Slot {
        final AtomicReference<VersionedUpdate> value = new AtomicReference<>();
        final AtomicBoolean queued = new AtomicBoolean(false);
        final AtomicLong versionCounter = new AtomicLong(0);
        // Touched only by the single consumer thread inside takeOldest() -- no cross-thread
        // visibility concern, so this deliberately isn't atomic/volatile.
        long lastSentVersion = -1;
    }

    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
    private final Semaphore available = new Semaphore(0);
    private volatile boolean producerDone = false;

    @Override
    public DerivedUpdate put(DerivedUpdate update) {
        Slot slot = slots.computeIfAbsent(update.instrument, k -> new Slot());
        long version = slot.versionCounter.incrementAndGet();
        // Single producer thread -> getAndSet (not a CAS loop) is safe here.
        VersionedUpdate previous = slot.value.getAndSet(new VersionedUpdate(update, version));
        if (slot.queued.compareAndSet(false, true)) {
            order.add(update.instrument);
            available.release();
            // Fresh entry: either a brand-new instrument, or one re-dirtying after its
            // previous value was already sent (queued had been cleared by the consumer).
            // Either way nothing pending was lost -- `previous`, if non-null, was already
            // delivered, not overwritten while still waiting.
            return null;
        }
        // Already queued -- this update genuinely overwrote a value that was still pending
        // and will now never be sent. This is the conflation event an audit trail counts.
        return previous == null ? null : previous.update;
    }

    @Override
    public DerivedUpdate takeOldest() throws InterruptedException {
        while (true) {
            boolean gotPermit = available.tryAcquire(20, TimeUnit.MILLISECONDS);
            if (!gotPermit) {
                if (producerDone && order.isEmpty()) {
                    return null;
                }
                continue;
            }
            String instrument = order.poll();
            if (instrument == null) {
                continue; // defensive: permits and adds are 1:1, shouldn't normally happen
            }
            Slot slot = slots.get(instrument);
            slot.queued.set(false);
            VersionedUpdate versioned = slot.value.get();
            if (versioned == null) {
                continue;
            }
            if (versioned.version == slot.lastSentVersion) {
                // This exact value was already sent -- we're looking at the redundant
                // re-enqueue caused by a producer update racing the queued.set(false) above.
                // Nothing has actually changed since the last send, so skip it silently
                // rather than emitting a duplicate row.
                continue;
            }
            slot.lastSentVersion = versioned.version;
            return versioned.update;
        }
    }

    @Override
    public void markProducerDone() {
        producerDone = true;
        available.release();
    }
}

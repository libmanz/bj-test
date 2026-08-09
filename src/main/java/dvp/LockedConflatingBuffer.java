package dvp;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A LinkedHashMap<instrument, latestUpdate> guarded by an explicit lock. put() on an existing
 * key updates the value in place WITHOUT moving its iteration position (true because
 * accessOrder=false, the default) -- that's what gives fair, first-dirty-wins send ordering.
 */
public class LockedConflatingBuffer implements ConflatingBuffer {
    private final LinkedHashMap<String, DerivedUpdate> pending = new LinkedHashMap<>();
    private boolean producerDone = false;

    private final Lock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    @Override
    public DerivedUpdate put(DerivedUpdate update) {
        lock.lock();
        try {
            // LinkedHashMap.put() on an existing key returns the previous value and leaves
            // its position in iteration order untouched -- that's what gives us fairness,
            // and the returned previous value is exactly "what got conflated away."
            return pending.put(update.instrument, update);
        } finally {
            notEmpty.signal();
            lock.unlock();
        }
    }

    @Override
    public DerivedUpdate takeOldest() throws InterruptedException {
        lock.lock();
        try {
            while (pending.isEmpty() && !producerDone) {
                notEmpty.await();
            }
            if (pending.isEmpty()) {
                return null;
            }
            // if using Java 21+ - poll first entry can be used instead
            //Map.Entry<String, DerivedUpdate> oldest = pending.pollFirstEntry();
            Iterator<Map.Entry<String, DerivedUpdate>> it = pending.entrySet().iterator();
            Map.Entry<String, DerivedUpdate> oldest = it.next();
            it.remove();
            return oldest.getValue();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markProducerDone() {
        lock.lock();
        try {
            producerDone = true;
            System.err.println("[BUFFER] markProducerDone() called. Signalling all waiting consumers...");
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }
}

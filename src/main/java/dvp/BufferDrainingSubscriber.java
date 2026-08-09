package dvp;

import java.util.Random;

/**
 * The sole implementation of DerivedValueSubscriber: pulls updates from a constructor-
 * injected buffer and dispatches each one to a DerivedValueListener, pacing itself with a
 * delay (+/- jitter) sleep after each dispatch -- this is what actually models "the
 * downstream consumer is slower than the input." Contains no knowledge of where updates
 * ultimately go (CSV, HTTP, Kafka...); that is entirely the listener's concern.
 */
public class BufferDrainingSubscriber implements DerivedValueSubscriber {
    private final ConflatingBuffer buffer;
    private final DerivedValueListener listener;
    private final long delayMs;
    private final double jitterPct;
    private final Random rng;

    public BufferDrainingSubscriber(ConflatingBuffer buffer, DerivedValueListener listener,
                                     long delayMs, double jitterPct, Random rng) {
        this.buffer = buffer;
        this.listener = listener;
        this.delayMs = delayMs;
        this.jitterPct = jitterPct;
        this.rng = rng;
    }

    @Override
    public void subscribe() throws InterruptedException {
        while (true) {
            DerivedUpdate update = buffer.takeOldest();
            if (update == null) {
                System.err.println("[PUBLISHER] Received null from buffer. Exiting consumer loop cleanly.");
                break; // producer done, buffer fully drained
            }
            listener.onUpdate(update);
            DelayUtil.sleepWithJitter(delayMs, jitterPct, rng);
        }
        listener.onComplete();
    }
}

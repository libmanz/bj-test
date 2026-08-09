package dvp;

/**
 * Capacity-1-per-instrument conflating buffer: a producer thread pushes derived updates,
 * a consumer thread pulls the oldest still-dirty instrument's LATEST value. Implementations
 * must guarantee:
 *   - conflation: multiple put()s for the same instrument before it's taken collapse to one
 *   - fairness: an instrument's send order is fixed by when it first went dirty since its
 *     last take, not refreshed by subsequent updates
 *   - takeOldest() blocks while empty and the producer isn't done, and returns null once
 *     markProducerDone() has been called and the buffer is fully drained
 */
public interface ConflatingBuffer {
    /**
     * Publishes an update into the buffer. Returns the pending value this call overwrote
     * (i.e. a value that was still waiting to be sent and will now never go out), or null if
     * this was a fresh entry -- either a brand-new instrument, or an instrument re-dirtying
     * after its previous value was already sent. The return value is what an audit trail
     * uses to detect and count conflation.
     */
    DerivedUpdate put(DerivedUpdate update);

    DerivedUpdate takeOldest() throws InterruptedException;

    void markProducerDone();
}

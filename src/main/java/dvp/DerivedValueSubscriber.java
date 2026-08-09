package dvp;

/**
 * Pulls updates from a ConflatingBuffer and dispatches each one to a DerivedValueListener.
 * The buffer is injected at construction, not passed per call. BufferDrainingSubscriber is
 * the sole implementation.
 *
 * Contract:
 *  - subscribe() loops on the buffer's takeOldest() until it returns null (producer done
 *    and buffer drained), then returns.
 *  - Delivery-side errors are handled internally by whatever DerivedValueListener is used.
 */
public interface DerivedValueSubscriber {
    void subscribe() throws InterruptedException;
}

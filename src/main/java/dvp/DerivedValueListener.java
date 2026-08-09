package dvp;

/**
 * Receives one DerivedUpdate at a time, in delivery order, and does whatever it likes with
 * it (write a CSV row, make an HTTP call, publish to Kafka, collect into a list for a
 * test...). Has no knowledge of the ConflatingBuffer it's ultimately fed by -- that's
 * DerivedValueSubscriber's job (see BufferDrainingSubscriber).
 */
public interface DerivedValueListener {
    /** Called once per published update, in delivery order. */
    void onUpdate(DerivedUpdate update);

    /** Called once, after the buffer has been fully drained (producer done, nothing left to
     *  send). Implementations holding open resources (e.g. a file writer) should release
     *  them here. No-op by default. */
    default void onComplete() {
    }
}

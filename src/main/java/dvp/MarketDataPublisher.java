package dvp;

/**
 * Publishes derived-value updates. Implementations own their data source (CSV, socket,
 * Kafka, test fixture...) and any parsing/state it needs. The DerivedValueComputer to
 * publish through is passed to publish() rather than injected at construction -- publish()
 * alone reads like a bare lifecycle signal (start/run); publish(computer) makes explicit
 * what's actually happening: rows flow through this computer as they're read.
 *
 * Contract:
 *  - publish(computer) drives this publisher to completion, feeding every valid row to
 *    computer.onInput() and every rejected row to computer.onRejected().
 *  - computer.markComplete() MUST be called exactly once, when the source is exhausted.
 *  - Source-level errors (bad row, dropped connection) are handled internally by the
 *    implementation -- this interface doesn't dictate a policy.
 */
public interface MarketDataPublisher {
    void publish(DerivedValueComputer computer) throws InterruptedException;
}

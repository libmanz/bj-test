package dvp;

/**
 * Orchestrates one pipeline run: runs the given MarketDataPublisher and DerivedValueSubscriber
 * on separate threads, waits for both to finish. Still buffer-agnostic -- the buffer is
 * wired into the subscriber at construction time (see Main.main, the composition root) and
 * never flows through this class. It DOES now hold a DerivedValueComputer reference, purely
 * to forward into publisher.publish(computer) -- it doesn't build one (Main still does, and
 * still owns the AuditLog it needs), just passes through what it was handed. This is a
 * deliberate, narrower coupling than the pipeline building or owning domain objects itself.
 */
public class DerivedValuePipeline {

    private final MarketDataPublisher publisher;
    private final DerivedValueSubscriber subscriber;
    private final DerivedValueComputer computer;

    public DerivedValuePipeline(MarketDataPublisher publisher, DerivedValueSubscriber subscriber,
                                 DerivedValueComputer computer) {
        this.publisher = publisher;
        this.subscriber = subscriber;
        this.computer = computer;
    }

    public void run() throws InterruptedException {
        Thread producerThread = new Thread(() -> {
            try {
                publisher.publish(computer);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "market-data-publisher");

        Thread consumerThread = new Thread(() -> {
            try {
                subscriber.subscribe();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "derived-value-subscriber");

        consumerThread.start();
        producerThread.start();

        producerThread.join();
        consumerThread.join();
    }
}

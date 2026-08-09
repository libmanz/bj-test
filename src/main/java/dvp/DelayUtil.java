package dvp;

import java.util.Random;

/** Shared pacing helper: sleeps baseMs +/- jitterPct, used by both CsvMarketDataPublisher and BufferDrainingSubscriber. */
final class DelayUtil {
    private DelayUtil() {
    }

    static void sleepWithJitter(long baseMs, double jitterPct, Random rng) throws InterruptedException {
        if (baseMs <= 0) {
            return;
        }
        double factor = 1.0 + (rng.nextDouble() * 2 - 1) * jitterPct; // +/- jitterPct around baseMs
        long sleepMs = Math.max(0, Math.round(baseMs * factor));
        Thread.sleep(sleepMs);
    }
}

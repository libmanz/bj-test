package dvp;

/**
 * A no-op AuditLog for tests that need a valid DerivedValueComputer but don't care about
 * audit content -- avoids dragging real file I/O into tests that are actually about
 * something else entirely.
 *
 * Deliberately kept in test sources, not src/main: this exercise's whole point is
 * auditability, so a "silently discard everything" implementation shouldn't be available as
 * a first-class production option, even unused.
 */
public class NoOpAuditLog implements AuditLog {
    @Override
    public void logRejected(int lineNo, String reason, String instrument) {
    }

    @Override
    public void logConflated(String instrument, DerivedUpdate overwritten, DerivedUpdate replacement) {
    }

    @Override
    public void logIncompleteAtShutdown(String instrument, String missing) {
    }

    @Override
    public void logSummary(String instrument, long received, long publishAttempts,
                            long conflated, long delivered, boolean everCompleted) {
    }

    @Override
    public void logGlobalSummary(long unattributedRejections) {
    }

    @Override
    public void close() {
    }
}

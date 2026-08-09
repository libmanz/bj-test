package dvp;

/**
 * Records the audit trail of the boundary between "what arrived, what was published, what
 * was rejected." DerivedValueComputer decides what's audit-worthy and when; implementations
 * only know how to record an event -- CsvAuditLog is the real (file-writing) implementation.
 *
 * Kept as an interface, not just the concrete CsvAuditLog, specifically so tests that need a
 * valid DerivedValueComputer but don't care about audit content can pass a no-op instead of
 * dragging in real file I/O (see NoOpAuditLog in the test sources).
 */
public interface AuditLog {
    void logRejected(int lineNo, String reason, String instrument);

    void logConflated(String instrument, DerivedUpdate overwritten, DerivedUpdate replacement);

    void logIncompleteAtShutdown(String instrument, String missing);

    void logSummary(String instrument, long received, long publishAttempts,
                     long conflated, long delivered, boolean everCompleted);

    void logGlobalSummary(long unattributedRejections);

    void close();
}

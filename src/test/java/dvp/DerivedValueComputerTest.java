package dvp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for DerivedValueComputer in isolation -- no file input, no CSV, no threading.
 * Buffer and AuditLog are both injected via the constructor. Audit-content assertions read
 * the real audit file back after markComplete() closes it, using loose .contains() checks
 * (matching the style used elsewhere for CSV output) rather than exact-line matches.
 *
 * takeOldest() blocks on an empty buffer unless markProducerDone() has been called --
 * markComplete() forwards to that, so tests asserting "nothing was published" call
 * computer.markComplete() first.
 */
class DerivedValueComputerTest {

    private static RowParser.ParsedRow row(long ts, String instrument, String type, double value) {
        return new RowParser.ParsedRow(ts, instrument, type, value);
    }

    private DerivedValueComputer computerFor(ConflatingBuffer buffer, Path auditPath) {
        return new DerivedValueComputer(buffer,
                auditPath == null ? new NoOpAuditLog() : new CsvAuditLog(auditPath));
    }

    // ---- publish / conflation behavior (unchanged from before the audit trail existed) ----

    @Test
    void doesNotPublishAnIncompleteInstrument() throws InterruptedException {
        ConflatingBuffer buffer = new LockedConflatingBuffer();
        DerivedValueComputer computer = computerFor(buffer, null);

        computer.onInput(row(100, "AAPL", "base_rate", 1.0));
        computer.onInput(row(200, "AAPL", "spread", 0.5));
        // no adjustment -- AAPL never becomes complete

        computer.markComplete();
        assertNull(buffer.takeOldest(), "incomplete instrument should never be published");
    }

    @Test
    void publishesDerivedValueOnceComplete() throws InterruptedException {
        ConflatingBuffer buffer = new LockedConflatingBuffer();
        DerivedValueComputer computer = computerFor(buffer, null);

        computer.onInput(row(100, "AAPL", "base_rate", 1.0));
        computer.onInput(row(200, "AAPL", "spread", 0.5));
        computer.onInput(row(300, "AAPL", "adjustment", -0.25));

        DerivedUpdate update = buffer.takeOldest();
        assertNotNull(update);
        assertEquals("AAPL", update.instrument);
        assertEquals(1.25, update.value, 1e-9);

        computer.markComplete();
        assertNull(buffer.takeOldest());
    }

    @Test
    void tracksMultipleInstrumentsIndependently() throws InterruptedException {
        ConflatingBuffer buffer = new LockedConflatingBuffer();
        DerivedValueComputer computer = computerFor(buffer, null);

        computer.onInput(row(100, "AAPL", "base_rate", 1.0));
        computer.onInput(row(101, "MSFT", "base_rate", 2.0));
        computer.onInput(row(102, "AAPL", "spread", 0.0));
        computer.onInput(row(103, "MSFT", "spread", 0.0));
        computer.onInput(row(104, "AAPL", "adjustment", 0.0)); // AAPL completes first
        computer.onInput(row(105, "MSFT", "adjustment", 0.0)); // MSFT completes second

        DerivedUpdate first = buffer.takeOldest();
        DerivedUpdate second = buffer.takeOldest();
        assertEquals("AAPL", first.instrument, "AAPL completed first, should be published first");
        assertEquals("MSFT", second.instrument);

        computer.markComplete();
        assertNull(buffer.takeOldest());
    }

    // ---- audit trail: conflation ----

    @Test
    void conflationIsLoggedAndReflectedInTheSummary(@TempDir Path tempDir) throws Exception {
        Path auditPath = tempDir.resolve("audit.csv");
        ConflatingBuffer buffer = new LockedConflatingBuffer();
        DerivedValueComputer computer = computerFor(buffer, auditPath);

        computer.onInput(row(100, "AAPL", "base_rate", 1.0));
        computer.onInput(row(200, "AAPL", "spread", 0.0));
        computer.onInput(row(300, "AAPL", "adjustment", 0.0)); // complete: publishAttempts=1, delivered=1.0
        computer.onInput(row(400, "AAPL", "base_rate", 5.0));  // re-derives and overwrites the pending
                                                                // value before it was ever taken: publishAttempts=2, conflated=1

        computer.markComplete(); // buffer never drained via takeOldest() -- irrelevant to the audit counters

        List<String> lines = Files.readAllLines(auditPath);
        boolean sawConflated = lines.stream().anyMatch(l -> l.contains("CONFLATED") && l.contains("AAPL"));
        assertTrue(sawConflated, "a CONFLATED event should have been logged for AAPL");

        String summaryLine = lines.stream()
                .filter(l -> l.contains("SUMMARY") && l.contains("AAPL"))
                .findFirst().orElse(null);
        assertNotNull(summaryLine, "a SUMMARY row for AAPL should exist");
        assertTrue(summaryLine.contains("publish_attempts=2"));
        assertTrue(summaryLine.contains("conflated=1"));
        assertTrue(summaryLine.contains("delivered=1"), "delivered should be publish_attempts(2) - conflated(1) = 1");
        assertTrue(summaryLine.contains("completed=true"));
    }

    // ---- audit trail: rejections ----

    @Test
    void rejectionWithKnownInstrumentIsLoggedAndAttributed(@TempDir Path tempDir) throws Exception {
        Path auditPath = tempDir.resolve("audit.csv");
        ConflatingBuffer buffer = new LockedConflatingBuffer();
        DerivedValueComputer computer = computerFor(buffer, auditPath);

        computer.onRejected(7, "non-numeric value 'oops'", "AAPL");
        computer.markComplete();

        List<String> lines = Files.readAllLines(auditPath);
        boolean sawRejected = lines.stream()
                .anyMatch(l -> l.contains("REJECTED") && l.contains("AAPL") && l.contains("line 7"));
        assertTrue(sawRejected, "a REJECTED event attributed to AAPL should have been logged");
    }

    @Test
    void rejectionWithoutKnownInstrumentCountsAsUnattributed(@TempDir Path tempDir) throws Exception {
        Path auditPath = tempDir.resolve("audit.csv");
        ConflatingBuffer buffer = new LockedConflatingBuffer();
        DerivedValueComputer computer = computerFor(buffer, auditPath);

        computer.onRejected(2, "expected 4 columns; got 3", null);
        computer.markComplete();

        List<String> lines = Files.readAllLines(auditPath);
        boolean sawGlobalSummary = lines.stream().anyMatch(l -> l.contains("unattributed_rejections=1"));
        assertTrue(sawGlobalSummary, "the global summary should count the unattributed rejection");
    }

    // ---- audit trail: incomplete at shutdown ----

    @Test
    void incompleteInstrumentAtShutdownIsLogged(@TempDir Path tempDir) throws Exception {
        Path auditPath = tempDir.resolve("audit.csv");
        ConflatingBuffer buffer = new LockedConflatingBuffer();
        DerivedValueComputer computer = computerFor(buffer, auditPath);

        computer.onInput(row(100, "AAPL", "base_rate", 1.0));
        computer.onInput(row(200, "AAPL", "spread", 0.5));
        // no adjustment ever arrives

        computer.markComplete();

        List<String> lines = Files.readAllLines(auditPath);
        boolean sawIncomplete = lines.stream()
                .anyMatch(l -> l.contains("INCOMPLETE_AT_SHUTDOWN") && l.contains("AAPL") && l.contains("adjustment"));
        assertTrue(sawIncomplete, "AAPL should be reported as incomplete, missing adjustment");

        String summaryLine = lines.stream()
                .filter(l -> l.contains("SUMMARY") && l.contains("AAPL"))
                .findFirst().orElse(null);
        assertNotNull(summaryLine);
        assertTrue(summaryLine.contains("completed=false"));
        assertTrue(summaryLine.contains("publish_attempts=0"));
    }

    @Test
    void instrumentThatWasOnlyEverRejectedStillAppearsAsIncompleteAtShutdown(@TempDir Path tempDir) throws Exception {
        // Regression check: before state and counters were merged into one InstrumentRecord
        // per instrument, an instrument touched only by rejections (never a single valid
        // row) never got an entry in the completeness scan, so it was silently invisible to
        // INCOMPLETE_AT_SHUTDOWN even though it's maximally incomplete.
        Path auditPath = tempDir.resolve("audit.csv");
        ConflatingBuffer buffer = new LockedConflatingBuffer();
        DerivedValueComputer computer = computerFor(buffer, auditPath);

        computer.onRejected(3, "non-numeric value 'oops'", "AAPL"); // AAPL never gets a valid row at all

        computer.markComplete();

        List<String> lines = Files.readAllLines(auditPath);
        boolean sawIncomplete = lines.stream()
                .anyMatch(l -> l.contains("INCOMPLETE_AT_SHUTDOWN") && l.contains("AAPL"));
        assertTrue(sawIncomplete, "an instrument that was only ever rejected should still be flagged incomplete");
    }

    @Test
    void markCompleteClosesTheAuditFileSoItCanBeReadBackAfterwards(@TempDir Path tempDir) throws Exception {
        Path auditPath = tempDir.resolve("audit.csv");
        ConflatingBuffer buffer = new LockedConflatingBuffer();
        DerivedValueComputer computer = computerFor(buffer, auditPath);

        computer.markComplete();

        List<String> lines = Files.readAllLines(auditPath);
        assertEquals("event_seq,event_ts_ms,event_type,instrument,detail", lines.get(0));
    }
}

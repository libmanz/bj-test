package dvp;

import java.util.HashMap;
import java.util.Map;

/**
 * Owns per-instrument state, the "is this instrument publishable yet" decision, and the
 * audit trail: buffer and AuditLog are both injected via constructor. Given a validated
 * input row, applies it to that instrument's running state and -- if the instrument is
 * complete as a result -- publishes the derived value into the buffer.
 *
 * Also owns rejection reporting (onRejected) and, at shutdown (markComplete), scans for any
 * instrument that never became complete and writes a final per-instrument summary.
 *
 * State and counters live together in one InstrumentRecord per instrument, keyed by a
 * single map -- not two separate maps -- so that an instrument touched only by rejections
 * (never a single valid row) still gets an entry, and therefore still gets flagged
 * INCOMPLETE_AT_SHUTDOWN like any other never-completed instrument, rather than being
 * silently invisible to that scan.
 *
 * "Delivered" is deliberately never tracked as its own counter: delivered = publishAttempts
 * - conflated. Every buffered value is eventually either overwritten while still pending
 * (conflated, reported via ConflatingBuffer.put()'s return value) or drained by the
 * subscriber (delivered) -- there's no third outcome, so delivered falls out of the other
 * two rather than needing the subscriber to report back a count of its own.
 *
 * Deliberately independent of any input source: any MarketDataPublisher implementation
 * (CSV, Kafka, a socket) can reuse this unchanged as long as it can produce a
 * RowParser.ParsedRow per input event and can report rejected lines via onRejected().
 */
public class DerivedValueComputer {

    private static class InstrumentRecord {
        final InstrumentState state = new InstrumentState();
        long received = 0;
        long publishAttempts = 0;
        long conflated = 0;
        long rejected = 0;
    }

    private final ConflatingBuffer buffer;
    private final AuditLog auditLog;
    private final Map<String, InstrumentRecord> instruments = new HashMap<>();
    private long unattributedRejections = 0; // rejections before the instrument column was readable

    public DerivedValueComputer(ConflatingBuffer buffer, AuditLog auditLog) {
        this.buffer = buffer;
        this.auditLog = auditLog;
    }

    /**
     * Applies one parsed input row. If the instrument becomes complete as a result, the
     * resulting DerivedUpdate is published into the buffer; if that publish overwrites a
     * still-pending value for the same instrument, a CONFLATED event is logged.
     */
    public void onInput(RowParser.ParsedRow row) {
        InstrumentRecord rec = instruments.computeIfAbsent(row.instrument, k -> new InstrumentRecord());
        rec.received++;
        rec.state.apply(row.inputType, row.value, row.timestampMs);

        if (rec.state.isComplete()) {
            rec.publishAttempts++;
            DerivedUpdate update = new DerivedUpdate(row.instrument, rec.state.derive(), rec.state.lastSourceTsMs());
            DerivedUpdate overwritten = buffer.put(update);
            if (overwritten != null) {
                rec.conflated++;
                auditLog.logConflated(row.instrument, overwritten, update);
            }
        }
    }

    /** Reports one rejected input line. Attributes to an instrument's record when the
     *  instrument column was readable; otherwise counted only in the global total. */
    public void onRejected(int lineNo, String reason, String instrument) {
        auditLog.logRejected(lineNo, reason, instrument);
        if (instrument == null || instrument.isEmpty()) {
            unattributedRejections++;
        } else {
            instruments.computeIfAbsent(instrument, k -> new InstrumentRecord()).rejected++;
        }
    }

    /**
     * Signals that no more input will arrive: scans every touched instrument for
     * incompleteness, writes the final per-instrument (and global) summary, forwards to the
     * buffer's markProducerDone() so the subscriber side can drain and stop, then closes the
     * audit log.
     */
    public void markComplete() {

        buffer.markProducerDone();

        // dump audit summary
        for (Map.Entry<String, InstrumentRecord> entry : instruments.entrySet()) {
            String instrument = entry.getKey();
            InstrumentRecord rec = entry.getValue();
            boolean everCompleted = rec.state.isComplete();
            if (!everCompleted) {
                auditLog.logIncompleteAtShutdown(instrument, rec.state.missingInputsDescription());
            }
            long delivered = rec.publishAttempts - rec.conflated;
            auditLog.logSummary(instrument, rec.received, rec.publishAttempts, rec.conflated, delivered, everCompleted);
        }
        auditLog.logGlobalSummary(unattributedRejections);
        auditLog.close();
    }
}

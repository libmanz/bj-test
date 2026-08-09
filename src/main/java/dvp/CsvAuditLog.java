package dvp;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;

/**
 * Writes the audit trail to a CSV file. Pure I/O, no decisions -- DerivedValueComputer
 * decides what's audit-worthy and when; this class only knows how to write a row.
 *
 * Individual events (REJECTED, CONFLATED, INCOMPLETE_AT_SHUTDOWN) are written and flushed
 * immediately as they happen -- never buffered in memory, matching CsvDerivedValueListener's
 * pattern, since the only caller (DerivedValueComputer) is confined to a single thread.
 * SUMMARY rows are written once, at the very end, from per-instrument counters that were
 * already being kept regardless (bounded by instrument cardinality, not event volume).
 *
 * "Published" is deliberately never logged as its own event: delivered = publishAttempts -
 * conflated, since every buffered value is eventually either overwritten (conflated) or
 * drained by the subscriber (delivered) -- see DerivedValueComputer for the accounting.
 */
public class CsvAuditLog implements AuditLog {
    private final BufferedWriter writer;
    private long seq = 0; // touched only by the single calling thread -- see class Javadoc
    private final DecimalFormat df = new DecimalFormat("#.####");

    public CsvAuditLog(Path auditPath) {
        try {
            writer = Files.newBufferedWriter(auditPath);
            writer.write("event_seq,event_ts_ms,event_type,instrument,detail");
            writer.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open audit file: " + auditPath, e);
        }
    }

    @Override
    public void logRejected(int lineNo, String reason, String instrument) {
        // reason is free text and can legitimately contain commas (e.g. RowParser's
        // "expected 4 columns, got 3") -- this writer doesn't do CSV quoting, so commas
        // must be neutralized or they'd silently corrupt the row's column structure.
        String safeReason = reason.replace(',', ';');
        writeRow("REJECTED", instrument,
            new StringBuilder().append("line ").append(lineNo).append(": ").append(safeReason));
    }

    @Override
    public void logConflated(String instrument, DerivedUpdate overwritten, DerivedUpdate replacement) {
        writeRow("CONFLATED", instrument, new StringBuilder()
                .append("value=").append(df.format(overwritten.value))
                .append(" (source_ts=").append(overwritten.sourceTsMs).append(")")
                .append(" superseded by value=").append(df.format(replacement.value))
                .append(" (source_ts=" ).append(replacement.sourceTsMs)
                .append(") before it was ever sent"));
    }

    @Override
    public void logIncompleteAtShutdown(String instrument, String missing) {
        writeRow("INCOMPLETE_AT_SHUTDOWN", instrument, new StringBuilder()
                .append("never received: ").append(missing.replace(',', ';')));
    }

    @Override
    public void logSummary(String instrument, long received, long publishAttempts,
                            long conflated, long delivered, boolean everCompleted) {
        writeRow("SUMMARY", instrument,new StringBuilder()
                .append("received=").append(received)
                .append(" publish_attempts=").append(publishAttempts)
                .append(" conflated=").append(conflated)
                .append(" delivered=").append(delivered)
                .append(" completed=").append(everCompleted));
    }

    @Override
    public void logGlobalSummary(long unattributedRejections) {
        writeRow("SUMMARY", "", "unattributed_rejections=" + unattributedRejections);
    }

    private void writeRow(String eventType, String instrument, CharSequence detail) {
        try {
            seq++;
            long ts = System.currentTimeMillis();
            String safeInstrument = instrument == null ? "" : instrument;
            writer.append(new StringBuilder()
                    .append(seq).append(',')
                    .append(ts).append(',')
                    .append(eventType).append(',')
                    .append(instrument == null ? "" : instrument).append(',')
                    .append(detail));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            System.err.println("CsvAuditLog I/O error: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException e) {
            System.err.println("CsvAuditLog failed to close output file: " + e.getMessage());
        }
    }
}

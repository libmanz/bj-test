package dvp;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes each received DerivedUpdate as one CSV row. Owns the output file's lifecycle:
 * opens it and writes the header eagerly at construction (so an empty run still produces a
 * header-only file), closes it in onComplete(). No pacing logic here -- the send rate is a
 * transport concern owned by whatever DerivedValueSubscriber is feeding this listener (see
 * BufferDrainingSubscriber), not a formatting concern.
 */
public class CsvDerivedValueListener implements DerivedValueListener {
    private final BufferedWriter writer;
    private final AtomicLong seq = new AtomicLong(0);
    private final DecimalFormat df = new DecimalFormat("#.####");

    public CsvDerivedValueListener(Path outputPath) {
        try {
            writer = Files.newBufferedWriter(outputPath);
            writer.write("publish_seq,publish_ts_ms,instrument,derived_value,source_ts_ms");
            writer.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open output file: " + outputPath, e);
        }
    }

    @Override
    public void onUpdate(DerivedUpdate update) {
        try {
            long publishTs = System.currentTimeMillis();

            StringBuilder sb = new StringBuilder()
                    .append(seq.incrementAndGet()).append(',')
                    .append(publishTs).append(',')
                    .append(update.instrument).append(',')
                    .append(df.format(update.value)).append(',')
                    .append(update.sourceTsMs);
            writer.append(sb);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            System.err.println("CsvDerivedValueListener I/O error: " + e.getMessage());
        }
    }

    @Override
    public void onComplete() {
        try {
            writer.close();
        } catch (IOException e) {
            System.err.println("CsvDerivedValueListener failed to close output file: " + e.getMessage());
        }
    }
}

package dvp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises CsvDerivedValueListener directly -- no buffer, no subscriber, no threading. */
class CsvDerivedValueListenerTest {

    @Test
    void writesHeaderEagerlyEvenBeforeAnyUpdate(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("out.csv");
        CsvDerivedValueListener listener = new CsvDerivedValueListener(out);
        listener.onComplete();

        List<String> lines = Files.readAllLines(out);
        assertEquals(1, lines.size());
        assertEquals("publish_seq,publish_ts_ms,instrument,derived_value,source_ts_ms", lines.get(0));
    }

    @Test
    void writesOneRowPerUpdateAndClosesOnComplete(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("out.csv");
        CsvDerivedValueListener listener = new CsvDerivedValueListener(out);

        listener.onUpdate(new DerivedUpdate("AAPL", 1.25, 300));
        listener.onUpdate(new DerivedUpdate("MSFT", 2.5, 400));
        listener.onComplete();

        List<String> lines = Files.readAllLines(out);
        assertEquals(3, lines.size(), "header + 2 rows");
        assertTrue(lines.get(1).startsWith("1,"));
        assertTrue(lines.get(1).contains("AAPL,1.25,300"));
        assertTrue(lines.get(2).startsWith("2,"));
        assertTrue(lines.get(2).contains("MSFT,2.5,400"));
    }

    @Test
    void publishSeqIsMonotonicAcrossMultipleUpdates(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("out.csv");
        CsvDerivedValueListener listener = new CsvDerivedValueListener(out);

        listener.onUpdate(new DerivedUpdate("A", 1.0, 1));
        listener.onUpdate(new DerivedUpdate("B", 2.0, 2));
        listener.onUpdate(new DerivedUpdate("C", 3.0, 3));
        listener.onComplete();

        List<String> lines = Files.readAllLines(out);
        assertEquals("1", lines.get(1).split(",")[0]);
        assertEquals("2", lines.get(2).split(",")[0]);
        assertEquals("3", lines.get(3).split(",")[0]);
    }
}

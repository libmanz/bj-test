package dvp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Exercises CsvMarketDataPublisher against real temp files. publish(computer) is called
 * directly on the test thread -- no Thread/start/join needed, since it's a synchronous
 * method from the MarketDataPublisher interface's point of view. Delay/jitter are zeroed so
 * these run instantly. The DerivedValueComputer is built fresh per test with a NoOpAuditLog
 * -- these tests care about CSV parsing/conflation mechanics, not audit content, so there's
 * no reason to write a real audit file per test.
 */
class CsvMarketDataPublisherTest {

    private CsvMarketDataPublisher publisherFor(Path csv) {
        return new CsvMarketDataPublisher(csv, 0L, 0.0, new Random(0));
    }

    private DerivedValueComputer computerFor(ConflatingBuffer buffer) {
        return new DerivedValueComputer(buffer, new NoOpAuditLog());
    }

    private Path writeCsv(Path dir, String... lines) throws IOException {
        Path file = dir.resolve("input.csv");
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    @Test
    void publishesOnceAllThreeInputsHaveArrived(@TempDir Path tempDir) throws Exception {
        Path csv = writeCsv(tempDir,
                "timestamp_ms,instrument,input_type,value",
                "100,AAPL,base_rate,1.0",
                "200,AAPL,spread,0.5",
                "300,AAPL,adjustment,-0.25");

        ConflatingBuffer buffer = new LockedConflatingBuffer();
        publisherFor(csv).publish(computerFor(buffer));

        DerivedUpdate update = buffer.takeOldest();
        assertNotNull(update);
        assertEquals("AAPL", update.instrument);
        assertEquals(1.25, update.value, 1e-9);
        assertEquals(300, update.sourceTsMs);
        assertNull(buffer.takeOldest(), "buffer should be drained and producer marked done");
    }

    @Test
    void doesNotPublishAnIncompleteInstrument(@TempDir Path tempDir) throws Exception {
        Path csv = writeCsv(tempDir,
                "timestamp_ms,instrument,input_type,value",
                "100,AAPL,base_rate,1.0",
                "200,AAPL,spread,0.5"); // no adjustment -- AAPL never becomes complete

        ConflatingBuffer buffer = new LockedConflatingBuffer();
        publisherFor(csv).publish(computerFor(buffer));

        assertNull(buffer.takeOldest(), "instrument missing one input should never be published");
    }

    @Test
    void skipsMalformedRowsAndKeepsProcessingValidOnes(@TempDir Path tempDir) throws Exception {
        Path csv = writeCsv(tempDir,
                "timestamp_ms,instrument,input_type,value",
                "100,AAPL,base_rate,1.0",
                "not-a-timestamp,AAPL,spread,0.5",   // bad timestamp -- skipped
                "200,AAPL,spread,0.5",               // valid
                "250,AAPL,discount_factor,9.9",      // unknown input_type -- skipped
                "300,AAPL,adjustment,NaN",           // non-finite value -- skipped
                "300,AAPL,adjustment,-0.25");        // valid

        ConflatingBuffer buffer = new LockedConflatingBuffer();
        publisherFor(csv).publish(computerFor(buffer));

        DerivedUpdate update = buffer.takeOldest();
        assertNotNull(update);
        assertEquals(1.25, update.value, 1e-9, "only the valid rows should have contributed");
        assertNull(buffer.takeOldest());
    }

    @Test
    void emptyFileAfterHeaderProducesNoUpdatesButStillMarksDone(@TempDir Path tempDir) throws Exception {
        Path csv = writeCsv(tempDir, "timestamp_ms,instrument,input_type,value");

        ConflatingBuffer buffer = new LockedConflatingBuffer();
        publisherFor(csv).publish(computerFor(buffer));

        assertNull(buffer.takeOldest());
    }

    @Test
    void firstLineIsAlwaysSkippedAsHeaderRegardlessOfContent(@TempDir Path tempDir) throws Exception {
        // Even if the "header" happens to look like a data row it must still be discarded --
        // the contract is strictly positional (first line is always the header).
        Path csv = writeCsv(tempDir,
                "100,AAPL,base_rate,1.0",  // treated as the header and discarded
                "200,AAPL,base_rate,2.0",
                "300,AAPL,spread,0.0",
                "400,AAPL,adjustment,0.0");

        ConflatingBuffer buffer = new LockedConflatingBuffer();
        publisherFor(csv).publish(computerFor(buffer));

        DerivedUpdate update = buffer.takeOldest();
        assertNotNull(update);
        assertEquals(2.0, update.value, 1e-9, "the first line should have been discarded as a header");
    }

    @Test
    void conflatesMultipleCompleteUpdatesForTheSameInstrumentWithinOneFile(@TempDir Path tempDir) throws Exception {
        Path csv = writeCsv(tempDir,
                "timestamp_ms,instrument,input_type,value",
                "100,AAPL,base_rate,1.0",
                "200,AAPL,spread,0.0",
                "300,AAPL,adjustment,0.0",  // AAPL complete: derived = 1.0
                "400,AAPL,base_rate,5.0");  // AAPL updates again: derived = 5.0

        ConflatingBuffer buffer = new LockedConflatingBuffer();
        publisherFor(csv).publish(computerFor(buffer));

        DerivedUpdate update = buffer.takeOldest();
        assertNotNull(update);
        assertEquals(5.0, update.value, 1e-9, "buffer should hold AAPL's latest value, not the first");
        assertNull(buffer.takeOldest());
    }

    @Test
    void nonexistentFileIsHandledWithoutThrowingAndStillMarksDone(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("does-not-exist.csv");
        ConflatingBuffer buffer = new LockedConflatingBuffer();
        DerivedValueComputer computer = computerFor(buffer);

        assertDoesNotThrow(() -> publisherFor(missing).publish(computer));
        assertNull(assertDoesNotThrow(buffer::takeOldest),
                "producer should still mark done even if the file couldn't be read");
    }
}

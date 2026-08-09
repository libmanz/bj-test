package dvp;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** Streams a CSV file row by row and delegates every parsed row to whichever
 *  DerivedValueComputer is passed to publish(), pacing itself with a delay (+/- jitter)
 *  sleep after each row. Purely mechanical: no instrument-state, publishability, or buffer
 *  logic lives here -- this class never references ConflatingBuffer at all. See
 *  DerivedValueComputer. */
public class CsvMarketDataPublisher implements MarketDataPublisher {
    private final Path inputPath;
    private final long delayMs;
    private final double jitterPct;
    private final Random rng;

    public CsvMarketDataPublisher(Path inputPath, long delayMs, double jitterPct, Random rng) {
        this.inputPath = inputPath;
        this.delayMs = delayMs;
        this.jitterPct = jitterPct;
        this.rng = rng;
    }

    @Override
    public void publish(DerivedValueComputer computer) throws InterruptedException {
        try (BufferedReader reader = Files.newBufferedReader(inputPath)) {
            String header = reader.readLine(); // assumed present; first line skipped
            if (header == null) {
                System.err.println("Input file is empty.");
                return;
            }
            String line;
            int lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (!line.isBlank()) {
                    processLine(line, lineNo, computer);
                }
                DelayUtil.sleepWithJitter(delayMs, jitterPct, rng);
            }
        } catch (IOException e) {
            System.err.println("Fatal: could not read input file: " + e.getMessage());
        } finally {
            computer.markComplete();
        }
    }

    private void processLine(String line, int lineNo, DerivedValueComputer computer) {
        RowParser.Result result = RowParser.parse(line);
        if (!result.isValid()) {
            computer.onRejected(lineNo, result.rejectReason, result.instrument);
            return;
        }
        computer.onInput(result.row);
    }
}

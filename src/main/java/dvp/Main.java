package dvp;

import java.nio.file.Paths;
import java.util.Random;

/**
 * CLI entry point and composition root. Parses arguments into a Config, then constructs the
 * ConflatingBuffer, the AuditLog, the MarketDataPublisher, and the DerivedValueSubscriber
 * and wires them into a DerivedValuePipeline. Swapping in a different publisher/subscriber
 * (a different data source, a different downstream sink) means changing only the lines
 * below -- DerivedValuePipeline itself never needs to change.
 */
public class Main {

    private static Config parseArgs(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: Main <input.csv> "
                    + "[--output <path>] [--audit <path>] [--input-delay-ms N] "
                    + "[--output-delay-ms N] [--jitter-pct P] [--seed N] [--lock-free]");
            return null;
        }
        Config.Builder builder = Config.builder(Paths.get(args[0]));
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--output":
                    builder.outputPath(Paths.get(args[++i]));
                    break;
                case "--audit":
                    builder.auditPath(Paths.get(args[++i]));
                    break;
                case "--input-delay-ms":
                    builder.inputDelayMs(Long.parseLong(args[++i]));
                    break;
                case "--output-delay-ms":
                    builder.outputDelayMs(Long.parseLong(args[++i]));
                    break;
                case "--jitter-pct":
                    builder.jitterPct(Double.parseDouble(args[++i]));
                    break;
                case "--seed":
                    builder.seed(Long.parseLong(args[++i]));
                    break;
                case "--lock-free":
                    builder.lockFree(true);
                    break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
            }
        }
        return builder.build();
    }

    public static void main(String[] args) throws InterruptedException {
        Config cfg = parseArgs(args);
        if (cfg == null) {
            return;
        }

        Random producerRng = new Random(cfg.seed);
        Random consumerRng = new Random(cfg.seed + 1);

        ConflatingBuffer buffer = cfg.lockFree ? new LockFreeConflatingBuffer() : new LockedConflatingBuffer();

        AuditLog auditLog = new CsvAuditLog(cfg.auditPath);
        DerivedValueComputer computer = new DerivedValueComputer(buffer, auditLog);

        MarketDataPublisher publisher =
                new CsvMarketDataPublisher(cfg.inputPath, cfg.inputDelayMs, cfg.jitterPct, producerRng);

        DerivedValueListener listener = new CsvDerivedValueListener(cfg.outputPath);
        DerivedValueSubscriber subscriber =
                new BufferDrainingSubscriber(buffer, listener, cfg.outputDelayMs, cfg.jitterPct, consumerRng);

        new DerivedValuePipeline(publisher, subscriber, computer).run();

        System.err.println("Done. Published output written to '" + cfg.outputPath.toAbsolutePath()
                + "', audit trail written to '" + cfg.auditPath.toAbsolutePath()
                + "' using '" + (cfg.lockFree ? "lock-free" : "locked") + "' buffer.");
    }
}

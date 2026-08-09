package dvp;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Immutable settings for one pipeline run. Construct via {@link #builder(Path)} rather than
 * a public constructor -- this keeps the required field (inputPath) enforced at compile/call
 * time while every other setting has a sensible default and reads clearly at the call site,
 * e.g. {@code Config.builder(path).outputDelayMs(200).jitterPct(0.2).build()}.
 */
public final class Config {
    public final Path inputPath;
    public final Path outputPath;
    public final Path auditPath;
    public final long inputDelayMs;
    public final long outputDelayMs;
    public final double jitterPct;
    public final long seed;
    public final boolean lockFree;

    private Config(Builder b) {
        this.inputPath = b.inputPath;
        this.outputPath = b.outputPath;
        this.auditPath = b.auditPath;
        this.inputDelayMs = b.inputDelayMs;
        this.outputDelayMs = b.outputDelayMs;
        this.jitterPct = b.jitterPct;
        this.seed = b.seed;
        this.lockFree = b.lockFree;
    }

    public static Builder builder(Path inputPath) {
        return new Builder(inputPath);
    }

    public static final class Builder {
        private final Path inputPath;
        private Path outputPath = Paths.get("published_output.csv");
        private Path auditPath = Paths.get("audit.csv");
        private long inputDelayMs = 1;
        private long outputDelayMs = 3;
        private double jitterPct = 0.0;
        private long seed = System.nanoTime();
        private boolean lockFree = false;

        private Builder(Path inputPath) {
            if (inputPath == null) {
                throw new IllegalArgumentException("inputPath is required");
            }
            this.inputPath = inputPath;
        }

        public Builder outputPath(Path outputPath) {
            this.outputPath = outputPath;
            return this;
        }

        public Builder auditPath(Path auditPath) {
            this.auditPath = auditPath;
            return this;
        }

        public Builder inputDelayMs(long inputDelayMs) {
            this.inputDelayMs = inputDelayMs;
            return this;
        }

        public Builder outputDelayMs(long outputDelayMs) {
            this.outputDelayMs = outputDelayMs;
            return this;
        }

        public Builder jitterPct(double jitterPct) {
            this.jitterPct = jitterPct;
            return this;
        }

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        public Builder lockFree(boolean lockFree) {
            this.lockFree = lockFree;
            return this;
        }

        public Config build() {
            return new Config(this);
        }
    }
}

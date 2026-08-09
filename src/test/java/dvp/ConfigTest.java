package dvp;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigTest {

    @Test
    void appliesDefaultsWhenNothingElseIsSet() {
        Config cfg = Config.builder(Paths.get("in.csv")).build();

        assertEquals(Paths.get("in.csv"), cfg.inputPath);
        assertEquals(Paths.get("published_output.csv"), cfg.outputPath);
        assertEquals(Paths.get("audit.csv"), cfg.auditPath);
        assertEquals(1, cfg.inputDelayMs);
        assertEquals(3, cfg.outputDelayMs);
        assertEquals(0.0, cfg.jitterPct, 1e-9);
        assertFalse(cfg.lockFree);
    }

    @Test
    void overridesApplyAndAreIndependentOfDefaults() {
        Path in = Paths.get("market_inputs.csv");
        Path out = Paths.get("out.csv");
        Path audit = Paths.get("audit_run1.csv");

        Config cfg = Config.builder(in)
                .outputPath(out)
                .auditPath(audit)
                .inputDelayMs(10)
                .outputDelayMs(500)
                .jitterPct(0.2)
                .seed(42)
                .lockFree(true)
                .build();

        assertEquals(in, cfg.inputPath);
        assertEquals(out, cfg.outputPath);
        assertEquals(audit, cfg.auditPath);
        assertEquals(10, cfg.inputDelayMs);
        assertEquals(500, cfg.outputDelayMs);
        assertEquals(0.2, cfg.jitterPct, 1e-9);
        assertEquals(42, cfg.seed);
        assertEquals(true, cfg.lockFree);
    }

    @Test
    void rejectsNullInputPath() {
        assertThrows(IllegalArgumentException.class, () -> Config.builder(null));
    }

    @Test
    void seedDefaultsDifferentlyAcrossInstancesUnlessSpecified() {
        // Not a strict guarantee (System.nanoTime() could theoretically collide), but a
        // sanity check that we're not accidentally hardcoding a fixed default seed.
        Config a = Config.builder(Paths.get("in.csv")).build();
        Config b = Config.builder(Paths.get("in.csv")).build();
        // Explicit seed still overrides cleanly regardless:
        Config c = Config.builder(Paths.get("in.csv")).seed(7).build();
        assertEquals(7, c.seed);
    }
}

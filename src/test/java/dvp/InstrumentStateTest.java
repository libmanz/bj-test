package dvp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstrumentStateTest {

    @Test
    void notCompleteUntilAllThreeInputsSeen() {
        InstrumentState s = new InstrumentState();
        assertFalse(s.isComplete());

        s.apply("base_rate", 1.0, 100);
        assertFalse(s.isComplete());

        s.apply("spread", 0.5, 200);
        assertFalse(s.isComplete());

        s.apply("adjustment", -0.25, 300);
        assertTrue(s.isComplete());
    }

    @Test
    void derivesSumOfThreeInputs() {
        InstrumentState s = new InstrumentState();
        s.apply("base_rate", 1.0, 100);
        s.apply("spread", 0.5, 200);
        s.apply("adjustment", -0.25, 300);
        assertEquals(1.25, s.derive(), 1e-9);
    }

    @Test
    void derivingBeforeCompleteThrows() {
        InstrumentState s = new InstrumentState();
        s.apply("base_rate", 1.0, 100);
        assertThrows(IllegalStateException.class, s::derive);
    }

    @Test
    void laterUpdateOverwritesEarlierOneForSameType() {
        // Models a duplicate/late/corrected update for the same (instrument, input_type).
        InstrumentState s = new InstrumentState();
        s.apply("base_rate", 1.0, 100);
        s.apply("base_rate", 2.0, 150); // supersedes the first
        s.apply("spread", 0.0, 200);
        s.apply("adjustment", 0.0, 300);
        assertEquals(2.0, s.derive(), 1e-9);
    }

    @Test
    void tracksMostRecentSourceTimestamp() {
        InstrumentState s = new InstrumentState();
        s.apply("base_rate", 1.0, 100);
        s.apply("spread", 0.5, 500);
        assertEquals(500, s.lastSourceTsMs());
    }

    @Test
    void unknownInputTypeThrows() {
        InstrumentState s = new InstrumentState();
        assertThrows(IllegalArgumentException.class, () -> s.apply("discount_factor", 1.0, 100));
    }
}

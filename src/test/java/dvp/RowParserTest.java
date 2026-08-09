package dvp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowParserTest {

    @Test
    void acceptsWellFormedRow() {
        RowParser.Result r = RowParser.parse("1690000000000,AAPL,base_rate,1.23");
        assertTrue(r.isValid());
        assertEquals("AAPL", r.row.instrument);
        assertEquals("base_rate", r.row.inputType);
        assertEquals(1.23, r.row.value, 1e-9);
        assertEquals(1690000000000L, r.row.timestampMs);
    }

    @Test
    void toleratesSurroundingWhitespace() {
        RowParser.Result r = RowParser.parse(" 1690000000000 , AAPL , base_rate , 1.23 ");
        assertTrue(r.isValid());
        assertEquals("AAPL", r.row.instrument);
    }

    @Test
    void rejectsWrongColumnCount() {
        RowParser.Result r = RowParser.parse("1690000000000,AAPL,base_rate");
        assertFalse(r.isValid());
        assertTrue(r.rejectReason.contains("4 columns"));
    }

    @Test
    void rejectsExtraColumns() {
        RowParser.Result r = RowParser.parse("1690000000000,AAPL,base_rate,1.23,extra");
        assertFalse(r.isValid());
    }

    @Test
    void rejectsNonNumericTimestamp() {
        RowParser.Result r = RowParser.parse("not-a-timestamp,AAPL,base_rate,1.23");
        assertFalse(r.isValid());
    }

    @Test
    void rejectsEmptyInstrument() {
        RowParser.Result r = RowParser.parse("1690000000000,,base_rate,1.23");
        assertFalse(r.isValid());
    }

    @Test
    void rejectsUnknownInputType() {
        RowParser.Result r = RowParser.parse("1690000000000,AAPL,discount_factor,1.23");
        assertFalse(r.isValid());
    }

    @Test
    void inputTypeIsCaseSensitive() {
        // Documenting current behavior: "Base_Rate" is rejected, not silently normalized.
        // Worth a deliberate choice, not an oversight.
        RowParser.Result r = RowParser.parse("1690000000000,AAPL,Base_Rate,1.23");
        assertFalse(r.isValid());
    }

    @Test
    void rejectsNonNumericValue() {
        RowParser.Result r = RowParser.parse("1690000000000,AAPL,base_rate,not-a-number");
        assertFalse(r.isValid());
    }

    @Test
    void rejectionAttributesTheInstrumentWhenTheColumnWasReadable() {
        // Needed for the audit trail to attribute a rejected row to a specific instrument.
        RowParser.Result r = RowParser.parse("1690000000000,AAPL,base_rate,not-a-number");
        assertFalse(r.isValid());
        assertEquals("AAPL", r.instrument);
    }

    @Test
    void rejectionHasNoInstrumentWhenColumnCountIsWrong() {
        // The instrument column was never reachable, so there's nothing to attribute to --
        // this becomes an "unattributed" rejection in the audit trail's global summary.
        RowParser.Result r = RowParser.parse("1690000000000,AAPL,base_rate");
        assertFalse(r.isValid());
        assertNull(r.instrument);
    }

    @Test
    void rejectsLiteralNaNAndInfinityValues() {
        // Double.parseDouble happily accepts these literal strings as valid doubles --
        // this is the rough edge the explicit isNaN/isInfinite check exists to catch.
        assertFalse(RowParser.parse("1690000000000,AAPL,base_rate,NaN").isValid());
        assertFalse(RowParser.parse("1690000000000,AAPL,base_rate,Infinity").isValid());
        assertFalse(RowParser.parse("1690000000000,AAPL,base_rate,-Infinity").isValid());
    }

    @Test
    void acceptsNegativeAndZeroValues() {
        assertTrue(RowParser.parse("1690000000000,AAPL,adjustment,-0.5").isValid());
        assertTrue(RowParser.parse("1690000000000,AAPL,adjustment,0").isValid());
    }
}

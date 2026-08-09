package dvp;

import java.util.Set;

/**
 * Parses one CSV line into a validated row, or rejects it with a reason. Deliberately a pure
 * function with no file I/O or logging so the "rough edges" handling can be unit tested
 * directly against input strings, without needing a real file on disk.
 */
public final class RowParser {

    public static final Set<String> VALID_TYPES = Set.of("base_rate", "spread", "adjustment");

    private RowParser() {
    }

    public static final class ParsedRow {
        public final long timestampMs;
        public final String instrument;
        public final String inputType;
        public final double value;

        public ParsedRow(long timestampMs, String instrument, String inputType, double value) {
            this.timestampMs = timestampMs;
            this.instrument = instrument;
            this.inputType = inputType;
            this.value = value;
        }
    }

    public static final class Result {
        public final ParsedRow row;       // null if rejected
        public final String rejectReason; // null if accepted
        public final String instrument;   // populated on rejection when the column was
                                           // readable; null if rejected before that point
                                           // (e.g. wrong column count) or if valid (use row.instrument)

        private Result(ParsedRow row, String rejectReason, String instrument) {
            this.row = row;
            this.rejectReason = rejectReason;
            this.instrument = instrument;
        }

        public static Result ok(ParsedRow row) {
            return new Result(row, null, null);
        }

        /** Rejection where the instrument column was never reached (e.g. wrong column count). */
        public static Result reject(String reason) {
            return new Result(null, reason, null);
        }

        /** Rejection where the instrument column was readable, so the audit trail can
         *  attribute this rejection to a specific instrument. */
        public static Result reject(String reason, String instrument) {
            return new Result(null, reason, instrument);
        }

        public boolean isValid() {
            return row != null;
        }
    }

    public static Result parse(String line) {
        if (line == null) {
            return Result.reject("null line");
        }
        String[] parts = line.split(",", -1);
        if (parts.length != 4) {
            return Result.reject("expected 4 columns, got " + parts.length);
        }

        String tsRaw = parts[0].trim();
        String instrument = parts[1].trim();
        String inputType = parts[2].trim();
        String valueRaw = parts[3].trim();

        long ts;
        try {
            ts = Long.parseLong(tsRaw);
        } catch (NumberFormatException e) {
            return Result.reject("bad timestamp '" + tsRaw + "'", instrument);
        }

        if (instrument.isEmpty()) {
            return Result.reject("empty instrument");
        }

        if (!VALID_TYPES.contains(inputType)) {
            return Result.reject("unknown input_type '" + inputType + "'", instrument);
        }

        double value;
        try {
            value = Double.parseDouble(valueRaw);
        } catch (NumberFormatException e) {
            return Result.reject("non-numeric value '" + valueRaw + "'", instrument);
        }
        // Double.parseDouble happily accepts the literal strings "NaN" and "Infinity" as valid
        // doubles -- that's a genuine rough edge worth guarding explicitly rather than letting
        // a bad row silently poison a derived_value sum downstream.
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Result.reject("non-finite value '" + valueRaw + "'", instrument);
        }

        return Result.ok(new ParsedRow(ts, instrument, inputType, value));
    }
}

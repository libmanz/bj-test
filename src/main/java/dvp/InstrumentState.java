package dvp;

/** Running state for one instrument: the latest value seen for each of the three input types. */
public final class InstrumentState {
    private Double baseRate;
    private Double spread;
    private Double adjustment;
    private long lastSourceTsMs = -1;

    public void apply(String inputType, double value, long tsMs) {
        switch (inputType) {
            case "base_rate":
                baseRate = value;
                break;
            case "spread":
                spread = value;
                break;
            case "adjustment":
                adjustment = value;
                break;
            default:
                throw new IllegalArgumentException("unknown input_type: " + inputType);
        }
        lastSourceTsMs = tsMs;
    }

    public boolean isComplete() {
        return baseRate != null && spread != null && adjustment != null;
    }

    public double derive() {
        if (!isComplete()) {
            throw new IllegalStateException("derive() called before all three inputs were present");
        }
        return baseRate + spread + adjustment;
    }

    public long lastSourceTsMs() {
        return lastSourceTsMs;
    }

    /** Names which of the three inputs have never arrived, for audit reporting. Only
     *  meaningful when !isComplete(). */
    public String missingInputsDescription() {
        StringBuilder missing = new StringBuilder();
        if (baseRate == null) {
            missing.append("base_rate ");
        }
        if (spread == null) {
            missing.append("spread ");
        }
        if (adjustment == null) {
            missing.append("adjustment ");
        }
        return missing.toString().trim();
    }
}

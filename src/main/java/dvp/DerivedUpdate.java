package dvp;

/** A computed derived value ready to publish, tagged with the input event time that produced it. */
public final class DerivedUpdate {
    public final String instrument;
    public final double value;
    public final long sourceTsMs;

    public DerivedUpdate(String instrument, double value, long sourceTsMs) {
        this.instrument = instrument;
        this.value = value;
        this.sourceTsMs = sourceTsMs;
    }
}

package dev.astra.client;

/** Constant-memory histogram: 32 subdivisions per power of two, reporting bucket upper bounds. */
public final class LatencyHistogram {
    private final long[] bins = new long[64 * 32];
    private long count;
    public void record(long nanos) {
        long value = Math.max(1, nanos);
        int exponent = 63 - Long.numberOfLeadingZeros(value);
        long base = 1L << exponent;
        int fraction = (int) (((double) value / base - 1) * 32);
        bins[exponent * 32 + Math.min(31, fraction)]++;
        count++;
    }
    public void merge(LatencyHistogram other) {
        for (int i = 0; i < bins.length; i++) bins[i] += other.bins[i];
        count += other.count;
    }
    public long count() { return count; }
    public double percentileMillis(double percentile) {
        if (count == 0) return 0;
        long rank = Math.max(1, (long) Math.ceil(count * percentile / 100)), cumulative = 0;
        for (int i = 0; i < bins.length; i++) {
            cumulative += bins[i];
            if (cumulative >= rank) return Math.scalb(1.0 + (i % 32 + 1) / 32.0, i / 32) / 1e6;
        }
        throw new IllegalStateException("histogram count mismatch");
    }
}

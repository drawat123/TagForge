package com.tagforge.simulator;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/**
 * Written from every device thread, read once a second by the reporter.
 *
 * LongAdder rather than AtomicLong: under heavy concurrent increment it keeps
 * per-thread cells and sums on read, instead of every thread contending on one
 * cache line. A metrics bottleneck would look exactly like a server bottleneck.
 */
public class Metrics {

    private final LongAdder published = new LongAdder();
    private final LongAdder acknowledged = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder missedIntervals = new LongAdder();

    /** Hands out a fresh histogram per interval without blocking writers. */
    private final Recorder ackLatency = new Recorder(3);

    private long lastPublished;
    private long lastAcknowledged;
    private long lastFailed;
    private long lastMissed;
    private long lastReportNanos = System.nanoTime();

    public void recordPublished() {
        published.increment();
    }

    public void recordAcknowledged(long latencyNanos) {
        acknowledged.increment();
        ackLatency.recordValue(TimeUnit.NANOSECONDS.toMicros(latencyNanos));
    }

    public void recordFailed() {
        failed.increment();
    }

    /** A device that could not keep up with its interval. Silence here would hide it. */
    public void recordMissedInterval() {
        missedIntervals.increment();
    }

    /**
     * One line covering the interval since the previous call, not the whole run.
     * A cumulative average hides the moment things start to degrade.
     */
    public String reportLine() {
        long nowPublished = published.sum();
        long nowAcknowledged = acknowledged.sum();
        long nowFailed = failed.sum();
        long nowMissed = missedIntervals.sum();

        long nowNanos = System.nanoTime();
        double seconds = (nowNanos - lastReportNanos) / 1_000_000_000.0;

        long publishedDelta = nowPublished - lastPublished;
        long acknowledgedDelta = nowAcknowledged - lastAcknowledged;
        long failedDelta = nowFailed - lastFailed;
        long missedDelta = nowMissed - lastMissed;

        lastPublished = nowPublished;
        lastAcknowledged = nowAcknowledged;
        lastFailed = nowFailed;
        lastMissed = nowMissed;
        lastReportNanos = nowNanos;

        // Samples since the previous call, then reset.
        Histogram interval = ackLatency.getIntervalHistogram();

        // Published minus acknowledged is the backpressure signal: if it grows
        // without bound the broker is not keeping up with what we are sending.
        long inFlight = nowPublished - nowAcknowledged;

        return String.format(
                "pub %6.0f/s | ack %6.0f/s | inflight %7d | failed %5d | missed %5d | "
                        + "ack p50 %6.2f ms  p99 %7.2f ms  max %7.2f ms",
                publishedDelta / seconds,
                acknowledgedDelta / seconds,
                inFlight,
                failedDelta,
                missedDelta,
                interval.getValueAtPercentile(50.0) / 1000.0,
                interval.getValueAtPercentile(99.0) / 1000.0,
                interval.getMaxValue() / 1000.0);
    }
}

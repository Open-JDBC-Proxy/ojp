package org.openjproxy.grpc.server.profiling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records nanosecond-granularity timings for each named step in an execution path.
 * <p>
 * Each call to {@link #mark(String)} records the elapsed nanoseconds since the
 * previous mark (or since {@link #start()} if this is the first mark). This
 * produces a sequential list of per-step durations.
 * </p>
 * <p>
 * Instances are not thread-safe; create one per thread via
 * {@link ExecutionPathProfilingContext}.
 * </p>
 */
public class ExecutionPathProfiler {

    /** Ordered list of step timings accumulated during one execution. */
    private final List<StepTiming> timings = new ArrayList<>();

    /** Nanosecond timestamp of the last mark (or start). */
    private long lastMarkNs;

    /**
     * Sets the starting reference timestamp. Must be called before the first
     * {@link #mark(String)} call.
     */
    public void start() {
        this.lastMarkNs = System.nanoTime();
    }

    /**
     * Records the nanoseconds elapsed since the last mark (or since {@link #start()})
     * under the given step name, then advances the reference timestamp.
     *
     * @param stepName human-readable label for this step
     */
    public void mark(String stepName) {
        long now = System.nanoTime();
        timings.add(new StepTiming(stepName, now - lastMarkNs));
        lastMarkNs = now;
    }

    /**
     * Advances the reference timestamp by {@code ns} nanoseconds, effectively
     * excluding that duration from the current step's measurement window.
     * <p>
     * Use this to discount time spent inside external JDBC calls so that the
     * subsequent {@link #mark(String)} records only OJP-internal overhead.
     * </p>
     *
     * @param ns nanoseconds to exclude from the current step's elapsed time
     */
    public void excludeNs(long ns) {
        this.lastMarkNs += ns;
    }

    /**
     * Returns an unmodifiable view of all recorded step timings in insertion order.
     *
     * @return list of step timings
     */
    public List<StepTiming> getTimings() {
        return Collections.unmodifiableList(timings);
    }

    /**
     * Immutable value object representing the duration of one named step.
     */
    public static final class StepTiming {

        private final String stepName;
        private final long durationNs;

        /**
         * Creates a new StepTiming.
         *
         * @param stepName   the name of the step
         * @param durationNs the duration in nanoseconds
         */
        public StepTiming(String stepName, long durationNs) {
            this.stepName = stepName;
            this.durationNs = durationNs;
        }

        /**
         * Returns the step name.
         *
         * @return step name
         */
        public String getStepName() {
            return stepName;
        }

        /**
         * Returns the step duration in nanoseconds.
         *
         * @return duration in nanoseconds
         */
        public long getDurationNs() {
            return durationNs;
        }
    }
}

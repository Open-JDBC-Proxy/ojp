package org.openjproxy.grpc.server.profiling;

/**
 * Thread-local holder for an active {@link ExecutionPathProfiler}.
 * <p>
 * Production code calls {@link #mark(String)} at each instrumentation point; the
 * call is a no-op unless a profiler has been {@link #activate(ExecutionPathProfiler)
 * activated} on the current thread by a test or diagnostic harness. This design
 * avoids any allocation or synchronization overhead in the production hot path.
 * </p>
 *
 * <h2>Typical test usage</h2>
 * <pre>{@code
 * ExecutionPathProfiler profiler = new ExecutionPathProfiler();
 * ExecutionPathProfilingContext.activate(profiler);
 * try {
 *     action.execute(context, request, observer);
 * } finally {
 *     ExecutionPathProfilingContext.deactivate();
 * }
 * profiler.getTimings().forEach(t ->
 *     log.info("{}: {} ns", t.getStepName(), t.getDurationNs()));
 * }</pre>
 */
public final class ExecutionPathProfilingContext {

    private static final ThreadLocal<ExecutionPathProfiler> ACTIVE = new ThreadLocal<>();

    /** Start timestamp (ns) recorded by {@link #beginJdbcCall()}. */
    private static final ThreadLocal<Long> JDBC_CALL_START = new ThreadLocal<>();

    /** Utility class — no instances. */
    private ExecutionPathProfilingContext() {
    }

    /**
     * Activates the given profiler on the current thread and starts its timer.
     *
     * @param profiler the profiler to activate; must not be {@code null}
     */
    public static void activate(ExecutionPathProfiler profiler) {
        profiler.start();
        ACTIVE.set(profiler);
    }

    /**
     * Deactivates the current profiler and removes it from the thread-local.
     * Safe to call even when no profiler is active.
     */
    public static void deactivate() {
        ACTIVE.remove();
        JDBC_CALL_START.remove();
    }

    /**
     * Returns {@code true} if a profiler is currently active on this thread.
     *
     * @return whether profiling is active
     */
    public static boolean isActive() {
        return ACTIVE.get() != null;
    }

    /**
     * Records elapsed nanoseconds for the named step on the active profiler.
     * This is a no-op when no profiler is active.
     *
     * @param step the step label; use short, lowercase identifiers (e.g.
     *             {@code "sessionConnection"}, {@code "sqlExecution"})
     */
    public static void mark(String step) {
        ExecutionPathProfiler profiler = ACTIVE.get();
        if (profiler != null) {
            profiler.mark(step);
        }
    }

    /**
     * Marks the start of a JDBC driver call whose duration should be excluded
     * from the current step's OJP measurement.
     * <p>
     * Must be paired with a subsequent {@link #endJdbcCall()}. Nesting is not
     * supported: a second {@code beginJdbcCall()} before a matching
     * {@code endJdbcCall()} will overwrite the first timestamp. This is a no-op
     * when no profiler is active.
     * </p>
     */
    public static void beginJdbcCall() {
        if (ACTIVE.get() != null) {
            JDBC_CALL_START.set(System.nanoTime());
        }
    }

    /**
     * Marks the end of a JDBC driver call and subtracts its duration from the
     * current step's measurement window so that only OJP-internal time is counted.
     * <p>
     * Safe to call even when {@link #beginJdbcCall()} was not called first (no-op
     * in that case).
     * </p>
     */
    public static void endJdbcCall() {
        ExecutionPathProfiler profiler = ACTIVE.get();
        Long start = JDBC_CALL_START.get();
        if (profiler != null && start != null) {
            profiler.excludeNs(System.nanoTime() - start);
            JDBC_CALL_START.remove();
        }
    }
}

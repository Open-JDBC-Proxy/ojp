package org.openjproxy.grpc.server.profiling;

import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.StatementRequest;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.AdmissionControlManager;
import org.openjproxy.grpc.server.CircuitBreakerRegistry;
import org.openjproxy.grpc.server.ClusterHealthTracker;
import org.openjproxy.grpc.server.MultinodeXaCoordinator;
import org.openjproxy.grpc.server.ServerConfiguration;
import org.openjproxy.grpc.server.SessionManagerImpl;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.transaction.ExecuteQueryAction;
import org.openjproxy.grpc.server.action.transaction.ExecuteUpdateAction;
import org.openjproxy.grpc.server.metrics.SqlStatementMetrics;
import org.openjproxy.grpc.server.sql.SqlEnhancerEngine;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Open-loop concurrency benchmark for the {@code executeQuery} and
 * {@code executeUpdate} server-side action paths.
 *
 * <p>Fires {@value #CONCURRENT_REQUESTS} simultaneous requests at each path
 * using an H2 in-memory database, records nanosecond-level timings for every
 * instrumented step, then prints median latencies as an ASCII bar chart to
 * stdout.</p>
 *
 * <p><b>This test is for assessment purposes only and is not intended to be
 * merged to the main branch.</b></p>
 *
 * <p><b>executeQuery steps instrumented:</b></p>
 * <ol>
 *   <li>{@code sessionConnection} – connection look-up from the session map (pre-created sessions)</li>
 *   <li>{@code cacheCheck} – query-result cache check (cache disabled here)</li>
 *   <li>{@code sqlEnhancement} – Calcite SQL enhancer (disabled here)</li>
 *   <li>{@code paramConversion} – proto-parameter deserialisation</li>
 *   <li>{@code statementCreation} – {@code Statement} / {@code PreparedStatement} creation</li>
 *   <li>{@code sqlExecution} – actual {@code executeQuery()} JDBC call</li>
 *   <li>{@code resultSetHandling} – row iteration and gRPC streaming</li>
 * </ol>
 *
 * <p><b>executeUpdate steps instrumented:</b></p>
 * <ol>
 *   <li>{@code affinityCheck} – session-affinity and generated-keys detection</li>
 *   <li>{@code sessionConnection} – connection look-up from the session map (pre-created sessions)</li>
 *   <li>{@code paramConversion} – proto-parameter deserialisation + prepared-statement look-up</li>
 *   <li>{@code statementCreation} – {@code Statement} / {@code PreparedStatement} creation</li>
 *   <li>{@code sqlExecution} – actual {@code executeUpdate()} JDBC call</li>
 *   <li>{@code buildResult} – result object construction + cache invalidation</li>
 * </ol>
 */
class ExecutePathLatencyBenchmarkTest {

    // Number of concurrent requests fired in the open-loop burst
    private static final int CONCURRENT_REQUESTS = 100;
    // Number of sequential warmup iterations (JIT warm-up)
    private static final int WARMUP_REQUESTS = 20;
    // Total pre-created sessions = warmup + concurrent
    private static final int TOTAL_SESSIONS = WARMUP_REQUESTS + CONCURRENT_REQUESTS;

    private static final String DB_URL = "jdbc:h2:mem:profiling_bench;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    private static final String CONN_HASH = "benchmark-h2";
    // Large multiplier to avoid row-ID collisions between the warmup and concurrent runs
    private static final int UPDATE_ID_MULTIPLIER = 100_000;
    private static final String SELECT_SQL = "SELECT id, val FROM bench";

    private static ActionContext actionContext;
    /** Pre-created sessions indexed [0 .. TOTAL_SESSIONS). */
    private static final List<SessionInfo> PRE_SESSIONS = new ArrayList<>(TOTAL_SESSIONS);
    /** Direct H2 connections backing each pre-created session; closed in teardown. */
    private static final List<Connection> PRE_CONNECTIONS = new ArrayList<>(TOTAL_SESSIONS);

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @BeforeAll
    static void setUpAll() throws Exception {
        // Bootstrap table via a standalone connection
        try (Connection bootstrap = DriverManager.getConnection(DB_URL, "sa", "")) {
            try (Statement stmt = bootstrap.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS bench (id INT, val VARCHAR(100))");
                stmt.execute("DELETE FROM bench");
                for (int i = 1; i <= 10; i++) {
                    stmt.execute("INSERT INTO bench (id, val) VALUES (" + i + ", 'row" + i + "')");
                }
            }
        }

        ServerConfiguration serverConfiguration = new ServerConfiguration();
        SessionManagerImpl sessionManager = new SessionManagerImpl(new ConcurrentHashMap<>());

        // Pre-create one direct H2 connection per virtual client and register a session
        for (int i = 0; i < TOTAL_SESSIONS; i++) {
            String clientUUID = clientUUID(i);
            sessionManager.registerClientUUID(CONN_HASH, clientUUID);
            Connection conn = DriverManager.getConnection(DB_URL, "sa", "");
            PRE_CONNECTIONS.add(conn);
            SessionInfo session = sessionManager.createSession(clientUUID, conn);
            PRE_SESSIONS.add(session);
        }

        // datasourceMap left empty: all sessions are pre-created, so no pool acquisition occurs.
        Map<String, DataSource> datasourceMap = new ConcurrentHashMap<>();
        Map<String, AdmissionControlManager> admissionManagers = new ConcurrentHashMap<>();
        // Disabled admission control so that slot queuing is excluded from the measured steps.
        // Parameters: totalSlots=TOTAL_SESSIONS, slowSlotPct=0 (disabled), idleTimeoutMs=0,
        //             slowSlotTimeoutMs=0, fastSlotTimeoutMs=0, enabled=false
        admissionManagers.put(CONN_HASH,
                new AdmissionControlManager(TOTAL_SESSIONS, 0, 0, 0, 0, false));

        Map<String, DbName> dbNameMap = new ConcurrentHashMap<>();
        dbNameMap.put(CONN_HASH, DbName.H2);

        CircuitBreakerRegistry circuitBreakerRegistry = new CircuitBreakerRegistry(
                serverConfiguration.getCircuitBreakerTimeout(),
                serverConfiguration.getCircuitBreakerThreshold());

        SqlStatementMetrics noopMetrics = new SqlStatementMetrics() {
            @Override
            public void recordSqlExecution(String sql, long executionTimeMs, boolean isSlow) {
                // no-op for benchmark
            }

            @Override
            public void close() {
                // no-op
            }
        };

        actionContext = new ActionContext(
                datasourceMap,
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                dbNameMap,
                admissionManagers,
                new ConcurrentHashMap<>(),
                mock(XAConnectionPoolProvider.class),
                new MultinodeXaCoordinator(),
                new ClusterHealthTracker(),
                sessionManager,
                circuitBreakerRegistry,
                serverConfiguration,
                noopMetrics,
                new SqlEnhancerEngine(false));
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        for (Connection conn : PRE_CONNECTIONS) {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Benchmark tests
    // -----------------------------------------------------------------------

    @Test
    void shouldBenchmarkExecuteQueryPath() throws Exception {
        System.out.println("\n=== executeQuery benchmark ===");

        // Sequential warmup using the first WARMUP_REQUESTS sessions
        runExecuteQuerySequential(0, WARMUP_REQUESTS);
        System.out.println("[warmup done: " + WARMUP_REQUESTS + " sequential requests]");

        // Concurrent open-loop burst using the remaining CONCURRENT_REQUESTS sessions
        List<ExecutionPathProfiler> profilers = runExecuteQueryConcurrent(WARMUP_REQUESTS);

        assertTrue(profilers.stream().noneMatch(p -> p == null || p.getTimings().isEmpty()),
                "All profilers should have recorded timings");
        printReport("executeQuery path", profilers);
    }

    @Test
    void shouldBenchmarkExecuteUpdatePath() throws Exception {
        System.out.println("\n=== executeUpdate benchmark ===");

        // Sequential warmup
        runExecuteUpdateSequential(0, WARMUP_REQUESTS);
        System.out.println("[warmup done: " + WARMUP_REQUESTS + " sequential requests]");

        // Concurrent open-loop burst
        List<ExecutionPathProfiler> profilers = runExecuteUpdateConcurrent(WARMUP_REQUESTS);

        assertTrue(profilers.stream().noneMatch(p -> p == null || p.getTimings().isEmpty()),
                "All profilers should have recorded timings");
        printReport("executeUpdate path", profilers);
    }

    // -----------------------------------------------------------------------
    // executeQuery helpers
    // -----------------------------------------------------------------------

    private void runExecuteQuerySequential(int startIdx, int count) throws Exception {
        for (int i = startIdx; i < startIdx + count; i++) {
            StatementRequest request = StatementRequest.newBuilder()
                    .setSession(PRE_SESSIONS.get(i))
                    .setSql(SELECT_SQL)
                    .build();
            ExecuteQueryAction.getInstance().execute(actionContext, request, new DroppingObserver());
        }
    }

    /**
     * Fires {@value #CONCURRENT_REQUESTS} executeQuery requests simultaneously from
     * {@code startIdx} onward. Returns one profiler per request.
     */
    private List<ExecutionPathProfiler> runExecuteQueryConcurrent(int startIdx) throws Exception {
        return runConcurrentBurst(startIdx, CONCURRENT_REQUESTS, idx -> {
            StatementRequest request = StatementRequest.newBuilder()
                    .setSession(PRE_SESSIONS.get(idx))
                    .setSql(SELECT_SQL)
                    .build();
            ExecuteQueryAction.getInstance().execute(actionContext, request, new DroppingObserver());
        });
    }

    // -----------------------------------------------------------------------
    // executeUpdate helpers
    // -----------------------------------------------------------------------

    private void runExecuteUpdateSequential(int startIdx, int count) throws Exception {
        for (int i = startIdx; i < startIdx + count; i++) {
            String sql = buildInsertSql(i);
            StatementRequest request = StatementRequest.newBuilder()
                    .setSession(PRE_SESSIONS.get(i))
                    .setSql(sql)
                    .build();
            ExecuteUpdateAction.getInstance().execute(actionContext, request, new DroppingObserver());
        }
    }

    /**
     * Fires {@value #CONCURRENT_REQUESTS} executeUpdate requests simultaneously from
     * {@code startIdx} onward. Returns one profiler per request.
     */
    private List<ExecutionPathProfiler> runExecuteUpdateConcurrent(int startIdx) throws Exception {
        return runConcurrentBurst(startIdx, CONCURRENT_REQUESTS, idx -> {
            String sql = buildInsertSql(idx * UPDATE_ID_MULTIPLIER);
            StatementRequest request = StatementRequest.newBuilder()
                    .setSession(PRE_SESSIONS.get(idx))
                    .setSql(sql)
                    .build();
            ExecuteUpdateAction.getInstance().execute(actionContext, request, new DroppingObserver());
        });
    }

    private static String buildInsertSql(int id) {
        return "INSERT INTO bench (id, val) VALUES (" + id + ", 'bench')";
    }

    // -----------------------------------------------------------------------
    // Generic open-loop burst driver
    // -----------------------------------------------------------------------

    /**
     * Runs {@code count} tasks simultaneously (open-loop burst) starting at
     * session index {@code startIdx}, activating a per-thread
     * {@link ExecutionPathProfiler} before each invocation.
     *
     * @param startIdx  first session index to use
     * @param count     number of concurrent tasks
     * @param task      action to execute; receives the absolute session index
     * @return profilers from all {@code count} tasks in index order
     */
    private List<ExecutionPathProfiler> runConcurrentBurst(int startIdx, int count, BenchTask task)
            throws Exception {

        List<ExecutionPathProfiler> profilers =
                new ArrayList<>(Collections.nCopies(count, null));
        List<AtomicReference<Exception>> errors = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            errors.add(new AtomicReference<>());
        }

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(count);
        ExecutorService executor = Executors.newFixedThreadPool(count);

        for (int i = 0; i < count; i++) {
            final int taskIdx = startIdx + i;
            final int resultIdx = i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    ExecutionPathProfiler profiler = new ExecutionPathProfiler();
                    ExecutionPathProfilingContext.activate(profiler);
                    try {
                        task.run(taskIdx);
                    } finally {
                        ExecutionPathProfilingContext.deactivate();
                    }
                    profilers.set(resultIdx, profiler);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    errors.get(resultIdx).set(ie);
                } catch (Exception e) {
                    errors.get(resultIdx).set(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Open loop: release all threads simultaneously
        startGate.countDown();
        boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "Benchmark burst did not finish within 60 s");
        for (int i = 0; i < count; i++) {
            Exception err = errors.get(i).get();
            if (err != null) {
                throw new AssertionError("Thread " + i + " failed: " + err.getMessage(), err);
            }
        }
        return profilers;
    }

    // -----------------------------------------------------------------------
    // Reporting: collect medians and print ASCII bar chart
    // -----------------------------------------------------------------------

    /**
     * Aggregates per-step nanosecond timings from all profilers, computes the
     * median across {@value #CONCURRENT_REQUESTS} samples for each step, and
     * prints an ASCII bar chart.
     */
    private void printReport(String title, List<ExecutionPathProfiler> profilers) {
        // Collect durations per step (preserve insertion order via LinkedHashMap)
        Map<String, List<Long>> stepData = new LinkedHashMap<>();
        for (ExecutionPathProfiler profiler : profilers) {
            for (ExecutionPathProfiler.StepTiming timing : profiler.getTimings()) {
                stepData.computeIfAbsent(timing.getStepName(), k -> new ArrayList<>())
                        .add(timing.getDurationNs());
            }
        }

        // Compute medians
        Map<String, Long> medians = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> entry : stepData.entrySet()) {
            List<Long> sorted = entry.getValue().stream().sorted().collect(Collectors.toList());
            int mid = sorted.size() / 2;
            long median = sorted.size() % 2 == 0
                    ? (sorted.get(mid - 1) + sorted.get(mid)) / 2
                    : sorted.get(mid);
            medians.put(entry.getKey(), median);
        }

        long maxMedian = medians.values().stream().mapToLong(Long::longValue).max().orElse(1L);

        int barWidth = 40;
        String totalRowLabel = "TOTAL (sum of medians)";
        int labelWidth = Math.max(
                medians.keySet().stream().mapToInt(String::length).max().orElse(10),
                totalRowLabel.length()) + 2;
        int totalWidth = barWidth + labelWidth + 26;

        System.out.println();
        System.out.println(center("Step Timing Report — " + title, totalWidth));
        System.out.println(center("median of " + CONCURRENT_REQUESTS + " concurrent requests (H2 in-memory)", totalWidth));
        System.out.println(repeat("-", totalWidth));

        for (Map.Entry<String, Long> entry : medians.entrySet()) {
            String step = entry.getKey();
            long medNs = entry.getValue();
            int bars = maxMedian == 0 ? 0 : (int) ((double) medNs / maxMedian * barWidth);
            String bar = repeat("█", bars) + repeat("░", barWidth - bars);
            System.out.printf("  %-" + labelWidth + "s |%s| %,9d ns  (%,.3f µs)%n",
                    step, bar, medNs, medNs / 1_000.0);
        }

        long totalNs = medians.values().stream().mapToLong(Long::longValue).sum();
        System.out.println(repeat("-", totalWidth));
        int totalBars = maxMedian == 0 ? 0 : (int) Math.min((double) totalNs / maxMedian * barWidth, barWidth);
        String totalBar = repeat("█", totalBars) + repeat("░", barWidth - totalBars);
        System.out.printf("  %-" + labelWidth + "s |%s| %,9d ns  (%,.3f µs)%n",
                totalRowLabel, totalBar, totalNs, totalNs / 1_000.0);
        System.out.println(repeat("-", totalWidth));

        // Summary line
        String heaviest = medians.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("?");
        System.out.printf("  Heaviest step: %s%n", heaviest);
        System.out.println();
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static String clientUUID(int index) {
        return "bench-client-" + index;
    }

    private static String repeat(String s, int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(count * s.length());
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    private static String center(String text, int width) {
        int pad = Math.max(0, (width - text.length()) / 2);
        return repeat(" ", pad) + text;
    }

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    /**
     * Task interface used by the open-loop burst driver.
     * Receives the session index and may throw checked exceptions.
     */
    @FunctionalInterface
    interface BenchTask {
        /**
         * Executes one benchmark iteration for the given session index.
         *
         * @param sessionIdx session index into {@link #PRE_SESSIONS}
         * @throws Exception on any error
         */
        void run(int sessionIdx) throws Exception;
    }

    /**
     * gRPC {@link StreamObserver} that silently discards all results, used to
     * avoid test-framework noise in the benchmark loop.
     */
    private static final class DroppingObserver implements StreamObserver<OpResult> {
        @Override
        public void onNext(OpResult value) {
            // intentionally discarded
        }

        @Override
        public void onError(Throwable t) {
            // errors handled via AtomicReference in the burst driver
        }

        @Override
        public void onCompleted() {
            // intentionally discarded
        }
    }
}

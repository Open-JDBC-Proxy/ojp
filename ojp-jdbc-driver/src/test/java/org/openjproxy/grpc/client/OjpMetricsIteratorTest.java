package org.openjproxy.grpc.client;

import com.openjproxy.grpc.OpResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.jdbc.OjpDriverMetrics;
import org.openjproxy.jdbc.OjpDriverMetricsHolder;

import java.util.Collections;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OjpMetricsIteratorTest {

    private long recordedDuration = -1;
    private int failedCount = 0;

    private final OjpDriverMetrics capturingMetrics = new OjpDriverMetrics() {
        @Override public void onConnectionCreated() {}
        @Override public void onConnectionFailed() {}
        @Override public void onConnectionClosed() {}
        @Override public void onStatementExecuted(long durationMs) { recordedDuration = durationMs; }
        @Override public void onStatementFailed() { failedCount++; }
    };

    @AfterEach
    void resetHolder() {
        OjpDriverMetricsHolder.reset();
    }

    @Test
    void recordsExecutedMetricAfterFullConsumption() {
        OjpDriverMetricsHolder.set(capturingMetrics);

        Iterator<OpResult> delegate = Collections.<OpResult>emptyList().iterator();
        OjpMetricsIterator iterator = new OjpMetricsIterator(delegate);

        boolean hasNext = iterator.hasNext();

        assertFalse(hasNext);
        assertTrue(recordedDuration >= 0, "duration should be >= 0 but was " + recordedDuration);
    }

    @Test
    void recordsExecutedMetricOnlyOnce() {
        OjpDriverMetricsHolder.set(capturingMetrics);

        Iterator<OpResult> delegate = Collections.<OpResult>emptyList().iterator();
        OjpMetricsIterator iterator = new OjpMetricsIterator(delegate);

        iterator.hasNext();
        recordedDuration = -99;
        iterator.hasNext();

        // Second hasNext() should not re-record the metric
        assertEquals(-99L, recordedDuration);
    }

    @Test
    void recordsFailedMetricWhenHasNextThrows() {
        OjpDriverMetricsHolder.set(capturingMetrics);

        Iterator<OpResult> failingDelegate = new Iterator<>() {
            @Override public boolean hasNext() { throw new RuntimeException("stream error"); }
            @Override public OpResult next() { return null; }
        };
        OjpMetricsIterator iterator = new OjpMetricsIterator(failingDelegate);

        assertThrows(RuntimeException.class, iterator::hasNext);
        assertEquals(1, failedCount);
    }

    @Test
    void recordsFailedMetricWhenNextThrows() {
        OjpDriverMetricsHolder.set(capturingMetrics);

        Iterator<OpResult> failingDelegate = new Iterator<>() {
            @Override public boolean hasNext() { return true; }
            @Override public OpResult next() { throw new RuntimeException("stream error"); }
        };
        OjpMetricsIterator iterator = new OjpMetricsIterator(failingDelegate);

        assertThrows(RuntimeException.class, iterator::next);
        assertEquals(1, failedCount);
    }

    @Test
    void recordsFailedMetricOnlyOnce() {
        OjpDriverMetricsHolder.set(capturingMetrics);

        Iterator<OpResult> failingDelegate = new Iterator<>() {
            @Override public boolean hasNext() { throw new RuntimeException("stream error"); }
            @Override public OpResult next() { return null; }
        };
        OjpMetricsIterator iterator = new OjpMetricsIterator(failingDelegate);

        assertThrows(RuntimeException.class, iterator::hasNext);
        assertThrows(RuntimeException.class, iterator::hasNext);

        assertEquals(1, failedCount);
    }

    @Test
    void delegatesHasNextAndNext() {
        OjpDriverMetricsHolder.set(capturingMetrics);

        OpResult result = OpResult.getDefaultInstance();
        Iterator<OpResult> delegate = Collections.singletonList(result).iterator();
        OjpMetricsIterator iterator = new OjpMetricsIterator(delegate);

        assertTrue(iterator.hasNext());
        assertSame(result, iterator.next());
        assertFalse(iterator.hasNext());
    }
}

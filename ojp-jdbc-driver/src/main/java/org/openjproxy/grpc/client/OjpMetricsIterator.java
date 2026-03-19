package org.openjproxy.grpc.client;

import com.openjproxy.grpc.OpResult;
import org.openjproxy.jdbc.OjpDriverMetricsHolder;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Iterator wrapper that records Micrometer metrics for a streaming gRPC query result.
 *
 * <p>For server-streaming RPCs ({@code executeQuery}), the gRPC stub returns an
 * {@link Iterator} whose elements arrive lazily over the network. Timing and error
 * metrics must therefore be measured across the full lifetime of the iterator, not
 * just at the moment the stub call is made.</p>
 *
 * <p>This wrapper captures the start time at construction and reports the elapsed
 * wall-clock time (in milliseconds) to
 * {@link OjpDriverMetricsHolder#get()} once the stream is exhausted
 * ({@link #hasNext()} returns {@code false}) or a {@link RuntimeException} is thrown
 * during iteration. If the iterator is abandoned before exhaustion, the metric will
 * not be recorded – a known trade-off documented in the {@code OjpMicrometerDriverMetrics}
 * class Javadoc.</p>
 */
class OjpMetricsIterator implements Iterator<OpResult> {

    private final Iterator<OpResult> delegate;
    private final long startNs;
    private boolean completed = false;

    OjpMetricsIterator(Iterator<OpResult> delegate) {
        this.delegate = delegate;
        this.startNs = System.nanoTime();
    }

    @Override
    public boolean hasNext() {
        try {
            boolean more = delegate.hasNext();
            if (!more && !completed) {
                completed = true;
                OjpDriverMetricsHolder.get().onStatementExecuted(elapsedMs());
            }
            return more;
        } catch (RuntimeException e) {
            if (!completed) {
                completed = true;
                OjpDriverMetricsHolder.get().onStatementFailed();
            }
            throw e;
        }
    }

    @Override
    public OpResult next() {
        try {
            return delegate.next();
        } catch (NoSuchElementException e) {
            throw e;
        } catch (RuntimeException e) {
            if (!completed) {
                completed = true;
                OjpDriverMetricsHolder.get().onStatementFailed();
            }
            throw e;
        }
    }

    private long elapsedMs() {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}

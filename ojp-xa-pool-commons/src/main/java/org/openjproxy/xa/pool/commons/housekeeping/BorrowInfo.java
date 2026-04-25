package org.openjproxy.xa.pool.commons.housekeeping;

/**
 * Tracks information about a borrowed session for leak detection.
 * <p>
 * This class holds the timestamp, thread, and optional stack trace
 * captured when a session is borrowed from the pool.
 * </p>
 */
public class BorrowInfo {
    private final long borrowTime;
    private final Thread borrowingThread;
    private final StackTraceElement[] stackTrace;

    /**
     * Creates borrow tracking information.
     *
     * @param borrowTime the timestamp (in nanoseconds) when the session was borrowed
     * @param borrowingThread the thread that borrowed the session
     * @param stackTrace the stack trace at borrow time (may be null if not captured)
     */
    public BorrowInfo(long borrowTime, Thread borrowingThread, StackTraceElement[] stackTrace) {
        this.borrowTime = borrowTime;
        this.borrowingThread = borrowingThread;
        this.stackTrace = stackTrace;
    }

    /**
     * Gets the borrow timestamp.
     *
     * @return the timestamp in nanoseconds
     */
    public long getBorrowTime() {
        return borrowTime;
    }

    /**
     * Gets the borrowing thread.
     *
     * @return the thread that borrowed the session
     */
    public Thread getThread() {
        return borrowingThread;
    }

    /**
     * Gets the stack trace captured at borrow time.
     *
     * @return the stack trace, or null if not captured
     */
    public StackTraceElement[] getStackTrace() {
        return stackTrace;
    }
}

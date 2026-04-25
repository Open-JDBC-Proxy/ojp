package org.openjproxy.xa.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.xa.XAException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe context holder for an XA transaction branch.
 *
 * <p>Each XA transaction branch (identified by {@link XidKey}) has one TxContext that tracks:</p>
 * <ul>
 *   <li>Current transaction state ({@link TxState})</li>
 *   <li>Bound backend session ({@link XABackendSession})</li>
 *   <li>Timestamps for diagnostics and leak detection</li>
 *   <li>Optional timeout and read-only hints</li>
 * </ul>
 *
 * <p>This class is thread-safe. State transitions are protected by an internal lock
 * and validated according to XA specification rules. Invalid transitions throw
 * {@link XAException} with appropriate error codes.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * TxContext ctx = new TxContext(xidKey, ojpSessionId);
 * ctx.transitionTo(TxState.ACTIVE, session);
 * // ... perform work ...
 * ctx.transitionTo(TxState.ENDED, null);
 * ctx.transitionTo(TxState.PREPARED, null);
 * ctx.transitionTo(TxState.COMMITTED, null);
 * }</pre>
 */
public class TxContext {

    private static final Logger log = LoggerFactory.getLogger(TxContext.class);

    private final XidKey xid;
    private final long createdAtNanos;
    private final AtomicLong lastAccessNanos;
    private final ReentrantLock lock = new ReentrantLock();

    private TxState state;
    private XABackendSession session;
    private javax.transaction.xa.Xid actualXid;  // Store the actual Xid object to reuse across XA calls
    private Integer timeoutSeconds;
    private Boolean readOnlyHint;
    private int associationCount;
    private boolean transactionComplete;  // Dual-condition lifecycle: true when commit/rollback called, false otherwise
    private String ojpSessionId;  // The OJP session ID this transaction belongs to

    /**
     * Creates a new transaction context in NONEXISTENT state.
     *
     * @param xid the transaction identifier
     * @param ojpSessionId the OJP session ID this transaction belongs to
     * @throws IllegalArgumentException if xid is null or ojpSessionId is null
     */
    public TxContext(XidKey xid, String ojpSessionId) {
        if (xid == null) {
            throw new IllegalArgumentException("xid cannot be null");
        }
        if (ojpSessionId == null) {
            throw new IllegalArgumentException("ojpSessionId cannot be null");
        }
        this.xid = xid;
        this.ojpSessionId = ojpSessionId;
        this.state = TxState.NONEXISTENT;
        this.createdAtNanos = System.nanoTime();
        this.lastAccessNanos = new AtomicLong(createdAtNanos);
        this.associationCount = 0;
        this.transactionComplete = false;  // Initially false, set to true on commit/rollback
    }

    /**
     * Gets the transaction identifier.
     *
     * @return the XidKey
     */
    public XidKey getXid() {
        return xid;
    }

    /**
     * Gets the current transaction state.
     *
     * @return the current TxState
     */
    public TxState getState() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the backend session binding.
     *
     * @return the XABackendSession, or null if not bound
     */
    public XABackendSession getSession() {
        lock.lock();
        try {
            return session;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets the backend session binding.
     *
     * @param session the backend session to bind
     */
    public void setSession(XABackendSession session) {
        lock.lock();
        try {
            this.session = session;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the actual Xid object used for XA calls.
     * This ensures the same Xid instance is reused across start/end/prepare/commit/rollback.
     *
     * @return the actual Xid, or null if not set
     */
    public javax.transaction.xa.Xid getActualXid() {
        lock.lock();
        try {
            return actualXid;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets the actual Xid object to be reused for XA calls.
     *
     * @param actualXid the Xid object to store
     */
    public void setActualXid(javax.transaction.xa.Xid actualXid) {
        lock.lock();
        try {
            this.actualXid = actualXid;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the transaction timeout in seconds.
     *
     * @return the timeout, or null if not set
     */
    public Integer getTimeoutSeconds() {
        lock.lock();
        try {
            return timeoutSeconds;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets the transaction timeout.
     *
     * @param timeoutSeconds the timeout in seconds
     */
    public void setTimeoutSeconds(Integer timeoutSeconds) {
        lock.lock();
        try {
            this.timeoutSeconds = timeoutSeconds;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the read-only hint.
     *
     * @return the read-only hint, or null if not set
     */
    public Boolean getReadOnlyHint() {
        lock.lock();
        try {
            return readOnlyHint;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets the read-only hint.
     *
     * @param readOnlyHint true if transaction is read-only
     */
    public void setReadOnlyHint(Boolean readOnlyHint) {
        lock.lock();
        try {
            this.readOnlyHint = readOnlyHint;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks if the transaction has completed (committed or rolled back).
     *
     * @return true if transaction is complete, false otherwise
     */
    public boolean isTransactionComplete() {
        lock.lock();
        try {
            return transactionComplete;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Marks the transaction as complete.
     * This is called during commit/rollback to indicate the transaction has finished,
     * but the backend session remains bound to the OJP session until XAConnection.close().
     */
    public void markTransactionComplete() {
        lock.lock();
        try {
            this.transactionComplete = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the OJP session ID this transaction belongs to.
     *
     * @return the OJP session ID
     */
    public String getOjpSessionId() {
        return ojpSessionId;
    }

    /**
     * Gets the association count (for TMJOIN/TMRESUME tracking).
     *
     * @return the association count
     */
    public int getAssociationCount() {
        lock.lock();
        try {
            return associationCount;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Increments the association count.
     */
    public void incrementAssociationCount() {
        lock.lock();
        try {
            associationCount++;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the creation timestamp in nanoseconds.
     *
     * @return creation timestamp
     */
    public long getCreatedAtNanos() {
        return createdAtNanos;
    }

    /**
     * Gets the last access timestamp in nanoseconds.
     *
     * @return last access timestamp
     */
    public long getLastAccessNanos() {
        return lastAccessNanos.get();
    }

    /**
     * Updates the last access timestamp to now.
     */
    public void touch() {
        lastAccessNanos.set(System.nanoTime());
    }

    /**
     * Transitions to a new state with validation.
     *
     * <p>This method validates the state transition according to XA specification
     * and throws {@link XAException} with appropriate error code if invalid.</p>
     *
     * @param newState the target state
     * @param newSession optional new session binding (for ACTIVE transition)
     * @throws XAException if the transition is invalid
     */
    public void transitionTo(TxState newState, XABackendSession newSession) throws XAException {
        lock.lock();
        try {
            validateTransition(state, newState);

            log.debug("Xid {} transitioning: {} → {}", xid.toCompactString(), state, newState);

            this.state = newState;
            if (newSession != null) {
                this.session = newSession;
            }
            touch();

        } finally {
            lock.unlock();
        }
    }

    /**
     * Validates if a state transition is allowed.
     *
     * @param from the current state
     * @param to the target state
     * @throws XAException if the transition is not allowed
     */
    private void validateTransition(TxState from, TxState to) throws XAException {
        boolean valid = false;

        switch (from) {
            case NONEXISTENT:
                valid = (to == TxState.ACTIVE);
                break;

            case ACTIVE:
                valid = (to == TxState.ENDED || to == TxState.COMMITTED || to == TxState.ROLLEDBACK);
                break;

            case ENDED:
                valid = (to == TxState.ACTIVE || to == TxState.PREPARED ||
                        to == TxState.COMMITTED || to == TxState.ROLLEDBACK);
                break;

            case PREPARED:
                valid = (to == TxState.COMMITTED || to == TxState.ROLLEDBACK);
                break;

            case COMMITTED:
            case ROLLEDBACK:
                // Terminal states - no transitions allowed
                valid = false;
                break;
        }

        if (!valid) {
            log.error("Invalid state transition for Xid {}: {} → {}",
                    xid.toCompactString(), from, to);
            XAException ex = new XAException(XAException.XAER_PROTO);
            ex.errorCode = XAException.XAER_PROTO;
            throw ex;
        }
    }

    /**
     * Transitions to ACTIVE state from NONEXISTENT (new transaction).
     *
     * @param newSession the backend session to bind
     * @throws IllegalStateException if current state is not NONEXISTENT
     */
    public void transitionToActive(XABackendSession newSession) {
        lock.lock();
        try {
            if (state != TxState.NONEXISTENT) {
                throw new IllegalStateException("Cannot transition to ACTIVE from " + state);
            }
            this.state = TxState.ACTIVE;
            this.session = newSession;
            this.associationCount = 1;
            touch();
            log.debug("Xid {} transitioned to ACTIVE", xid.toCompactString());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Transitions to ACTIVE state from ENDED (TMJOIN or TMRESUME).
     *
     * @param isJoin true for TMJOIN, false for TMRESUME
     * @throws IllegalStateException if current state is not ENDED
     */
    public void transitionToActiveFromEnded(boolean isJoin) {
        lock.lock();
        try {
            if (state != TxState.ENDED) {
                throw new IllegalStateException("Cannot transition to ACTIVE from " + state);
            }
            this.state = TxState.ACTIVE;
            if (isJoin) {
                this.associationCount++;
            }
            touch();
            log.debug("Xid {} transitioned to ACTIVE ({})", xid.toCompactString(),
                    isJoin ? "TMJOIN" : "TMRESUME");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Transitions to ENDED state.
     *
     * @param isSuspend true if TMSUSPEND, false if TMSUCCESS/TMFAIL
     * @throws IllegalStateException if current state is not ACTIVE
     */
    public void transitionToEnded(boolean isSuspend) {
        lock.lock();
        try {
            if (state != TxState.ACTIVE) {
                throw new IllegalStateException("Cannot transition to ENDED from " + state);
            }
            this.state = TxState.ENDED;
            touch();
            log.debug("Xid {} transitioned to ENDED ({})", xid.toCompactString(),
                    isSuspend ? "TMSUSPEND" : "TMSUCCESS/TMFAIL");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Transitions to PREPARED state.
     *
     * @throws IllegalStateException if current state is not ENDED
     */
    public void transitionToPrepared() {
        lock.lock();
        try {
            if (state != TxState.ENDED) {
                throw new IllegalStateException("Cannot transition to PREPARED from " + state);
            }
            this.state = TxState.PREPARED;
            touch();
            log.debug("Xid {} transitioned to PREPARED", xid.toCompactString());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Transitions to COMMITTED state.
     *
     * @throws IllegalStateException if current state is invalid
     */
    public void transitionToCommitted() {
        lock.lock();
        try {
            if (state != TxState.ENDED && state != TxState.PREPARED &&
                state != TxState.ACTIVE && state != TxState.COMMITTED) {
                throw new IllegalStateException("Cannot transition to COMMITTED from " + state);
            }
            this.state = TxState.COMMITTED;
            touch();
            log.debug("Xid {} transitioned to COMMITTED", xid.toCompactString());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Transitions to ROLLEDBACK state.
     *
     * @throws IllegalStateException if current state is invalid
     */
    public void transitionToRolledBack() {
        lock.lock();
        try {
            if (state != TxState.ACTIVE && state != TxState.ENDED &&
                state != TxState.PREPARED && state != TxState.ROLLEDBACK) {
                throw new IllegalStateException("Cannot transition to ROLLEDBACK from " + state);
            }
            this.state = TxState.ROLLEDBACK;
            touch();
            log.debug("Xid {} transitioned to ROLLEDBACK", xid.toCompactString());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            return "TxContext{" +
                    "xid=" + xid.toCompactString() +
                    ", state=" + state +
                    ", session=" + (session != null ? "bound" : "null") +
                    ", age=" + ((System.nanoTime() - createdAtNanos) / 1_000_000) + "ms" +
                    '}';
        } finally {
            lock.unlock();
        }
    }
}

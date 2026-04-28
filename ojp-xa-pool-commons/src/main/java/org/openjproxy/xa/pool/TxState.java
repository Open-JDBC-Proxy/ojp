package org.openjproxy.xa.pool;

/**
 * Represents the state of an XA transaction branch in its lifecycle.
 *
 * <p>State transitions follow the XA specification:</p>
 * <pre>
 * NONEXISTENT → ACTIVE → ENDED → PREPARED → COMMITTED/ROLLEDBACK
 *                  ↓                 ↓
 *                  → → → → → → → ROLLEDBACK
 * </pre>
 *
 * <p>Valid transitions:</p>
 * <ul>
 *   <li>{@code NONEXISTENT → ACTIVE}: xa_start with TMNOFLAGS</li>
 *   <li>{@code ACTIVE → ENDED}: xa_end with TMSUCCESS/TMFAIL/TMSUSPEND</li>
 *   <li>{@code ENDED → ACTIVE}: xa_start with TMRESUME/TMJOIN</li>
 *   <li>{@code ENDED → PREPARED}: xa_prepare</li>
 *   <li>{@code PREPARED → COMMITTED}: xa_commit(onePhase=false)</li>
 *   <li>{@code PREPARED → ROLLEDBACK}: xa_rollback</li>
 *   <li>{@code ACTIVE → ROLLEDBACK}: xa_rollback (immediate)</li>
 *   <li>{@code ENDED → ROLLEDBACK}: xa_rollback</li>
 *   <li>{@code ENDED → COMMITTED}: xa_commit(onePhase=true)</li>
 *   <li>{@code ACTIVE → COMMITTED}: xa_commit(onePhase=true) with implicit end</li>
 * </ul>
 */
public enum TxState {

    /**
     * Transaction does not exist yet. Initial state before xa_start.
     */
    NONEXISTENT,

    /**
     * Transaction is active. Work can be performed on the backend session.
     * Entered after xa_start (TMNOFLAGS/TMJOIN/TMRESUME).
     */
    ACTIVE,

    /**
     * Transaction has ended but not yet prepared or committed.
     * Entered after xa_end (TMSUCCESS/TMFAIL/TMSUSPEND).
     * No work can be performed, but transaction is not yet durable.
     */
    ENDED,

    /**
     * Transaction has been prepared (two-phase commit first phase complete).
     * Entered after xa_prepare returns XA_OK.
     * Transaction outcome is durable in the backend database.
     * Backend session must remain pinned until commit/rollback.
     */
    PREPARED,

    /**
     * Transaction has been committed. Terminal state.
     * Entered after xa_commit completes successfully.
     * Backend session can be returned to pool.
     */
    COMMITTED,

    /**
     * Transaction has been rolled back. Terminal state.
     * Entered after xa_rollback completes successfully.
     * Backend session can be returned to pool.
     */
    ROLLEDBACK;

    /**
     * Checks if this state is a terminal state (transaction complete).
     *
     * @return true if state is COMMITTED or ROLLEDBACK
     */
    public boolean isTerminal() {
        return this == COMMITTED || this == ROLLEDBACK;
    }

    /**
     * Checks if this state allows work to be performed.
     *
     * @return true if state is ACTIVE
     */
    public boolean canPerformWork() {
        return this == ACTIVE;
    }

    /**
     * Checks if the backend session is pinned (cannot be returned to pool).
     *
     * @return true if state is ACTIVE, ENDED, or PREPARED
     */
    public boolean isSessionPinned() {
        return this == ACTIVE || this == ENDED || this == PREPARED;
    }
}

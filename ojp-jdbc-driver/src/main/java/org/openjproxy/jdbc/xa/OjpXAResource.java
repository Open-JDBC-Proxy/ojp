package org.openjproxy.jdbc.xa;

import com.google.protobuf.ByteString;
import com.openjproxy.grpc.*;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.client.MultinodeConnectionManager;
import org.openjproxy.grpc.client.MultinodeStatementService;
import org.openjproxy.grpc.client.StatementService;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.SQLException;

/**
 * Implementation of XAResource that delegates all operations to the OJP server via StatementService.
 * 
 * Phase 1: Implements retry logic for xaStart() to handle server failures gracefully.
 */
@Slf4j
public class OjpXAResource implements XAResource {

    private final StatementService statementService;
    private SessionInfo sessionInfo;
    private final OjpXAConnection xaConnection; // Phase 1: Reference to parent connection for session recreation

    public OjpXAResource(StatementService statementService, SessionInfo sessionInfo, OjpXAConnection xaConnection) {
        this.statementService = statementService;
        this.sessionInfo = sessionInfo;
        this.xaConnection = xaConnection;
    }

    @Override
    public void start(Xid xid, int flags) throws XAException {
        log.debug("start: xid={}, flags={}", xid, flags);
        
        // Phase 1: Implement retry logic for xaStart
        // Safe to retry because no transaction state exists yet
        int maxRetries = getMaxRetries();
        int attempt = 0;
        XAException lastException = null;
        
        while (attempt < maxRetries) {
            try {
                // MultinodeStatementService will automatically add cluster health via withClusterHealth()
                XaStartRequest request = XaStartRequest.newBuilder()
                        .setSession(sessionInfo)
                        .setXid(toXidProto(xid))
                        .setFlags(flags)
                        .build();
                XaResponse response = statementService.xaStart(request);
                if (!response.getSuccess()) {
                    throw new XAException(response.getMessage());
                }
                
                // Success - return immediately
                if (attempt > 0) {
                    log.info("xaStart succeeded on retry attempt {}", attempt);
                }
                return;
                
            } catch (XAException e) {
                throw e; // XA-level errors are not retryable
            } catch (Exception e) {
                // Check if this is a connection-level error
                boolean isConnectionError = isConnectionLevelError(e);
                
                if (isConnectionError) {
                    // Connection-level error - can retry
                    lastException = new XAException(XAException.XAER_RMFAIL);
                    lastException.initCause(e);
                    
                    attempt++;
                    
                    if (attempt < maxRetries) {
                        log.warn("xaStart failed with connection error (attempt {}/{}): {}. Attempting to recreate session...", 
                                attempt, maxRetries, e.getMessage());
                        
                        try {
                            // Recreate session on a different server
                            this.sessionInfo = xaConnection.recreateSession();
                            log.info("Session recreated successfully on attempt {}", attempt);
                        } catch (SQLException recreateEx) {
                            log.error("Failed to recreate session on attempt {}: {}", attempt, recreateEx.getMessage());
                            // Continue to next retry if we have attempts left
                        }
                    } else {
                        log.error("xaStart failed after {} attempts", maxRetries, e);
                    }
                } else {
                    // Database-level error - don't retry
                    log.error("Database-level error in start (not retrying)", e);
                    XAException xae = new XAException(XAException.XAER_RMERR);
                    xae.initCause(e);
                    throw xae;
                }
            }
        }
        
        // All retries exhausted
        throw lastException;
    }
    
    /**
     * Phase 1: Get the maximum number of retries for xaStart.
     * Returns the number of healthy servers available, or 3 as a fallback.
     */
    private int getMaxRetries() {
        if (statementService instanceof MultinodeStatementService) {
            MultinodeStatementService multinodeService = (MultinodeStatementService) statementService;
            MultinodeConnectionManager connectionManager = multinodeService.getConnectionManager();
            if (connectionManager != null) {
                long healthyServerCount = connectionManager.getServerEndpoints().stream()
                        .filter(endpoint -> endpoint.isHealthy())
                        .count();
                // Try at least once per healthy server, minimum of 1
                return Math.max(1, (int) healthyServerCount);
            }
        }
        // Default: try 3 times
        return 3;
    }
    
    /**
     * Phase 1: Determines if an exception represents a connection-level error.
     * Uses the same logic as GrpcExceptionHandler.isConnectionLevelError().
     */
    private boolean isConnectionLevelError(Exception exception) {
        return org.openjproxy.grpc.client.GrpcExceptionHandler.isConnectionLevelError(exception);
    }

    @Override
    public void end(Xid xid, int flags) throws XAException {
        log.debug("end: xid={}, flags={}", xid, flags);
        try {
            XaEndRequest request = XaEndRequest.newBuilder()
                    .setSession(sessionInfo)
                    .setXid(toXidProto(xid))
                    .setFlags(flags)
                    .build();
            XaResponse response = statementService.xaEnd(request);
            if (!response.getSuccess()) {
                throw new XAException(response.getMessage());
            }
        } catch (XAException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error in end", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    public int prepare(Xid xid) throws XAException {
        log.debug("prepare: xid={}", xid);
        try {
            // MultinodeStatementService will automatically add cluster health via withClusterHealth()
            XaPrepareRequest request = XaPrepareRequest.newBuilder()
                    .setSession(sessionInfo)
                    .setXid(toXidProto(xid))
                    .build();
            XaPrepareResponse response = statementService.xaPrepare(request);
            return response.getResult();
        } catch (Exception e) {
            log.error("Error in prepare", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    public void commit(Xid xid, boolean onePhase) throws XAException {
        log.debug("commit: xid={}, onePhase={}", xid, onePhase);
        try {
            // MultinodeStatementService will automatically add cluster health via withClusterHealth()
            XaCommitRequest request = XaCommitRequest.newBuilder()
                    .setSession(sessionInfo)
                    .setXid(toXidProto(xid))
                    .setOnePhase(onePhase)
                    .build();
            XaResponse response = statementService.xaCommit(request);
            if (!response.getSuccess()) {
                throw new XAException(response.getMessage());
            }
        } catch (XAException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error in commit", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    public void rollback(Xid xid) throws XAException {
        log.debug("rollback: xid={}", xid);
        try {
            XaRollbackRequest request = XaRollbackRequest.newBuilder()
                    .setSession(sessionInfo)
                    .setXid(toXidProto(xid))
                    .build();
            XaResponse response = statementService.xaRollback(request);
            if (!response.getSuccess()) {
                throw new XAException(response.getMessage());
            }
        } catch (XAException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error in rollback", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    public Xid[] recover(int flag) throws XAException {
        log.debug("recover: flag={}", flag);
        try {
            XaRecoverRequest request = XaRecoverRequest.newBuilder()
                    .setSession(sessionInfo)
                    .setFlag(flag)
                    .build();
            XaRecoverResponse response = statementService.xaRecover(request);
            return response.getXidsList().stream()
                    .map(this::fromXidProto)
                    .toArray(Xid[]::new);
        } catch (Exception e) {
            if (isTransientRecoverError(e)) {
                // Transient errors (deadline exceeded, server unavailable) must surface as
                // XAER_RMFAIL rather than XAER_RMERR.  XAER_RMFAIL tells the transaction
                // manager that the resource manager is temporarily unreachable and that it
                // should retry later (e.g. after periodicRecoveryPeriod), whereas XAER_RMERR
                // signals a permanent failure that can cause the recovery scan to be abandoned.
                log.warn("XA recover failed with transient error, will retry later: {}", e.getMessage());
                XAException xae = new XAException(XAException.XAER_RMFAIL);
                xae.initCause(e);
                throw xae;
            }
            log.error("Error in recover", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    /**
     * Returns true when an exception from xaRecover() represents a transient condition
     * (timeout, server temporarily unavailable) rather than a permanent resource-manager error.
     * Walks the cause chain to find any gRPC StatusRuntimeException whose status code
     * indicates a transient failure.
     *
     * @param e the exception thrown by statementService.xaRecover()
     * @return true if the error is transient and recovery should be retried later
     */
    private static boolean isTransientRecoverError(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof StatusRuntimeException) {
                io.grpc.Status.Code code = ((StatusRuntimeException) cause).getStatus().getCode();
                return code == io.grpc.Status.Code.DEADLINE_EXCEEDED
                        || code == io.grpc.Status.Code.UNAVAILABLE;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Override
    public void forget(Xid xid) throws XAException {
        log.debug("forget: xid={}", xid);
        try {
            XaForgetRequest request = XaForgetRequest.newBuilder()
                    .setSession(sessionInfo)
                    .setXid(toXidProto(xid))
                    .build();
            XaResponse response = statementService.xaForget(request);
            if (!response.getSuccess()) {
                throw new XAException(response.getMessage());
            }
        } catch (XAException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error in forget", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    public boolean setTransactionTimeout(int seconds) throws XAException {
        log.debug("setTransactionTimeout: seconds={}", seconds);
        try {
            XaSetTransactionTimeoutRequest request = XaSetTransactionTimeoutRequest.newBuilder()
                    .setSession(sessionInfo)
                    .setSeconds(seconds)
                    .build();
            XaSetTransactionTimeoutResponse response = statementService.xaSetTransactionTimeout(request);
            return response.getSuccess();
        } catch (Exception e) {
            log.error("Error in setTransactionTimeout", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    public int getTransactionTimeout() throws XAException {
        log.debug("getTransactionTimeout");
        try {
            XaGetTransactionTimeoutRequest request = XaGetTransactionTimeoutRequest.newBuilder()
                    .setSession(sessionInfo)
                    .build();
            XaGetTransactionTimeoutResponse response = statementService.xaGetTransactionTimeout(request);
            return response.getSeconds();
        } catch (Exception e) {
            log.error("Error in getTransactionTimeout", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    public boolean isSameRM(XAResource xares) throws XAException {
        log.debug("isSameRM: xares={}", xares);
        if (!(xares instanceof OjpXAResource)) {
            return false;
        }
        try {
            OjpXAResource other = (OjpXAResource) xares;
            XaIsSameRMRequest request = XaIsSameRMRequest.newBuilder()
                    .setSession1(sessionInfo)
                    .setSession2(other.sessionInfo)
                    .build();
            XaIsSameRMResponse response = statementService.xaIsSameRM(request);
            return response.getIsSame();
        } catch (Exception e) {
            log.error("Error in isSameRM", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    /**
     * Convert javax.transaction.xa.Xid to protobuf XidProto.
     */
    private XidProto toXidProto(Xid xid) {
        return XidProto.newBuilder()
                .setFormatId(xid.getFormatId())
                .setGlobalTransactionId(ByteString.copyFrom(xid.getGlobalTransactionId()))
                .setBranchQualifier(ByteString.copyFrom(xid.getBranchQualifier()))
                .build();
    }

    /**
     * Convert protobuf XidProto to javax.transaction.xa.Xid.
     */
    private Xid fromXidProto(XidProto xidProto) {
        return new OjpXid(
                xidProto.getFormatId(),
                xidProto.getGlobalTransactionId().toByteArray(),
                xidProto.getBranchQualifier().toByteArray()
        );
    }

    /**
     * Simple implementation of Xid interface.
     */
    private static class OjpXid implements Xid {
        private final int formatId;
        private final byte[] globalTransactionId;
        private final byte[] branchQualifier;

        public OjpXid(int formatId, byte[] globalTransactionId, byte[] branchQualifier) {
            this.formatId = formatId;
            this.globalTransactionId = globalTransactionId;
            this.branchQualifier = branchQualifier;
        }

        @Override
        public int getFormatId() {
            return formatId;
        }

        @Override
        public byte[] getGlobalTransactionId() {
            return globalTransactionId;
        }

        @Override
        public byte[] getBranchQualifier() {
            return branchQualifier;
        }
    }
}

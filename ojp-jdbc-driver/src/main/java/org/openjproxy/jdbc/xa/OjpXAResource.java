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
    private final OjpXAConnection xaConnection; // Reference to parent connection for session recreation
    private OjpXALogicalConnection logicalConnection; // Track the logical connection for alteration checking

    public OjpXAResource(StatementService statementService, SessionInfo sessionInfo, OjpXAConnection xaConnection) {
        this.statementService = statementService;
        this.sessionInfo = sessionInfo;
        this.xaConnection = xaConnection;
    }
    
    /**
     * Sets the logical connection for alteration tracking.
     * Called by OjpXAConnection when getConnection() is called.
     */
    void setLogicalConnection(OjpXALogicalConnection connection) {
        this.logicalConnection = connection;
    }

    @Override
    public void start(Xid xid, int flags) throws XAException {
        log.debug("start: xid={}, flags={}", xid, flags);
        
        // Implement retry logic for xaStart with connection alteration check
        // Safe to retry on different server only if connection not altered
        int maxRetries = getMaxRetries();
        int attempt = 0;
        XAException lastException = null;
        
        while (attempt < maxRetries) {
            try {
                XaStartRequest request = XaStartRequest.newBuilder()
                        .setSession(sessionInfo)
                        .setXid(toXidProto(xid))
                        .setFlags(flags)
                        .build();
                XaResponse response = statementService.xaStart(request);
                if (!response.getSuccess()) {
                    throw new XAException(response.getMessage());
                }
                
                // Success - reset alteration flag and return
                if (logicalConnection != null) {
                    logicalConnection.resetAlterationFlag();
                }
                if (attempt > 0) {
                    log.info("xaStart succeeded on retry attempt {}", attempt);
                }
                return;
                
            } catch (XAException e) {
                throw e; // XA-level errors are not retryable
            } catch (Exception e) {
                // Check if this is a connection-level error
                boolean isConnectionError = isConnectionLevelError(e);
                
                if (!isConnectionError) {
                    // Database-level error - don't retry
                    log.error("Database-level error in start (not retrying)", e);
                    XAException xae = new XAException(XAException.XAER_RMERR);
                    xae.initCause(e);
                    throw xae;
                }
                
                // Connection-level error - check if safe to retry
                lastException = new XAException(XAException.XAER_RMFAIL);
                lastException.initCause(e);
                
                attempt++;
                
                if (attempt < maxRetries) {
                    // Check if connection was altered before xaStart
                    if (logicalConnection != null && logicalConnection.isConnectionAltered()) {
                        log.error("Cannot retry xaStart - connection was altered before xaStart (unsafe to retry on different server)");
                        XAException xae = new XAException(XAException.XAER_RMFAIL);
                        xae.initCause(e);
                        throw xae;
                    }
                    
                    log.warn("xaStart failed with connection error (attempt {}/{}): {}. Attempting to recreate session...", 
                            attempt, maxRetries, e.getMessage());
                    
                    try {
                        // Recreate session on a different server (round-robin)
                        this.sessionInfo = xaConnection.recreateSession();
                        log.info("Session recreated successfully on attempt {} (using round-robin)", attempt);
                    } catch (SQLException recreateEx) {
                        log.error("Failed to recreate session on attempt {}: {}", attempt, recreateEx.getMessage());
                        // Continue to next retry if we have attempts left
                    }
                } else {
                    log.error("xaStart failed after {} attempts", maxRetries, e);
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
     * Uses the same logic as MultinodeConnectionManager.isConnectionLevelError().
     */
    private boolean isConnectionLevelError(Exception exception) {
        if (exception instanceof StatusRuntimeException) {
            StatusRuntimeException statusException = (StatusRuntimeException) exception;
            io.grpc.Status.Code code = statusException.getStatus().getCode();
            
            // Only these status codes indicate connection-level failures
            return code == io.grpc.Status.Code.UNAVAILABLE ||
                   code == io.grpc.Status.Code.DEADLINE_EXCEEDED ||
                   code == io.grpc.Status.Code.CANCELLED ||
                   (code == io.grpc.Status.Code.UNKNOWN && 
                    statusException.getMessage() != null && 
                    (statusException.getMessage().contains("connection") || 
                     statusException.getMessage().contains("Connection")));
        }
        
        // Check for SQLException with connection-related messages
        if (exception instanceof SQLException) {
            String message = exception.getMessage();
            if (message != null) {
                String lowerMessage = message.toLowerCase();
                return lowerMessage.contains("server") && lowerMessage.contains("unavailable") ||
                       lowerMessage.contains("connection") ||
                       lowerMessage.contains("timeout");
            }
        }
        
        // For non-gRPC exceptions, check for connection-related keywords
        String message = exception.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            return lowerMessage.contains("connection") || 
                   lowerMessage.contains("timeout") ||
                   lowerMessage.contains("unavailable");
        }
        
        return false; // Default to not retrying for unknown errors
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
            log.error("Error in recover", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
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

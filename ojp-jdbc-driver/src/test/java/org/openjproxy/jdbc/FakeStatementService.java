package org.openjproxy.jdbc;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SessionInfo;
import org.openjproxy.grpc.client.StatementService;
import org.openjproxy.grpc.dto.Parameter;

import java.sql.SQLException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Minimal {@link StatementService} test double used by unit tests that need to exercise
 * {@link Statement} / {@link PreparedStatement} behaviour without a real gRPC server.
 * Every method not overridden by the caller throws {@link UnsupportedOperationException}
 * so accidental usage of unstubbed behaviour fails fast.
 */
class FakeStatementService implements StatementService {

    private final OpResult executeUpdateResult;
    private final Iterator<OpResult> executeQueryResult;

    FakeStatementService() {
        this(null, Collections.emptyIterator());
    }

    FakeStatementService(OpResult executeUpdateResult, Iterator<OpResult> executeQueryResult) {
        this.executeUpdateResult = executeUpdateResult;
        this.executeQueryResult = executeQueryResult;
    }

    @Override
    public SessionInfo connect(ConnectionDetails connectionDetails) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public OpResult executeUpdate(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                  Map<String, Object> properties) throws SQLException {
        return this.executeUpdateResult;
    }

    @Override
    public OpResult executeUpdate(SessionInfo sessionInfo, String sql, List<Parameter> params, String statementUUID,
                                  Map<String, Object> properties) throws SQLException {
        return this.executeUpdateResult;
    }

    @Override
    public Iterator<OpResult> executeQuery(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                           String statementUUID, Map<String, Object> properties) throws SQLException {
        return this.executeQueryResult;
    }

    @Override
    public Iterator<OpResult> executeQuery(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                           Map<String, Object> properties) throws SQLException {
        return this.executeQueryResult;
    }

    @Override
    public OpResult fetchNextRows(SessionInfo sessionInfo, String resultSetUUID, int size) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public LobReference createLob(Connection connection, Iterator<LobDataBlock> lobDataBlock) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<LobDataBlock> readLob(LobReference lobReference, long pos, int length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void terminateSession(SessionInfo session) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public SessionInfo startTransaction(SessionInfo session) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public SessionInfo commitTransaction(SessionInfo session) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public SessionInfo rollbackTransaction(SessionInfo session) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public CallResourceResponse callResource(CallResourceRequest request) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.openjproxy.grpc.XaResponse xaStart(com.openjproxy.grpc.XaStartRequest request) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.openjproxy.grpc.XaResponse xaEnd(com.openjproxy.grpc.XaEndRequest request) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.openjproxy.grpc.XaPrepareResponse xaPrepare(com.openjproxy.grpc.XaPrepareRequest request)
            throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.openjproxy.grpc.XaResponse xaCommit(com.openjproxy.grpc.XaCommitRequest request) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.openjproxy.grpc.XaResponse xaRollback(com.openjproxy.grpc.XaRollbackRequest request)
            throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.openjproxy.grpc.XaRecoverResponse xaRecover(com.openjproxy.grpc.XaRecoverRequest request)
            throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.openjproxy.grpc.XaResponse xaForget(com.openjproxy.grpc.XaForgetRequest request) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.openjproxy.grpc.XaSetTransactionTimeoutResponse xaSetTransactionTimeout(
            com.openjproxy.grpc.XaSetTransactionTimeoutRequest request) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.openjproxy.grpc.XaGetTransactionTimeoutResponse xaGetTransactionTimeout(
            com.openjproxy.grpc.XaGetTransactionTimeoutRequest request) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.openjproxy.grpc.XaIsSameRMResponse xaIsSameRM(com.openjproxy.grpc.XaIsSameRMRequest request)
            throws SQLException {
        throw new UnsupportedOperationException();
    }
}

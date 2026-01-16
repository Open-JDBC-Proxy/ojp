package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.TransactionInfo;
import com.openjproxy.grpc.TransactionStatus;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.action.ActionContext;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommitTransactionActionTest {

    @Mock
    private ActionContext context;
    @Mock
    private SessionManager sessionManager;
    @Mock
    private Connection connection;
    @Mock
    private StreamObserver<SessionInfo> responseObserver;

    private CommitTransactionAction action;
    private SessionInfo sessionInfo;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(context.getSessionManager()).thenReturn(sessionManager);
        action = new CommitTransactionAction(context);

        TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                .setTransactionUUID("tx-uuid")
                .build();
        sessionInfo = SessionInfo.newBuilder()
                .setSessionUUID("session-uuid")
                .setTransactionInfo(transactionInfo)
                .build();
    }

    @Test
    void execute_successfulCommit() throws SQLException {
        when(sessionManager.getConnection(sessionInfo)).thenReturn(connection);

        action.execute(sessionInfo, responseObserver);

        verify(connection).commit();

        ArgumentCaptor<SessionInfo> sessionInfoCaptor = ArgumentCaptor.forClass(SessionInfo.class);
        verify(responseObserver).onNext(sessionInfoCaptor.capture());
        verify(responseObserver).onCompleted();

        SessionInfo resultSession = sessionInfoCaptor.getValue();
        assertNotNull(resultSession);
        assertEquals(TransactionStatus.TRX_COMMITED, resultSession.getTransactionInfo().getTransactionStatus());
        assertEquals("tx-uuid", resultSession.getTransactionInfo().getTransactionUUID());
    }

    @Test
    void execute_sqlException() throws SQLException {
        when(sessionManager.getConnection(sessionInfo)).thenReturn(connection);
        SQLException sqlException = new SQLException("Commit failed");        
        
        org.mockito.Mockito.doThrow(sqlException).when(connection).commit();

        action.execute(sessionInfo, responseObserver);

        verify(connection).commit();
    }

    @Test
    void execute_generalException() throws SQLException {
        when(sessionManager.getConnection(sessionInfo)).thenThrow(new RuntimeException("Unexpected error"));

        action.execute(sessionInfo, responseObserver);        
        
        verify(sessionManager).getConnection(sessionInfo);
    }
}

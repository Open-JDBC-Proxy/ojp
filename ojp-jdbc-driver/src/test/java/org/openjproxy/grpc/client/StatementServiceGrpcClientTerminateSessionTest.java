package org.openjproxy.grpc.client;

import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.SessionTerminationStatus;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatementServiceGrpcClientTerminateSessionTest {

    @Test
    void shouldRetryTerminateSessionWhenConnectionFailureThenSucceed() {
        TestableStatementServiceGrpcClient client = new TestableStatementServiceGrpcClient();
        client.addFailure(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Connection refused")));
        client.addFailure(new StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription("Request timeout")));
        client.addSuccess();

        assertDoesNotThrow(() -> client.terminateSession(buildSessionInfo()));
        assertEquals(3, client.getTerminateAttempts());
    }

    @Test
    void shouldStopAfterThreeConnectionLevelFailuresWhenTerminatingSession() {
        TestableStatementServiceGrpcClient client = new TestableStatementServiceGrpcClient();
        client.addFailure(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Connection refused")));
        client.addFailure(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Connection refused")));
        client.addFailure(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Connection refused")));

        SQLException exception = assertThrows(SQLException.class, () -> client.terminateSession(buildSessionInfo()));
        assertEquals(3, client.getTerminateAttempts());
        assertEquals("Error while terminating session: UNAVAILABLE: Connection refused", exception.getMessage());
    }

    @Test
    void shouldNotRetryTerminateSessionWhenPoolIsNotFound() {
        TestableStatementServiceGrpcClient client = new TestableStatementServiceGrpcClient();
        client.addFailure(new StatusRuntimeException(Status.NOT_FOUND.withDescription("Pool not found")));

        SQLException exception = assertThrows(SQLException.class, () -> client.terminateSession(buildSessionInfo()));
        assertEquals(1, client.getTerminateAttempts());
        assertEquals("Error while terminating session: NOT_FOUND: Pool not found", exception.getMessage());
    }

    @Test
    void shouldNotRetryTerminateSessionWhenSQLExceptionIsRaised() {
        TestableStatementServiceGrpcClient client = new TestableStatementServiceGrpcClient();
        SQLException sqlException = new SQLException("database failure");
        client.addFailure(sqlException);

        SQLException exception = assertThrows(SQLException.class, () -> client.terminateSession(buildSessionInfo()));
        assertEquals(1, client.getTerminateAttempts());
        assertSame(sqlException, exception);
    }

    private SessionInfo buildSessionInfo() {
        return SessionInfo.newBuilder()
                .setSessionUUID("session-1")
                .setConnHash("conn-hash")
                .build();
    }

    private static final class TestableStatementServiceGrpcClient extends StatementServiceGrpcClient {
        private final Deque<Object> outcomes = new ArrayDeque<>();
        private int terminateAttempts;

        private void addFailure(Exception exception) {
            outcomes.addLast(exception);
        }

        private void addSuccess() {
            outcomes.addLast(SessionTerminationStatus.newBuilder().setTerminated(true).build());
        }

        private int getTerminateAttempts() {
            return terminateAttempts;
        }

        @Override
        SessionTerminationStatus terminateSessionRpc(SessionInfo session) throws Exception {
            terminateAttempts++;
            Object outcome = outcomes.removeFirst();
            if (outcome instanceof Exception) {
                throw (Exception) outcome;
            }
            return (SessionTerminationStatus) outcome;
        }
    }
}

package org.openjproxy.grpc.server;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcExceptionHandlerTest {

    @Test
    void shouldSendResourceExhaustedForServerOverload() {
        CapturingObserver<Object> observer = new CapturingObserver<>();
        ServerOverloadException exception = new ServerOverloadException("too busy");

        GrpcExceptionHandler.sendServerOverload(exception, observer);

        assertNotNull(observer.error);
        StatusRuntimeException statusRuntimeException = assertInstanceOf(StatusRuntimeException.class, observer.error);
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, statusRuntimeException.getStatus().getCode());
        assertTrue(statusRuntimeException.getStatus().getDescription().contains("too busy"));
    }

    private static class CapturingObserver<T> implements StreamObserver<T> {
        private Throwable error;

        @Override
        public void onNext(T value) {
        }

        @Override
        public void onError(Throwable throwable) {
            this.error = throwable;
        }

        @Override
        public void onCompleted() {
        }
    }
}

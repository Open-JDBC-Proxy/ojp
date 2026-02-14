package org.openjproxy.grpc.server;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IpWhitelistingInterceptor.
 */
class IpWhitelistingInterceptorTest {
    private static final int TEST_PORT = 12345;

    @Test
    void testAllowedIpAccess() {
        // Setup
        List<String> allowedIps = List.of("192.168.1.1", "10.0.0.0/8");
        IpWhitelistingInterceptor interceptor = new IpWhitelistingInterceptor(allowedIps);

        TrackingServerCall call = createServerCall("192.168.1.1");
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        ServerCall.Listener<String> listener = new ServerCall.Listener<>() { };
        ServerCallHandler<String, String> next = (callArg, headers) -> {
            nextCalled.set(true);
            return listener;
        };

        // Execute
        ServerCall.Listener<String> result = interceptor.interceptCall(call, new Metadata(), next);

        // Verify
        assertNotNull(result);
        assertSame(listener, result);
        assertTrue(nextCalled.get());
        assertEquals(0, call.getCloseCount());
    }

    @Test
    void testDeniedIpAccess() {
        // Setup
        List<String> allowedIps = List.of("192.168.1.1", "10.0.0.0/8");
        IpWhitelistingInterceptor interceptor = new IpWhitelistingInterceptor(allowedIps);

        TrackingServerCall call = createServerCall("203.0.113.1");
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        ServerCallHandler<String, String> next = (callArg, headers) -> {
            nextCalled.set(true);
            return new ServerCall.Listener<>() { };
        };

        // Execute
        ServerCall.Listener<String> result = interceptor.interceptCall(call, new Metadata(), next);

        // Verify
        assertNotNull(result);
        assertFalse(nextCalled.get());
        assertEquals(1, call.getCloseCount());
        assertNotNull(call.getClosedStatus());
        assertEquals(Status.Code.PERMISSION_DENIED, call.getClosedStatus().getCode());
        assertEquals("Access denied", call.getClosedStatus().getDescription());
    }

    @Test
    void testAllowedIpAccessWithCidr() {
        // Setup
        List<String> allowedIps = List.of("10.0.0.0/8");
        IpWhitelistingInterceptor interceptor = new IpWhitelistingInterceptor(allowedIps);

        TrackingServerCall call = createServerCall("10.50.60.70");
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        ServerCall.Listener<String> listener = new ServerCall.Listener<>() { };
        ServerCallHandler<String, String> next = (callArg, headers) -> {
            nextCalled.set(true);
            return listener;
        };

        // Execute
        ServerCall.Listener<String> result = interceptor.interceptCall(call, new Metadata(), next);

        // Verify
        assertNotNull(result);
        assertSame(listener, result);
        assertTrue(nextCalled.get());
        assertEquals(0, call.getCloseCount());
    }

    @Test
    void testEmptyWhitelistAllowsAll() {
        // Setup
        List<String> allowedIps = List.of();
        IpWhitelistingInterceptor interceptor = new IpWhitelistingInterceptor(allowedIps);

        TrackingServerCall call = createServerCall("203.0.113.1");
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        ServerCall.Listener<String> listener = new ServerCall.Listener<>() { };
        ServerCallHandler<String, String> next = (callArg, headers) -> {
            nextCalled.set(true);
            return listener;
        };

        // Execute
        ServerCall.Listener<String> result = interceptor.interceptCall(call, new Metadata(), next);

        // Verify - empty whitelist should allow all
        assertNotNull(result);
        assertSame(listener, result);
        assertTrue(nextCalled.get());
        assertEquals(0, call.getCloseCount());
    }

    @Test
    void testWildcardAllowsAll() {
        // Setup
        List<String> allowedIps = List.of("*");
        IpWhitelistingInterceptor interceptor = new IpWhitelistingInterceptor(allowedIps);

        TrackingServerCall call = createServerCall("203.0.113.1");
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        ServerCall.Listener<String> listener = new ServerCall.Listener<>() { };
        ServerCallHandler<String, String> next = (callArg, headers) -> {
            nextCalled.set(true);
            return listener;
        };

        // Execute
        ServerCall.Listener<String> result = interceptor.interceptCall(call, new Metadata(), next);

        // Verify
        assertNotNull(result);
        assertSame(listener, result);
        assertTrue(nextCalled.get());
        assertEquals(0, call.getCloseCount());
    }

    /**
     * Helper method to create a ServerCall with a specific IP address.
     */
    private TrackingServerCall createServerCall(String ipAddress) {
        InetSocketAddress remoteAddr = new InetSocketAddress(ipAddress, TEST_PORT);
        Attributes attributes = Attributes.newBuilder()
                .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remoteAddr)
                .build();
        return new TrackingServerCall(attributes, newMethodDescriptor());
    }

    private MethodDescriptor<String, String> newMethodDescriptor() {
        MethodDescriptor.Marshaller<String> marshaller = new MethodDescriptor.Marshaller<>() {
            @Override
            public InputStream stream(String value) {
                byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public String parse(InputStream stream) {
                return "";
            }
        };

        return MethodDescriptor.<String, String>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("test.Service/TestMethod")
                .setRequestMarshaller(marshaller)
                .setResponseMarshaller(marshaller)
                .build();
    }

    private static final class TrackingServerCall extends ServerCall<String, String> {
        private final Attributes attributes;
        private final MethodDescriptor<String, String> methodDescriptor;
        private Status closedStatus;
        private Metadata closedMetadata;
        private int closeCount;

        private TrackingServerCall(Attributes attributes, MethodDescriptor<String, String> methodDescriptor) {
            this.attributes = attributes;
            this.methodDescriptor = methodDescriptor;
        }

        @Override
        public void request(int numMessages) {
        }

        @Override
        public void sendHeaders(Metadata headers) {
        }

        @Override
        public void sendMessage(String message) {
        }

        @Override
        public void close(Status status, Metadata trailers) {
            this.closedStatus = status;
            this.closedMetadata = trailers;
            this.closeCount++;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void setMessageCompression(boolean enabled) {
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public Attributes getAttributes() {
            return attributes;
        }

        @Override
        public MethodDescriptor<String, String> getMethodDescriptor() {
            return methodDescriptor;
        }

        Status getClosedStatus() {
            return closedStatus;
        }

        Metadata getClosedMetadata() {
            return closedMetadata;
        }

        int getCloseCount() {
            return closeCount;
        }
    }
}

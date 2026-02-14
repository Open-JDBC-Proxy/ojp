package org.openjproxy.grpc.server;

import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

/**
 * gRPC Server Interceptor that enforces IP whitelisting for all incoming requests.
 * Logs warning messages with relevant information when denying access for audit purposes.
 */
public class IpWhitelistingInterceptor implements ServerInterceptor {
    private static final Logger logger = LoggerFactory.getLogger(IpWhitelistingInterceptor.class);
    
    private final List<String> allowedIps;
    
    public IpWhitelistingInterceptor(List<String> allowedIps) {
        this.allowedIps = allowedIps;
    }
    
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        
        // Extract client IP address
        String clientIp = extractClientIp(call);
        String methodName = call.getMethodDescriptor().getFullMethodName();
        
        // Check if IP is allowed
        if (!IpWhitelistValidator.isIpAllowed(clientIp, allowedIps)) {
            // Log warning for audit with relevant information
            logger.warn("IP whitelisting access denied: clientIp={}, method={}", 
                    clientIp, methodName);
            
            // Close the call with PERMISSION_DENIED status
            call.close(
                Status.PERMISSION_DENIED
                    .withDescription("Access denied"),
                new Metadata()
            );
            
            // Return empty listener since we're rejecting the call
            return new ServerCall.Listener<ReqT>() {};
        }
        
        // IP is allowed, proceed with the call
        return next.startCall(call, headers);
    }
    
    /**
     * Extracts the client IP address from the gRPC ServerCall.
     */
    private String extractClientIp(ServerCall<?, ?> call) {
        try {
            SocketAddress remoteAddr = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
            
            if (remoteAddr instanceof InetSocketAddress) {
                InetSocketAddress inetAddr = (InetSocketAddress) remoteAddr;
                return inetAddr.getAddress().getHostAddress();
            }
            
            // Fallback if not InetSocketAddress
            logger.warn("Unable to extract IP address from remote address: {}", remoteAddr);
            return "unknown";
            
        } catch (Exception e) {
            logger.error("Error extracting client IP address", e);
            return "unknown";
        }
    }
}

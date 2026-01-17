package org.openjproxy.grpc.server.action;

import io.grpc.stub.StreamObserver;

/**
 * Action interface for bidirectional streaming operations (e.g., createLob).
 * Used when the action needs to return a StreamObserver for client-to-server streaming.
 * 
 * <p><b>Implementation Requirements:</b>
 * <ul>
 *   <li>All implementations MUST be singletons (private constructor, static INSTANCE field, getInstance() method)</li>
 *   <li>All implementations MUST be stateless - no instance fields except INSTANCE</li>
 *   <li>All state must be passed via method parameters or captured in the returned StreamObserver</li>
 * </ul>
 * 
 * <p><b>Example Implementation:</b>
 * <pre>{@code
 * public class CreateLobAction implements StreamingAction<LobDataBlock, LobReference> {
 *     private static final CreateLobAction INSTANCE = new CreateLobAction();
 *     
 *     private CreateLobAction() {}
 *     
 *     public static CreateLobAction getInstance() {
 *         return INSTANCE;
 *     }
 *     
 *     public StreamObserver<LobDataBlock> execute(StreamObserver<LobReference> responseObserver) {
 *         // Implementation returns StreamObserver for client streaming
 *     }
 * }
 * }</pre>
 * 
 * @param <TRequest> The gRPC request type (streamed from client)
 * @param <TResponse> The gRPC response type (sent to client)
 */
@FunctionalInterface
public interface StreamingAction<TRequest, TResponse> {
    /**
     * Execute the action and return a StreamObserver for receiving client requests.
     * 
     * @param responseObserver The gRPC response observer for sending responses to client
     * @return StreamObserver for receiving streaming requests from client
     */
    StreamObserver<TRequest> execute(StreamObserver<TResponse> responseObserver);
}

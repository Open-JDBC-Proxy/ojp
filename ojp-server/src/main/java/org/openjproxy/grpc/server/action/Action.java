package org.openjproxy.grpc.server.action;

import io.grpc.stub.StreamObserver;

/**
 * Base interface for all action classes in the refactored StatementServiceImpl.
 * Each action encapsulates the logic for a specific gRPC operation.
 * 
 * <p>Actions are implemented as singletons and are stateless. All necessary state
 * is passed via the ActionContext parameter.
 * 
 * <p>Use this interface for standard unary or server-streaming RPC methods where:
 * <ul>
 *   <li>The method receives a request and sends responses via StreamObserver</li>
 *   <li>The method signature is: {@code void methodName(Request, StreamObserver<Response>)}</li>
 *   <li>Examples: connect(), executeUpdate(), executeQuery(), startTransaction(), etc.</li>
 * </ul>
 * 
 * <p>For bidirectional streaming operations (e.g., createLob), use {@link StreamingAction} instead.
 * 
 * @param <TRequest> The gRPC request type
 * @param <TResponse> The gRPC response type
 * 
 * @see StreamingAction for bidirectional streaming operations
 */
@FunctionalInterface
public interface Action<TRequest, TResponse> {
    /**
     * Execute the action with the given context, request and response observer.
     * Actions are stateless - all necessary state is provided via the context parameter.
     * 
     * @param context The action context containing shared state and services
     * @param request The gRPC request
     * @param responseObserver The gRPC response observer for sending responses
     */
    void execute(ActionContext context, TRequest request, StreamObserver<TResponse> responseObserver);
}

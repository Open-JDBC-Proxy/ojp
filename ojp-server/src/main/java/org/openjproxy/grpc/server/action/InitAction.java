package org.openjproxy.grpc.server.action;

/**
 * Action interface for initialization operations that don't take request/response parameters.
 * Used for setup actions like initializing the XA Pool Provider.
 * 
 * <p><b>Implementation Requirements:</b>
 * <ul>
 *   <li>All implementations MUST be singletons (private constructor, static INSTANCE field, getInstance() method)</li>
 *   <li>All implementations MUST be stateless - no instance fields except INSTANCE</li>
 *   <li>All state must be accessed through external services or passed during construction of the action context</li>
 * </ul>
 * 
 * <p><b>Example Implementation:</b>
 * <pre>{@code
 * public class InitializeXAPoolProviderAction implements InitAction {
 *     private static final InitializeXAPoolProviderAction INSTANCE = new InitializeXAPoolProviderAction();
 *     
 *     private InitializeXAPoolProviderAction() {}
 *     
 *     public static InitializeXAPoolProviderAction getInstance() {
 *         return INSTANCE;
 *     }
 *     
 *     public void execute() {
 *         // Initialization logic
 *     }
 * }
 * }</pre>
 * 
 * <p>Example: InitializeXAPoolProviderAction
 */
@FunctionalInterface
public interface InitAction {
    /**
     * Execute the initialization action.
     */
    void execute();
}

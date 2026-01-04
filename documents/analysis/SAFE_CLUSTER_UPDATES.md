# Safe Cluster Update Strategies for OJP

## Executive Summary

This document provides detailed strategies for safely updating OJP cluster nodes without losing requests or causing service interruptions. It covers graceful shutdown procedures, connection draining, rolling updates, blue-green deployments, and best practices for zero-downtime cluster operations.

## Table of Contents

1. [Current State Analysis](#current-state-analysis)
2. [Key Challenges](#key-challenges)
3. [Graceful Shutdown Strategy](#graceful-shutdown-strategy)
4. [Connection Draining Implementation](#connection-draining-implementation)
5. [Rolling Update Strategy](#rolling-update-strategy)
6. [Blue-Green Deployment](#blue-green-deployment)
7. [Canary Deployment](#canary-deployment)
8. [Session Management During Updates](#session-management-during-updates)
9. [Monitoring and Validation](#monitoring-and-validation)
10. [Troubleshooting Guide](#troubleshooting-guide)

---

## Current State Analysis

### Existing Capabilities

OJP already provides strong foundations for safe updates:

1. **Health Monitoring**
   - Automatic detection of unavailable servers
   - Periodic health checks with configurable intervals
   - Automatic recovery when servers return

2. **Session Stickiness**
   - Sessions bound to specific servers
   - Maintains ACID transaction guarantees
   - Prevents mid-transaction server switches

3. **Connection Redistribution**
   - Automatic rebalancing after server recovery
   - Load-aware connection distribution
   - Gradual redistribution to avoid spikes

4. **Failure Handling**
   - Immediate session invalidation on server failure
   - Graceful retry mechanisms
   - Connection pool coordination

### Gaps for Safe Updates

Current system lacks:

1. **Planned Shutdown Signaling**
   - No way to signal intent to shutdown
   - Sudden removal treated like failure
   - No grace period for connection completion

2. **Drain Mode**
   - Cannot prevent new connections to a server
   - No mechanism to wait for existing work to complete
   - Forced invalidation may interrupt transactions

3. **Update Coordination**
   - Manual coordination required
   - No automated rolling update support
   - Risk of too many servers updating simultaneously

4. **Observability**
   - Limited visibility into in-flight requests
   - No metrics for drain progress
   - Difficult to know when safe to shutdown

---

## Key Challenges

### 1. In-Flight Transactions

**Problem:**
Active database transactions must complete before server shutdown to maintain ACID properties.

**Impact:**
- Abrupt shutdown causes transaction rollback
- Data inconsistency risk
- Application errors and retries

**Solution Requirements:**
- Track active transactions per server
- Wait for completion before shutdown
- Timeout and rollback if necessary

---

### 2. Session State Loss

**Problem:**
OJP servers maintain session state (connection mappings, transaction context). Server shutdown loses this state.

**Impact:**
- "Connection not found" errors
- Session re-establishment overhead
- Temporary service disruption

**Solution Requirements:**
- Graceful session migration (if possible)
- Clear session invalidation
- Fast session re-creation

---

### 3. Connection Pool Behavior

**Problem:**
Connection pools hold connections and may not immediately detect server unavailability.

**Impact:**
- Stale connections in pool
- Connection validation overhead
- Temporary increase in errors

**Solution Requirements:**
- Proactive connection invalidation
- Pool notification of server changes
- Fast pool rebalancing

---

### 4. Load Redistribution

**Problem:**
Removing a server concentrates load on remaining servers.

**Impact:**
- Temporary performance degradation
- Risk of cascading failures
- Connection pool saturation

**Solution Requirements:**
- Gradual load shifting
- Capacity headroom
- Monitoring and alerting

---

## Graceful Shutdown Strategy

### Overview

A graceful shutdown ensures all in-flight work completes before the server stops accepting new connections and eventually terminates.

### Implementation

#### Phase 1: Drain Mode

**Server-side endpoint:**
```java
@RestController
@RequestMapping("/admin")
public class OjpAdminController {
    
    private final ServerLifecycleManager lifecycleManager;
    private final ServiceRegistry serviceRegistry;
    
    @PostMapping("/drain")
    public ResponseEntity<DrainStatus> startDrain(
            @RequestParam(defaultValue = "300") int timeoutSeconds) {
        
        log.info("Drain initiated with timeout: {}s", timeoutSeconds);
        
        // 1. Deregister from service discovery
        try {
            serviceRegistry.deregister();
            log.info("Deregistered from service discovery");
        } catch (Exception e) {
            log.error("Failed to deregister from service discovery", e);
        }
        
        // 2. Enter drain mode - stop accepting new connections
        lifecycleManager.enterDrainMode();
        
        // 3. Start monitoring for completion
        CompletableFuture<Boolean> drainComplete = 
            lifecycleManager.waitForDrain(timeoutSeconds);
        
        DrainStatus status = new DrainStatus();
        status.setDraining(true);
        status.setStartTime(Instant.now());
        status.setTimeout(timeoutSeconds);
        status.setActiveConnections(lifecycleManager.getActiveConnectionCount());
        status.setActiveSessions(lifecycleManager.getActiveSessionCount());
        
        return ResponseEntity.accepted().body(status);
    }
    
    @GetMapping("/drain/status")
    public ResponseEntity<DrainStatus> getDrainStatus() {
        DrainStatus status = lifecycleManager.getDrainStatus();
        
        if (status.isComplete()) {
            return ResponseEntity.ok(status);
        } else {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(status);
        }
    }
    
    @PostMapping("/shutdown")
    public ResponseEntity<String> shutdown(
            @RequestParam(defaultValue = "false") boolean force) {
        
        if (!force && !lifecycleManager.isDrainComplete()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body("Server not fully drained. Use force=true to override.");
        }
        
        // Schedule shutdown
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(5000); // Give response time to return
                System.exit(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        return ResponseEntity.ok("Shutdown scheduled");
    }
}
```

#### Phase 2: Lifecycle Management

```java
@Component
public class ServerLifecycleManager {
    
    private volatile ServerState state = ServerState.RUNNING;
    private final ConnectionTracker connectionTracker;
    private final SessionManager sessionManager;
    private Instant drainStartTime;
    
    public enum ServerState {
        RUNNING,    // Normal operation
        DRAINING,   // Drain mode - no new connections
        DRAINED,    // All work complete
        SHUTDOWN    // Shutting down
    }
    
    public void enterDrainMode() {
        if (state != ServerState.RUNNING) {
            throw new IllegalStateException("Cannot enter drain mode from state: " + state);
        }
        
        state = ServerState.DRAINING;
        drainStartTime = Instant.now();
        
        log.info("Entered drain mode at {}", drainStartTime);
        
        // Publish drain event
        eventPublisher.publishEvent(new ServerDrainStartedEvent(this));
    }
    
    public CompletableFuture<Boolean> waitForDrain(int timeoutSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            long timeoutMillis = timeoutSeconds * 1000L;
            
            while (System.currentTimeMillis() - startTime < timeoutMillis) {
                int activeConnections = connectionTracker.getActiveCount();
                int activeSessions = sessionManager.getActiveCount();
                int activeTransactions = sessionManager.getActiveTransactionCount();
                
                log.debug("Drain progress: {} connections, {} sessions, {} transactions",
                         activeConnections, activeSessions, activeTransactions);
                
                if (activeConnections == 0 && activeSessions == 0 && activeTransactions == 0) {
                    state = ServerState.DRAINED;
                    log.info("Drain complete after {}s", 
                            Duration.between(drainStartTime, Instant.now()).getSeconds());
                    
                    eventPublisher.publishEvent(new ServerDrainCompletedEvent(this));
                    return true;
                }
                
                try {
                    Thread.sleep(1000); // Check every second
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            
            // Timeout
            log.warn("Drain timeout after {}s with {} active connections, {} sessions, {} transactions",
                    timeoutSeconds,
                    connectionTracker.getActiveCount(),
                    sessionManager.getActiveCount(),
                    sessionManager.getActiveTransactionCount());
            
            return false;
        });
    }
    
    public boolean acceptsNewConnections() {
        return state == ServerState.RUNNING;
    }
    
    public DrainStatus getDrainStatus() {
        DrainStatus status = new DrainStatus();
        status.setState(state);
        status.setDraining(state == ServerState.DRAINING);
        status.setComplete(state == ServerState.DRAINED);
        status.setStartTime(drainStartTime);
        status.setActiveConnections(connectionTracker.getActiveCount());
        status.setActiveSessions(sessionManager.getActiveCount());
        status.setActiveTransactions(sessionManager.getActiveTransactionCount());
        
        if (drainStartTime != null) {
            status.setDrainDuration(Duration.between(drainStartTime, Instant.now()));
        }
        
        return status;
    }
}
```

#### Phase 3: Connection Rejection

```java
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {
    
    private final ServerLifecycleManager lifecycleManager;
    
    @Override
    public void connect(ConnectRequest request, StreamObserver<SessionInfo> responseObserver) {
        // Check if accepting new connections
        if (!lifecycleManager.acceptsNewConnections()) {
            responseObserver.onError(Status.UNAVAILABLE
                .withDescription("Server is draining and not accepting new connections")
                .asException());
            return;
        }
        
        // Normal connection logic
        try {
            SessionInfo sessionInfo = connectionService.connect(request);
            responseObserver.onNext(sessionInfo);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }
}
```

---

## Connection Draining Implementation

### Client-Side Drain Detection

```java
public class MultinodeConnectionManager {
    
    /**
     * Gracefully removes endpoints, waiting for connections to drain.
     */
    public CompletableFuture<Void> drainAndRemoveEndpoints(
            List<ServerEndpoint> endpoints,
            Duration timeout) {
        
        log.info("Starting graceful drain for {} endpoints with timeout {}",
                endpoints.size(), timeout);
        
        List<CompletableFuture<Void>> drainFutures = endpoints.stream()
            .map(endpoint -> drainSingleEndpoint(endpoint, timeout))
            .collect(Collectors.toList());
        
        return CompletableFuture.allOf(drainFutures.toArray(new CompletableFuture[0]));
    }
    
    private CompletableFuture<Void> drainSingleEndpoint(
            ServerEndpoint endpoint,
            Duration timeout) {
        
        return CompletableFuture.runAsync(() -> {
            // 1. Mark as draining - stops new connection routing
            endpoint.setDraining(true);
            log.info("Endpoint {} marked as draining", endpoint.getAddress());
            
            // 2. Wait for active connections to complete
            long startTime = System.currentTimeMillis();
            long timeoutMillis = timeout.toMillis();
            
            while (System.currentTimeMillis() - startTime < timeoutMillis) {
                ConnectionStats stats = getConnectionStats(endpoint);
                
                if (stats.getActiveConnections() == 0 && stats.getActiveSessions() == 0) {
                    log.info("Endpoint {} successfully drained", endpoint.getAddress());
                    removeEndpoint(endpoint);
                    return;
                }
                
                log.debug("Waiting for endpoint {} to drain: {} connections, {} sessions",
                         endpoint.getAddress(), 
                         stats.getActiveConnections(), 
                         stats.getActiveSessions());
                
                try {
                    Thread.sleep(5000); // Check every 5 seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Drain interrupted", e);
                }
            }
            
            // Timeout - force removal
            log.warn("Endpoint {} drain timeout, forcing removal", endpoint.getAddress());
            forceRemoveEndpoint(endpoint);
        }, drainExecutor);
    }
    
    private void removeEndpoint(ServerEndpoint endpoint) {
        // Mark as unhealthy to stop routing
        endpoint.setHealthy(false);
        
        // Remove from rotation
        serverEndpoints.remove(endpoint);
        
        // Close gRPC channel
        ChannelAndStub channelAndStub = channelMap.remove(endpoint);
        if (channelAndStub != null) {
            channelAndStub.channel.shutdown();
            try {
                channelAndStub.channel.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("Timeout waiting for channel shutdown", e);
                channelAndStub.channel.shutdownNow();
            }
        }
        
        log.info("Endpoint {} removed from cluster", endpoint.getAddress());
    }
    
    private void forceRemoveEndpoint(ServerEndpoint endpoint) {
        // Invalidate all sessions
        invalidateSessionsForServer(endpoint);
        
        // Close all connections
        closeConnectionsForServer(endpoint);
        
        // Remove endpoint
        removeEndpoint(endpoint);
    }
}
```

### Connection Tracking Enhancement

```java
public class ConnectionTracker {
    
    // Track connections by server
    private final Map<ServerEndpoint, Set<Connection>> connectionsByServer = 
        new ConcurrentHashMap<>();
    
    // Track connection metadata
    private final Map<Connection, ConnectionMetadata> connectionMetadata = 
        new ConcurrentHashMap<>();
    
    public void register(Connection connection, ServerEndpoint server) {
        connectionsByServer
            .computeIfAbsent(server, k -> ConcurrentHashMap.newKeySet())
            .add(connection);
        
        ConnectionMetadata metadata = new ConnectionMetadata();
        metadata.setServer(server);
        metadata.setCreatedAt(Instant.now());
        metadata.setLastUsed(Instant.now());
        connectionMetadata.put(connection, metadata);
    }
    
    public void unregister(Connection connection) {
        ConnectionMetadata metadata = connectionMetadata.remove(connection);
        if (metadata != null) {
            ServerEndpoint server = metadata.getServer();
            Set<Connection> connections = connectionsByServer.get(server);
            if (connections != null) {
                connections.remove(connection);
            }
        }
    }
    
    public ConnectionStats getConnectionStats(ServerEndpoint server) {
        Set<Connection> connections = connectionsByServer.getOrDefault(
            server, Collections.emptySet());
        
        int active = 0;
        int idle = 0;
        int inTransaction = 0;
        
        for (Connection conn : connections) {
            ConnectionMetadata metadata = connectionMetadata.get(conn);
            if (metadata != null) {
                if (metadata.isInTransaction()) {
                    inTransaction++;
                    active++;
                } else if (metadata.isIdle()) {
                    idle++;
                } else {
                    active++;
                }
            }
        }
        
        return new ConnectionStats(connections.size(), active, idle, inTransaction);
    }
    
    public Set<Connection> getConnectionsForServer(ServerEndpoint server) {
        return connectionsByServer.getOrDefault(server, Collections.emptySet());
    }
}
```

---

## Rolling Update Strategy

### Automated Rolling Update

```java
public class RollingUpdateOrchestrator {
    
    private final MultinodeConnectionManager connectionManager;
    private final HealthCheckValidator healthCheckValidator;
    
    /**
     * Performs a rolling update of the cluster.
     * 
     * @param updateStrategy Strategy for the update
     * @return Result of the rolling update
     */
    public RollingUpdateResult performRollingUpdate(RollingUpdateStrategy updateStrategy) {
        
        List<ServerEndpoint> servers = connectionManager.getAllEndpoints();
        int totalServers = servers.size();
        int maxConcurrentUpdates = updateStrategy.getMaxConcurrentUpdates();
        
        log.info("Starting rolling update of {} servers ({} at a time)",
                totalServers, maxConcurrentUpdates);
        
        RollingUpdateResult result = new RollingUpdateResult();
        
        // Process servers in batches
        for (int i = 0; i < totalServers; i += maxConcurrentUpdates) {
            int endIndex = Math.min(i + maxConcurrentUpdates, totalServers);
            List<ServerEndpoint> batch = servers.subList(i, endIndex);
            
            log.info("Updating batch {}/{}: {}", 
                    (i / maxConcurrentUpdates) + 1,
                    (totalServers + maxConcurrentUpdates - 1) / maxConcurrentUpdates,
                    batch.stream().map(ServerEndpoint::getAddress).collect(Collectors.toList()));
            
            try {
                updateBatch(batch, updateStrategy);
                result.addSuccessful(batch);
            } catch (Exception e) {
                log.error("Batch update failed", e);
                result.addFailed(batch, e);
                
                if (updateStrategy.isStopOnError()) {
                    log.error("Stopping rolling update due to error");
                    break;
                }
            }
            
            // Wait between batches
            if (endIndex < totalServers) {
                log.info("Waiting {}s before next batch", 
                        updateStrategy.getBatchDelaySeconds());
                sleep(updateStrategy.getBatchDelaySeconds());
            }
        }
        
        return result;
    }
    
    private void updateBatch(List<ServerEndpoint> batch, RollingUpdateStrategy strategy) 
            throws UpdateException {
        
        for (ServerEndpoint endpoint : batch) {
            updateSingleServer(endpoint, strategy);
        }
    }
    
    private void updateSingleServer(ServerEndpoint endpoint, RollingUpdateStrategy strategy) 
            throws UpdateException {
        
        log.info("Updating server: {}", endpoint.getAddress());
        
        // 1. Drain the server
        log.info("Draining server: {}", endpoint.getAddress());
        boolean drained = drainServer(endpoint, strategy.getDrainTimeout());
        
        if (!drained) {
            throw new UpdateException("Server drain timeout: " + endpoint.getAddress());
        }
        
        // 2. Perform the update (delegate to external script/API)
        log.info("Performing update on server: {}", endpoint.getAddress());
        strategy.getUpdateFunction().accept(endpoint);
        
        // 3. Wait for health check
        log.info("Waiting for server to be healthy: {}", endpoint.getAddress());
        boolean healthy = waitForHealthy(endpoint, strategy.getHealthCheckTimeout());
        
        if (!healthy) {
            throw new UpdateException("Server failed health check: " + endpoint.getAddress());
        }
        
        // 4. Add back to rotation
        log.info("Adding server back to rotation: {}", endpoint.getAddress());
        connectionManager.addEndpoints(Collections.singletonList(endpoint));
        
        // 5. Wait for stabilization
        sleep(strategy.getStabilizationDelaySeconds());
        
        log.info("Server update complete: {}", endpoint.getAddress());
    }
    
    private boolean drainServer(ServerEndpoint endpoint, Duration timeout) {
        try {
            // Call server drain endpoint
            HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://" + endpoint.getAddress() + "/admin/drain"))
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "timeout=" + timeout.getSeconds()))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            
            if (response.statusCode() != 202) {
                log.error("Failed to initiate drain: {}", response.body());
                return false;
            }
            
            // Wait for drain completion
            return waitForDrainComplete(endpoint, timeout);
            
        } catch (Exception e) {
            log.error("Error draining server", e);
            return false;
        }
    }
    
    private boolean waitForDrainComplete(ServerEndpoint endpoint, Duration timeout) {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeout.toMillis();
        
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try {
                HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("http://" + endpoint.getAddress() + "/admin/drain/status"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                
                if (response.statusCode() == 200) {
                    // Parse response to check if drain complete
                    DrainStatus status = objectMapper.readValue(
                        response.body(), DrainStatus.class);
                    
                    if (status.isComplete()) {
                        return true;
                    }
                }
                
                Thread.sleep(5000); // Check every 5 seconds
                
            } catch (Exception e) {
                log.error("Error checking drain status", e);
            }
        }
        
        return false;
    }
    
    private boolean waitForHealthy(ServerEndpoint endpoint, Duration timeout) {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeout.toMillis();
        
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            if (healthCheckValidator.validate(endpoint)) {
                return true;
            }
            
            sleep(5); // Check every 5 seconds
        }
        
        return false;
    }
    
    private void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### Rolling Update Configuration

```java
@Data
public class RollingUpdateStrategy {
    
    // Maximum servers to update concurrently
    private int maxConcurrentUpdates = 1;
    
    // Delay between batches (seconds)
    private int batchDelaySeconds = 30;
    
    // Timeout for server drain (seconds)
    private Duration drainTimeout = Duration.ofMinutes(5);
    
    // Timeout for health check (seconds)
    private Duration healthCheckTimeout = Duration.ofSeconds(60);
    
    // Delay after adding server back (for stabilization)
    private int stabilizationDelaySeconds = 30;
    
    // Stop on first error
    private boolean stopOnError = true;
    
    // Function to perform the actual update
    private Consumer<ServerEndpoint> updateFunction;
}
```

---

## Blue-Green Deployment

### Strategy Overview

Blue-green deployment maintains two complete environments:
- **Blue**: Current production
- **Green**: New version being deployed

### Implementation

```java
public class BlueGreenDeploymentManager {
    
    private final ServiceDiscoveryManager discoveryManager;
    private final MultinodeConnectionManager connectionManager;
    
    public enum Environment {
        BLUE, GREEN
    }
    
    /**
     * Switches traffic from one environment to another.
     */
    public void switchEnvironment(Environment from, Environment to) {
        
        log.info("Switching traffic from {} to {}", from, to);
        
        // 1. Discover servers in target environment
        List<ServerEndpoint> targetServers = discoveryManager.discoverByTag(
            "environment", to.name().toLowerCase());
        
        if (targetServers.isEmpty()) {
            throw new IllegalStateException("No servers found in " + to + " environment");
        }
        
        log.info("Found {} servers in {} environment", targetServers.size(), to);
        
        // 2. Validate target servers are healthy
        List<ServerEndpoint> healthyTargets = targetServers.stream()
            .filter(this::isHealthy)
            .collect(Collectors.toList());
        
        if (healthyTargets.size() < targetServers.size()) {
            throw new IllegalStateException("Not all " + to + " servers are healthy");
        }
        
        // 3. Add target servers to rotation
        connectionManager.addEndpoints(healthyTargets);
        
        // 4. Wait for stabilization
        log.info("Waiting for target servers to stabilize...");
        sleep(30);
        
        // 5. Drain and remove source servers
        List<ServerEndpoint> sourceServers = discoveryManager.discoverByTag(
            "environment", from.name().toLowerCase());
        
        log.info("Draining {} servers from {} environment", sourceServers.size(), from);
        
        try {
            connectionManager.drainAndRemoveEndpoints(
                sourceServers, 
                Duration.ofMinutes(5)
            ).get();
        } catch (Exception e) {
            log.error("Error draining source servers", e);
            // Rollback: remove target servers
            connectionManager.removeEndpoints(healthyTargets, false);
            throw new RuntimeException("Traffic switch failed", e);
        }
        
        log.info("Traffic switch complete from {} to {}", from, to);
    }
    
    /**
     * Performs a gradual traffic shift from blue to green.
     */
    public void gradualShift(Environment from, Environment to, 
                            Duration shiftDuration) {
        
        List<ServerEndpoint> blueServers = discoveryManager.discoverByTag(
            "environment", from.name().toLowerCase());
        List<ServerEndpoint> greenServers = discoveryManager.discoverByTag(
            "environment", to.name().toLowerCase());
        
        // Calculate weight adjustment steps
        int steps = 10;
        long stepDuration = shiftDuration.toMillis() / steps;
        
        for (int i = 0; i <= steps; i++) {
            int greenWeight = i * 10; // 0%, 10%, 20%, ..., 100%
            int blueWeight = 100 - greenWeight;
            
            log.info("Traffic shift progress: {}% blue, {}% green", 
                    blueWeight, greenWeight);
            
            connectionManager.setWeightedRouting(blueServers, blueWeight);
            connectionManager.setWeightedRouting(greenServers, greenWeight);
            
            if (i < steps) {
                sleep((int) (stepDuration / 1000));
            }
        }
        
        log.info("Gradual traffic shift complete");
    }
    
    private boolean isHealthy(ServerEndpoint endpoint) {
        try {
            return healthCheckValidator.validate(endpoint);
        } catch (Exception e) {
            return false;
        }
    }
    
    private void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

---

## Canary Deployment

### Strategy Overview

Canary deployment releases new version to small subset of servers to validate before full rollout.

### Implementation

```java
public class CanaryDeploymentManager {
    
    private final MultinodeConnectionManager connectionManager;
    
    /**
     * Deploys canary version and monitors performance.
     */
    public CanaryDeploymentResult deployCanary(CanaryStrategy strategy) {
        
        log.info("Starting canary deployment with {} traffic", 
                strategy.getInitialTrafficPercentage());
        
        List<ServerEndpoint> productionServers = 
            discoveryManager.discoverByTag("version", "stable");
        List<ServerEndpoint> canaryServers = 
            discoveryManager.discoverByTag("version", "canary");
        
        CanaryDeploymentResult result = new CanaryDeploymentResult();
        
        try {
            // Phase 1: Initial canary deployment
            int canaryPercentage = strategy.getInitialTrafficPercentage();
            int productionPercentage = 100 - canaryPercentage;
            
            connectionManager.setWeightedRouting(canaryServers, canaryPercentage);
            connectionManager.setWeightedRouting(productionServers, productionPercentage);
            
            log.info("Canary deployed with {}% traffic", canaryPercentage);
            
            // Phase 2: Monitor canary performance
            boolean canaryHealthy = monitorCanary(
                canaryServers, 
                strategy.getMonitorDuration(),
                strategy.getErrorRateThreshold()
            );
            
            if (!canaryHealthy) {
                log.error("Canary validation failed, rolling back");
                rollbackCanary(productionServers, canaryServers);
                result.setSuccess(false);
                result.setMessage("Canary failed validation");
                return result;
            }
            
            // Phase 3: Gradually increase canary traffic
            if (strategy.isGradualRollout()) {
                for (int percentage : strategy.getRolloutSteps()) {
                    log.info("Increasing canary traffic to {}%", percentage);
                    
                    connectionManager.setWeightedRouting(
                        canaryServers, percentage);
                    connectionManager.setWeightedRouting(
                        productionServers, 100 - percentage);
                    
                    // Monitor at each step
                    boolean healthy = monitorCanary(
                        canaryServers,
                        strategy.getStepMonitorDuration(),
                        strategy.getErrorRateThreshold()
                    );
                    
                    if (!healthy) {
                        log.error("Canary validation failed at {}% traffic", percentage);
                        rollbackCanary(productionServers, canaryServers);
                        result.setSuccess(false);
                        result.setMessage("Canary failed at " + percentage + "% traffic");
                        return result;
                    }
                }
            }
            
            // Phase 4: Complete rollout
            log.info("Promoting canary to production");
            connectionManager.setWeightedRouting(canaryServers, 100);
            
            // Phase 5: Remove old production servers
            connectionManager.drainAndRemoveEndpoints(
                productionServers,
                Duration.ofMinutes(5)
            ).get();
            
            result.setSuccess(true);
            result.setMessage("Canary deployment successful");
            
        } catch (Exception e) {
            log.error("Canary deployment error", e);
            rollbackCanary(productionServers, canaryServers);
            result.setSuccess(false);
            result.setMessage("Deployment error: " + e.getMessage());
        }
        
        return result;
    }
    
    private boolean monitorCanary(
            List<ServerEndpoint> canaryServers,
            Duration monitorDuration,
            double errorRateThreshold) {
        
        log.info("Monitoring canary for {}", monitorDuration);
        
        long startTime = System.currentTimeMillis();
        long endTime = startTime + monitorDuration.toMillis();
        
        while (System.currentTimeMillis() < endTime) {
            // Collect metrics from canary servers
            MetricsSnapshot metrics = collectMetrics(canaryServers);
            
            double errorRate = metrics.getErrorRate();
            double avgLatency = metrics.getAverageLatency();
            
            log.debug("Canary metrics: error rate={}, avg latency={}ms",
                     errorRate, avgLatency);
            
            // Check error rate threshold
            if (errorRate > errorRateThreshold) {
                log.error("Canary error rate {} exceeds threshold {}",
                         errorRate, errorRateThreshold);
                return false;
            }
            
            // Check for server health
            boolean allHealthy = canaryServers.stream()
                .allMatch(server -> server.isHealthy());
            
            if (!allHealthy) {
                log.error("One or more canary servers unhealthy");
                return false;
            }
            
            try {
                Thread.sleep(10000); // Check every 10 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        log.info("Canary monitoring successful");
        return true;
    }
    
    private void rollbackCanary(
            List<ServerEndpoint> productionServers,
            List<ServerEndpoint> canaryServers) {
        
        log.warn("Rolling back canary deployment");
        
        // Restore production traffic
        connectionManager.setWeightedRouting(productionServers, 100);
        connectionManager.setWeightedRouting(canaryServers, 0);
        
        // Remove canary servers
        connectionManager.removeEndpoints(canaryServers, false);
        
        log.info("Rollback complete");
    }
}
```

---

## Session Management During Updates

### Preserving Sessions

```java
public class SessionPreservationManager {
    
    /**
     * Attempts to migrate sessions from source to target server.
     * 
     * Note: This is complex due to connection state. May not be feasible
     * for all scenarios. Alternative is graceful session termination.
     */
    public void migrateSessions(ServerEndpoint from, ServerEndpoint to) {
        
        Set<String> sessionUUIDs = sessionManager.getSessionsForServer(from);
        
        log.info("Migrating {} sessions from {} to {}",
                sessionUUIDs.size(), from.getAddress(), to.getAddress());
        
        for (String sessionUUID : sessionUUIDs) {
            try {
                // Get session context
                SessionContext context = sessionManager.getSessionContext(sessionUUID);
                
                // Check if session can be migrated
                if (!context.canMigrate()) {
                    log.warn("Session {} cannot be migrated (in transaction)",
                            sessionUUID);
                    continue;
                }
                
                // Create equivalent session on target server
                SessionInfo newSession = createSessionOnTarget(to, context);
                
                // Update session binding
                connectionManager.rebindSession(sessionUUID, to);
                
                // Mark old session for cleanup
                sessionManager.markForCleanup(from, sessionUUID);
                
                log.debug("Migrated session {} to {}", sessionUUID, to.getAddress());
                
            } catch (Exception e) {
                log.error("Failed to migrate session {}", sessionUUID, e);
            }
        }
    }
    
    /**
     * Waits for all transactions to complete before allowing shutdown.
     */
    public boolean waitForTransactionCompletion(
            ServerEndpoint server,
            Duration timeout) {
        
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeout.toMillis();
        
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            int activeTransactions = sessionManager.getActiveTransactionCount(server);
            
            if (activeTransactions == 0) {
                log.info("All transactions completed for server {}", 
                        server.getAddress());
                return true;
            }
            
            log.debug("Waiting for {} transactions to complete on {}",
                     activeTransactions, server.getAddress());
            
            try {
                Thread.sleep(2000); // Check every 2 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        log.warn("Timeout waiting for transactions on {}", server.getAddress());
        return false;
    }
}
```

---

## Monitoring and Validation

### Key Metrics

```java
@Component
public class ClusterUpdateMetrics {
    
    private final MeterRegistry meterRegistry;
    
    // Drain metrics
    private final Counter drainInitiated;
    private final Counter drainCompleted;
    private final Counter drainTimeout;
    private final Timer drainDuration;
    
    // Update metrics
    private final Counter updatesStarted;
    private final Counter updatesSucceeded;
    private final Counter updatesFailed;
    private final Timer updateDuration;
    
    // Connection metrics
    private final Gauge activeConnections;
    private final Gauge activeSessions;
    private final Gauge activeTransactions;
    
    public ClusterUpdateMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // Initialize counters
        this.drainInitiated = Counter.builder("ojp.drain.initiated")
            .description("Number of drain operations initiated")
            .register(meterRegistry);
        
        this.drainCompleted = Counter.builder("ojp.drain.completed")
            .description("Number of drain operations completed successfully")
            .register(meterRegistry);
        
        this.drainTimeout = Counter.builder("ojp.drain.timeout")
            .description("Number of drain operations that timed out")
            .register(meterRegistry);
        
        this.drainDuration = Timer.builder("ojp.drain.duration")
            .description("Time taken to drain servers")
            .register(meterRegistry);
        
        // Update metrics
        this.updatesStarted = Counter.builder("ojp.updates.started")
            .description("Number of server updates started")
            .register(meterRegistry);
        
        this.updatesSucceeded = Counter.builder("ojp.updates.succeeded")
            .description("Number of server updates completed successfully")
            .register(meterRegistry);
        
        this.updatesFailed = Counter.builder("ojp.updates.failed")
            .description("Number of server updates that failed")
            .register(meterRegistry);
        
        this.updateDuration = Timer.builder("ojp.updates.duration")
            .description("Time taken to update servers")
            .register(meterRegistry);
    }
    
    public void recordDrainStarted() {
        drainInitiated.increment();
    }
    
    public void recordDrainCompleted(Duration duration) {
        drainCompleted.increment();
        drainDuration.record(duration);
    }
    
    public void recordDrainTimeout() {
        drainTimeout.increment();
    }
    
    // Additional metric recording methods...
}
```

### Health Checks

```yaml
# Prometheus alert rules
groups:
  - name: ojp_cluster_updates
    interval: 30s
    rules:
      # Alert when drain takes too long
      - alert: OjpDrainDurationHigh
        expr: ojp_drain_duration_seconds > 300
        for: 5m
        annotations:
          summary: "OJP server drain taking too long"
          description: "Server drain duration {{ $value }}s exceeds 5 minutes"
      
      # Alert when drain timeout rate is high
      - alert: OjpDrainTimeoutRateHigh
        expr: rate(ojp_drain_timeout_total[5m]) > 0.1
        annotations:
          summary: "High rate of OJP drain timeouts"
          description: "Drain timeout rate {{ $value }}/s"
      
      # Alert when update failure rate is high
      - alert: OjpUpdateFailureRateHigh
        expr: rate(ojp_updates_failed_total[5m]) / rate(ojp_updates_started_total[5m]) > 0.2
        annotations:
          summary: "High OJP update failure rate"
          description: "Update failure rate {{ $value | humanizePercentage }}"
      
      # Alert when active connections during drain
      - alert: OjpActiveConnectionsDuringDrain
        expr: ojp_active_connections > 0 AND ojp_server_draining == 1
        for: 10m
        annotations:
          summary: "Active connections remaining during drain"
          description: "{{ $value }} connections still active after 10 minutes of draining"
```

---

## Troubleshooting Guide

### Problem: Drain Never Completes

**Symptoms:**
- Server stays in drain mode indefinitely
- Active connection count doesn't decrease
- Timeout occurs

**Possible Causes:**
1. Long-running transactions
2. Connection leaks in application
3. Connection pool not releasing connections
4. Stuck queries

**Solutions:**
1. Check for long-running queries:
   ```sql
   -- PostgreSQL
   SELECT pid, now() - query_start AS duration, query
   FROM pg_stat_activity
   WHERE state != 'idle'
   ORDER BY duration DESC;
   ```

2. Force transaction rollback (last resort):
   ```java
   sessionManager.rollbackActiveTransactions(server);
   ```

3. Configure aggressive connection pool validation:
   ```properties
   hikari.connection-test-query=SELECT 1
   hikari.validation-timeout=3000
   hikari.leak-detection-threshold=60000
   ```

---

### Problem: Update Causes Service Disruption

**Symptoms:**
- Increased error rate during update
- Connection timeout errors
- Performance degradation

**Possible Causes:**
1. Updating too many servers concurrently
2. Insufficient capacity on remaining servers
3. Connection pool exhaustion
4. Slow drain causing traffic spike

**Solutions:**
1. Reduce concurrent updates:
   ```properties
   ojp.update.maxConcurrentUpdates=1
   ```

2. Increase capacity before update:
   - Add temporary servers
   - Increase connection pool sizes

3. Increase drain timeout:
   ```properties
   ojp.drain.timeout=600
   ```

4. Monitor and adjust:
   - Watch error rates
   - Monitor connection pool metrics
   - Adjust timing between updates

---

### Problem: Session Binding Lost After Update

**Symptoms:**
- "Connection not found" errors after server restart
- Session UUID mismatches

**Possible Causes:**
1. Server restarted without proper drain
2. Session state not persisted
3. Client-side caching issues

**Solutions:**
1. Ensure proper drain before restart:
   ```bash
   curl -X POST http://server:8080/admin/drain
   # Wait for completion
   curl http://server:8080/admin/drain/status
   # Then restart
   ```

2. Implement session persistence (if needed):
   ```java
   sessionManager.enablePersistence(redisConnection);
   ```

3. Clear client-side session cache:
   ```java
   connectionManager.clearSessionBindings();
   ```

---

## Best Practices Summary

### Planning

1. **Capacity Planning**
   - Maintain N+1 redundancy
   - Reserve headroom for updates
   - Plan for peak load times

2. **Timing**
   - Update during low-traffic periods
   - Avoid peak hours
   - Schedule maintenance windows

3. **Communication**
   - Notify stakeholders
   - Update status pages
   - Document changes

### Execution

1. **Pre-Update Checks**
   - Verify all servers healthy
   - Check capacity headroom
   - Test drain procedure
   - Prepare rollback plan

2. **During Update**
   - Monitor error rates
   - Watch connection metrics
   - Track drain progress
   - Be ready to rollback

3. **Post-Update Validation**
   - Verify all servers healthy
   - Check connection distribution
   - Monitor error rates
   - Validate performance

### Automation

1. **Scripted Updates**
   - Automate drain and health checks
   - Script rollback procedures
   - Integrate with CI/CD

2. **Monitoring Integration**
   - Set up alerts
   - Track metrics
   - Log all actions

3. **Documentation**
   - Maintain runbooks
   - Document lessons learned
   - Update procedures

---

## Conclusion

Safe cluster updates require careful orchestration of:
1. Graceful draining
2. Connection management
3. Session handling
4. Health validation
5. Comprehensive monitoring

By following these strategies and best practices, OJP clusters can be updated with zero downtime and minimal risk of service disruption.

---

## References

- [OJP Multinode Configuration](../multinode/README.md)
- [Server Recovery and Redistribution](../multinode/server-recovery-and-redistribution.md)
- [Dynamic Server Discovery](./DYNAMIC_SERVER_DISCOVERY.md)
- [Connection Pool Configuration](../configuration/ojp-jdbc-configuration.md)

# Multinode Connection Redistribution Fix

## Issue Summary

The `add-load-aware-selection` branch broke the Multinode Integration Test workflow, specifically the "Check PostgreSQL connections after killing Server 2" step. The test expected approximately 20 connections to be maintained throughout the test, but connections were not properly redistributing to recovered servers.

## Root Cause Analysis

### Problem Background

In commit `c6ecaaa` with message "Removed active redistribution of XA connections after server resurrection", the connection redistribution logic was completely removed from the `performHealthCheck()` method in `MultinodeConnectionManager.java`.

### What Was Removed

The following code block was removed from the `performHealthCheck()` method:

```java
// If any servers recovered, trigger connection redistribution
if (!recoveredServers.isEmpty() && healthCheckConfig.isRedistributionEnabled()) {
    log.info("Triggering connection redistribution for {} recovered server(s)", 
            recoveredServers.size());
    
    List<ServerEndpoint> allHealthyServers = serverEndpoints.stream()
            .filter(ServerEndpoint::isHealthy)
            .collect(Collectors.toList());
    
    try {
        connectionRedistributor.rebalance(recoveredServers, allHealthyServers);
    } catch (Exception e) {
        log.error("Failed to redistribute connections after server recovery: {}", 
                e.getMessage(), e);
    }
}
```

### Why This Broke Non-XA Flows

The removal affected **ALL** connection modes, not just XA mode:

1. **Non-XA Mode**: Uses `ConnectionRedistributor` to rebalance connections across all healthy servers when a failed server recovers
2. **XA Mode**: Uses `XAConnectionRedistributor` for special XA-specific connection management

By removing the redistribution logic entirely, non-XA connections no longer redistributed when servers recovered. This caused the following scenario in the Multinode Integration Test:

1. Test starts with 2 servers (Server 1 and Server 2) - each gets ~10 connections (total ~20)
2. Server 2 is killed - Server 1 now handles all ~20 connections
3. Server 2 is restarted and marked healthy
4. **Without redistribution**: New connections go to Server 2, but existing connections stay on Server 1
5. **Test expectation**: Should maintain ~20 connections distributed across both servers
6. **Actual behavior**: Connections don't redistribute, causing test failure

### Intent vs. Reality

The branch was intended to only affect XA scenarios (as indicated by its name and commit message), but the removal of the redistribution logic inadvertently affected non-XA flows as well. The code change was too broad in scope.

## Solution

### Fix Implementation

Restored the connection redistribution logic for non-XA mode only, by adding a check for `xaConnectionRedistributor == null`:

```java
// For non-XA mode: trigger connection redistribution when servers recover
// XA mode handles redistribution differently (through invalidation), so skip it
if (!recoveredServers.isEmpty() && xaConnectionRedistributor == null && healthCheckConfig.isRedistributionEnabled()) {
    log.info("Triggering connection redistribution for {} recovered server(s)", 
            recoveredServers.size());
    
    List<ServerEndpoint> allHealthyServers = serverEndpoints.stream()
            .filter(ServerEndpoint::isHealthy)
            .collect(Collectors.toList());
    
    try {
        connectionRedistributor.rebalance(recoveredServers, allHealthyServers);
    } catch (Exception e) {
        log.error("Failed to redistribute connections after server recovery: {}", 
                e.getMessage(), e);
    }
}
```

### Key Changes

1. **Added condition**: `xaConnectionRedistributor == null` - Only redistribute for non-XA mode
2. **Preserved XA behavior**: XA mode (`xaConnectionRedistributor != null`) skips this redistribution and handles recovery through its own invalidation mechanism
3. **Maintained original non-XA behavior**: Non-XA connections redistribute as they did before the branch

### How It Works

#### Non-XA Mode (`xaConnectionRedistributor == null`)
1. Health check detects recovered server
2. Server marked healthy and added to `recoveredServers` list
3. Redistribution triggered via `connectionRedistributor.rebalance()`
4. Existing connections on overloaded servers are marked for closure
5. New connections distribute evenly across all healthy servers (including recovered)

#### XA Mode (`xaConnectionRedistributor != null`)
1. Health check detects recovered server
2. Server marked healthy
3. **No redistribution triggered** - XA mode handles this differently
4. XA connections are managed through session invalidation when servers fail
5. New XA connections are created on recovered servers as needed

## Testing

### Unit Tests Verified
- `MultinodeConnectionManagerClusterHealthTest`: All 7 tests pass
- `LoadAwareServerSelectionTest`: All 8 tests pass

### Integration Test Expected Behavior
The Multinode Integration Test workflow should now:
1. Maintain ~20 connections when both servers are running
2. Maintain ~20 connections when Server 1 is killed (all on Server 2)
3. Maintain ~20 connections when Server 1 recovers (redistributed across both)
4. Maintain ~20 connections when Server 2 is killed (all on Server 1)
5. Maintain ~20 connections when Server 2 recovers (redistributed across both)

## Files Modified

- `ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeConnectionManager.java`
  - Added back non-XA connection redistribution logic in `performHealthCheck()` method
  - Lines added: 254-271 (18 lines)

## Lessons Learned

1. **Scope of Changes**: When making changes intended for specific scenarios (XA), ensure they don't inadvertently affect other scenarios (non-XA)
2. **Test Coverage**: The Multinode Integration Test caught this regression, highlighting the importance of comprehensive integration tests
3. **Code Comments**: Clear comments distinguishing XA vs. non-XA behavior help prevent such issues
4. **Conditional Logic**: Use explicit conditions (`xaConnectionRedistributor == null`) to separate behavior paths rather than removing shared code

## Related Documentation

- [Server Recovery and Redistribution](../multinode/server-recovery-and-redistribution.md)
- [Multinode Integration Test Workflow](../../.github/workflows/multinode-integration.yml)

## Version Information

- **Fixed in**: 0.4.0-beta
- **Branch**: copilot/fix-postgresql-connection-issue  
- **Date**: 2025-11-24

# Changelog

All notable changes to the Open J Proxy (OJP) project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed - BREAKING: XA Connection Pooling Migration
- **Replaced semaphore-based XA transaction limiter with Atomikos connection pooling**
  - **REMOVED**: `ojp.xa.maxTransactions` configuration property
  - **REMOVED**: `ojp.xa.startTimeoutMillis` configuration property
  - **Migration**: Use `ojp.connection.pool.maximumPoolSize` and `ojp.connection.pool.connectionTimeout` instead
  - XA connections now use Atomikos connection pool infrastructure (AtomikosDataSourceBean wrapping driver XADataSource)
  - Pool sizing properties apply to both regular (HikariCP) and XA (Atomikos) connections
  - Server remains XA pass-through - no transaction manager runs on server
  - Atomikos used only for connection pooling, not transaction management
  - **Affects**: All applications using `OjpXADataSource` or XA distributed transactions
  - **Migration guide**: See `documents/configuration/ojp-jdbc-configuration.md`

### Fixed
- **Fixed server recovery in multinode setup to work continuously, not just when all servers are down**
  - Previously, unhealthy servers were only recovered when ALL servers were down
  - Now, recovery attempts happen during every `connect()` and server selection operation
  - Unhealthy servers are retried after the configured retry delay (default: 5 seconds)
  - This ensures that if one server goes down in a multi-server setup, it will automatically recover when brought back online
  - Addresses issue where "Connected to 1 out of 2 servers" message persisted even after bringing downed server back up

- **Fixed "Channel shutdown invoked" errors after server recovery**
  - Problem: When a failed server recovered, the old channel was immediately shut down, causing errors for in-flight operations
  - Root cause: Cached `StatementServiceGrpcClient` instances held references to old (shutdown) channel stubs
  - Solution: 
    - Removed immediate `channel.shutdown()` call when server fails - channels now garbage collected naturally
    - `getClient()` now detects when stubs have changed (after recovery) and creates new client with fresh stubs
    - Prevents "UNAVAILABLE: Channel shutdown invoked" errors during and after recovery
  - Result: Smooth recovery without disrupting in-flight operations

### Removed - API Cleanup (Follow-up to PR #93)
- **Removed deprecated and unused server selection APIs from MultinodeConnectionManager**
  - Removed `selectServer()`: Unused public method, functionality now handled internally by `affinityServer()`
  - Removed `getServerForSession(SessionInfo)`: Deprecated method replaced by `affinityServer(String sessionKey)`
  - These removals consolidate the multinode server selection API to a single, clearer method

### Changed - BREAKING
- **Replaced Java native serialization with Protocol Buffers for Map/List/Properties transport**
  - Maps, Lists, and Properties objects are now serialized using Protocol Buffers (proto3) instead of Java native serialization
  - This change provides language independence and better cross-platform compatibility
  - **Breaking Change**: Server and driver must be updated together. Old serialized data cannot be read by the new version
  - The `SerializationHandler` class is retained for JDBC-specific complex types (Blob, Clob, etc.) but is no longer used for simple containers
  - New `ProtoSerialization` utility class in `org.openjproxy.grpc.transport` package handles container serialization

### Added
- New proto file: `ojp-grpc-commons/src/main/proto/containers.proto` defining protobuf messages for Maps, Lists, and Properties
- New class: `org.openjproxy.grpc.transport.ProtoSerialization` for protobuf-based serialization of containers
- Comprehensive test suite: `ProtoSerializationTest` with 25 unit tests covering round-trip, edge cases, and error handling

### Technical Details
- Container types wrapped in a top-level `Container` message to enable type-safe deserialization
- All numbers are stored as `double` in protobuf (precision note: very large longs may lose precision)
- Nested Maps and Lists are fully supported
- UTF-8 and special characters are properly handled
- Empty containers serialize correctly (not as empty payloads)

### Migration Guide

#### Migrating from Deprecated Server Selection APIs (PR #93 Follow-up)

If you were directly using `MultinodeConnectionManager` APIs (rare, as these are internal):

**Before (deprecated):**
```java
// Using getServerForSession
SessionInfo sessionInfo = ...;
ServerEndpoint server = connectionManager.getServerForSession(sessionInfo);

// Using selectServer
ServerEndpoint server = connectionManager.selectServer();
```

**After (current API):**
```java
// Replace getServerForSession with affinityServer
SessionInfo sessionInfo = ...;
String sessionKey = (sessionInfo != null && sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) 
        ? sessionInfo.getSessionUUID() : null;
ServerEndpoint server = connectionManager.affinityServer(sessionKey);

// selectServer is removed - use affinityServer with null session key for round-robin
ServerEndpoint server = connectionManager.affinityServer(null);
```

**Key Points:**
- `affinityServer(String sessionKey)` is the unified API for server selection
- Pass `null` or empty string for round-robin selection (no session affinity)
- Pass a session UUID for session-sticky routing
- Most users don't need to change anything as these are internal APIs used by `MultinodeStatementService`

#### Protocol Buffers Serialization Migration

For applications using OJP:
1. Update both server and driver to the same version simultaneously
2. No code changes required in your application - the change is transparent at the API level
3. Previously serialized data (if any was persisted) will not be readable after upgrade

For contributors/developers:
- When adding features that transport Maps, Lists, or Properties, use `ProtoSerialization` instead of `SerializationHandler`
- JDBC-specific types (Blob, Clob, etc.) should continue using Java serialization
- See `ProtoSerializationTest` for usage examples

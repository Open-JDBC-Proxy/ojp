# Copilot Prompt: Implement Audit Logging Features

## Overview
Implement comprehensive audit logging functionality for the OJP (Open J Proxy) server as described in Chapter 11 - Security, Audit Logging section of the ebook. This feature will provide security and compliance capabilities by logging connections, queries, and authentication events.

## Context
OJP is a JDBC Type 3 driver and Layer 7 Proxy Server that acts as an intelligent intermediary between applications and databases. The audit logging feature will track all significant security-related events passing through the proxy to support:
- Security monitoring and incident response
- Compliance requirements (PCI-DSS, HIPAA, GDPR)
- Performance analysis and troubleshooting
- Forensic analysis of database access patterns

## Requirements

### 1. Configuration Properties
Add support for the following configuration properties (following the existing pattern in `ServerConfiguration.java`):

```properties
# Enable/disable audit logging globally
ojp.server.audit.enabled=true

# Path to audit log file (supports absolute and relative paths)
ojp.server.audit.log.path=/var/log/ojp/audit.log

# Log all connection events (connect, disconnect, connection errors)
ojp.server.audit.log.connections=true

# Log all queries (WARNING: High performance impact - use with caution)
# Should log: SQL statement, execution time, result set size, parameters
ojp.server.audit.log.queries=false

# Log authentication events (login attempts, failures, certificate validation)
ojp.server.audit.log.auth=true
```

### 2. Audit Log Format
The audit log should follow a structured format for easy parsing and analysis:

```
[TIMESTAMP] [LEVEL] [EVENT_TYPE] [SESSION_ID] [CLIENT_IP] [USER] - [MESSAGE] - [METADATA_JSON]
```

Example log entries:
```
[2026-01-24T21:25:22.587Z] [INFO] [CONNECTION] [sess-12345] [192.168.1.100] [app-user-1] - Connection established - {"database":"postgresql","host":"db-server-1","port":5432}
[2026-01-24T21:25:23.120Z] [INFO] [AUTH] [sess-12345] [192.168.1.100] [app-user-1] - Authentication successful - {"method":"password","database":"mydb"}
[2026-01-24T21:25:24.567Z] [INFO] [QUERY] [sess-12345] [192.168.1.100] [app-user-1] - Query executed - {"sql":"SELECT * FROM users WHERE id = ?","params":["123"],"executionTimeMs":45,"rowCount":1}
[2026-01-24T21:25:30.890Z] [WARN] [AUTH] [sess-67890] [10.0.0.50] [unknown] - Authentication failed - {"reason":"invalid_credentials","attempts":3}
[2026-01-24T21:26:15.234Z] [INFO] [CONNECTION] [sess-12345] [192.168.1.100] [app-user-1] - Connection closed - {"durationSeconds":53,"queryCount":15}
```

### 3. Event Types to Log

#### CONNECTION Events (when `ojp.server.audit.log.connections=true`)
- **Connection Established**: Log when a new connection/session is created
  - Capture: session ID, client IP, target database, timestamp, connection parameters
- **Connection Closed**: Log when a connection/session is terminated
  - Capture: session ID, duration, number of queries executed, reason (normal/error/timeout)
- **Connection Error**: Log connection failures
  - Capture: error type, client IP, target database, error message

#### QUERY Events (when `ojp.server.audit.log.queries=true`)
- **Query Executed**: Log each SQL statement execution
  - Capture: session ID, SQL statement, parameters (sanitized), execution time, row count
  - **WARNING**: Add prominent logging warning that this has significant performance impact
  - Consider: Sampling mode (log 1 in N queries) or threshold-based logging (only log slow queries)
- **Query Error**: Log failed query executions
  - Capture: SQL statement, error message, error code

#### AUTH Events (when `ojp.server.audit.log.auth=true`)
- **Authentication Success**: Log successful authentication
  - Capture: user/principal, authentication method, client IP, timestamp
- **Authentication Failure**: Log failed authentication attempts
  - Capture: attempted user/principal, failure reason, client IP, timestamp, attempt count
- **Certificate Validation**: Log mTLS certificate validation (when mTLS is enabled)
  - Capture: certificate subject, issuer, validation result, client IP

### 4. Implementation Guidelines

#### 4.1 Code Structure
- Create a new package: `org.openjproxy.grpc.server.audit`
- Main classes:
  - `AuditLogger.java` - Core audit logging implementation
  - `AuditEvent.java` - Event model/enumeration
  - `AuditConfiguration.java` - Configuration holder for audit settings
  - `AuditLogFormatter.java` - Formats audit events consistently

#### 4.2 Integration Points
Integrate audit logging at these key points in the existing codebase:

1. **Session Management** (`SessionManagerImpl.java`)
   - Log connection established in `createSession()` or equivalent
   - Log connection closed in session cleanup/termination

2. **Statement Execution** (`StatementServiceImpl.java`)
   - Log query execution (when enabled)
   - Use existing query timing mechanisms if available

3. **Authentication** (IP whitelist validation, any auth mechanisms)
   - Log authentication events in `IpWhitelistValidator.java` or similar
   - If mTLS is implemented, add logging there

4. **gRPC Server** (`GrpcServer.java`)
   - Initialize audit logging system on server startup
   - Log audit system status (enabled/disabled, configuration)

#### 4.3 Performance Considerations
- **Asynchronous Logging**: Use async logging to minimize performance impact
  - Consider using a dedicated thread pool or async appender
  - Buffer audit events and batch writes
- **Query Logging Impact**: When query logging is enabled, warn users prominently:
  ```
  WARN - Audit query logging is ENABLED. This will significantly impact performance. Only use in non-production or for debugging.
  ```
- **Sampling**: Consider implementing sampling for high-volume events
  - Example: Log every Nth query instead of all queries
  - Configuration: `ojp.server.audit.log.queries.samplingRate=100` (log 1 in 100)

#### 4.4 File Management
- **Log Rotation**: Integrate with existing log rotation mechanisms (SLF4J/Logback)
- **Directory Creation**: Automatically create log directory if it doesn't exist
- **Permissions**: Log warnings if log file is not writable
- **Separate Log File**: Audit logs should be in a separate file from application logs
  - Application logs: General server operation (INFO, DEBUG, ERROR)
  - Audit logs: Security and compliance events (structured format)

#### 4.5 Security and Privacy
- **Sensitive Data**: 
  - **DO NOT** log passwords or sensitive authentication tokens
  - **DO** sanitize SQL parameters that may contain sensitive data (consider masking or hashing)
  - **DO** log only the metadata about authentication, not credentials
- **PII Considerations**:
  - SQL statements may contain personally identifiable information (PII)
  - Consider adding configuration to mask/redact PII from audit logs
  - Document GDPR/privacy implications of query logging

### 5. Testing Requirements

#### 5.1 Unit Tests
- Test configuration loading (all properties)
- Test audit log formatting
- Test event creation and metadata
- Test file path handling (relative/absolute paths)
- Test directory creation
- Test behavior when audit logging is disabled

#### 5.2 Integration Tests
- Test connection logging (create session, close session)
- Test query logging (execute statement, verify log entry)
- Test authentication logging
- Test log rotation (if implemented)
- Test performance impact (measure overhead with query logging enabled vs disabled)

#### 5.3 Manual Testing
- Run OJP server with audit logging enabled
- Execute database operations (connect, query, disconnect)
- Verify audit log contains expected entries
- Test with different configuration combinations
- Test log file creation in various directories

### 6. Documentation Requirements

#### 6.1 Configuration Documentation
Update `documents/configuration/ojp-server-configuration.md` with:
- Description of all audit logging properties
- Examples of different configurations
- Performance impact warnings
- Security and compliance benefits
- Log format specification

#### 6.2 Example Configuration
Update `documents/configuration/ojp-server-example.properties` with:
- Commented audit logging section
- Recommended production settings (queries disabled)
- Development/debugging settings (queries enabled with warning)

#### 6.3 Security Documentation
Update `documents/ebook/part3-chapter11-security.md` (if needed):
- Implementation notes
- Usage examples
- Best practices for production use

### 7. Default Values
```java
// Recommended defaults
DEFAULT_AUDIT_ENABLED = false  // Opt-in feature
DEFAULT_AUDIT_LOG_PATH = "logs/ojp-audit.log"
DEFAULT_AUDIT_LOG_CONNECTIONS = true  // When enabled, log connections
DEFAULT_AUDIT_LOG_QUERIES = false  // High impact, default off
DEFAULT_AUDIT_LOG_AUTH = true  // When enabled, log auth events
```

### 8. Compliance Mapping
The audit logging feature supports these compliance requirements:

**PCI-DSS:**
- Requirement 10.2: Implement automated audit trails
- Requirement 10.3: Record audit trail entries with specific elements
- Log connections, authentication, and access to cardholder data

**HIPAA:**
- § 164.312(b) Audit Controls: Implement hardware, software, and/or procedural mechanisms that record and examine activity in information systems containing PHI
- Log all access to systems containing PHI

**GDPR:**
- Article 5(2): Accountability and ability to demonstrate compliance
- Article 32: Security of processing
- Audit logs provide evidence of access controls and data processing activities

### 9. Future Enhancements (Out of Scope for Initial Implementation)
- Query parameter masking/redaction rules
- Log aggregation integration (Splunk, ELK, etc.)
- Real-time alerting based on audit events
- Audit log encryption at rest
- Log shipping to remote syslog server
- Audit event webhooks/callbacks
- Structured output formats (JSON Lines, CSV)
- Query sampling and threshold-based logging

## Success Criteria
1. ✅ All configuration properties are implemented and tested
2. ✅ Audit logs are written in the specified format
3. ✅ Connection, query, and auth events are logged correctly
4. ✅ Performance impact is minimal (except when query logging is enabled)
5. ✅ Documentation is updated and comprehensive
6. ✅ Tests pass and validate all functionality
7. ✅ Code compiles successfully (`mvn clean compile`)
8. ✅ Code follows existing OJP patterns and style

## Implementation Notes
- Follow the existing pattern used in `ServerConfiguration.java` for adding new configuration properties
- Use SLF4J for logging, following existing patterns in the codebase
- Integrate with existing session management in `SessionManager` and `SessionManagerImpl`
- Ensure backward compatibility - audit logging should be opt-in (disabled by default)
- Add JavaDoc comments to all new public classes and methods
- Follow the existing code style and patterns in the OJP codebase

## References
- Chapter 11 - Security, Audit Logging section (lines 1107-1124 in `documents/ebook/part3-chapter11-security.md`)
- Existing configuration: `ServerConfiguration.java`
- Session management: `SessionManagerImpl.java`
- Statement execution: `StatementServiceImpl.java`
- IP validation: `IpWhitelistValidator.java`
- Server startup: `GrpcServer.java`

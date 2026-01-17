# OpenTelemetry Distributed Tracing Integration Analysis

## Executive Summary

This document provides a comprehensive analysis of what is required to integrate OpenTelemetry distributed tracing into OJP (Open J Proxy), enabling integration with distributed tracing systems like Zipkin and Jaeger. Currently, OJP implements OpenTelemetry for metrics collection with Prometheus export, but lacks distributed tracing capabilities.

## Current State Assessment

### Existing OpenTelemetry Implementation

OJP currently has the following OpenTelemetry infrastructure in place:

1. **Dependencies** (in `ojp-server/pom.xml`):
   - `io.opentelemetry:opentelemetry-api:1.58.0` - Core OpenTelemetry API
   - `io.opentelemetry:opentelemetry-sdk:1.58.0` - OpenTelemetry SDK
   - `io.opentelemetry.instrumentation:opentelemetry-grpc-1.6:2.17.1-alpha` - gRPC instrumentation
   - `io.opentelemetry:opentelemetry-exporter-prometheus:1.52.0-alpha` - Prometheus metrics exporter

2. **Implementation** (`OjpServerTelemetry.java`):
   - Configured with Prometheus HTTP server for metrics export
   - Uses `SdkMeterProvider` for metrics collection
   - Provides gRPC server/client interceptors for automatic instrumentation
   - Supports IP whitelisting for metrics endpoint security

3. **Configuration** (`ServerConfiguration.java`):
   - `ojp.opentelemetry.enabled` - Enable/disable OpenTelemetry (default: `true`)
   - `ojp.prometheus.port` - Prometheus metrics port (default: `9159`)
   - `ojp.prometheus.allowedIps` - IP whitelist for metrics endpoint (default: allow all)
   - `ojp.opentelemetry.endpoint` - Placeholder for future exporter endpoint configuration

4. **Current Limitations**:
   - Only metrics collection is implemented (via `SdkMeterProvider`)
   - No trace provider (`SdkTracerProvider`) configured
   - No trace exporters (OTLP, Zipkin, Jaeger) available
   - No span creation or context propagation for distributed tracing

## Requirements for Distributed Tracing Integration

### 1. Additional Dependencies

To enable distributed tracing with Zipkin and Jaeger support, the following dependencies need to be added to `ojp-server/pom.xml`:

#### Core Tracing Dependencies

```xml
<!-- OpenTelemetry Tracing API (already included in opentelemetry-sdk) -->
<!-- Ensure version consistency with existing OpenTelemetry dependencies -->

<!-- OpenTelemetry Semantic Conventions for standardized attribute names -->
<dependency>
    <groupId>io.opentelemetry.semconv</groupId>
    <artifactId>opentelemetry-semconv</artifactId>
    <version>1.29.0-alpha</version>
</dependency>
```

#### Trace Exporters

```xml
<!-- OTLP Exporter - OpenTelemetry Protocol (works with Jaeger 1.35+) -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
    <version>1.58.0</version>
</dependency>

<!-- Zipkin Exporter - Direct Zipkin protocol support -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-zipkin</artifactId>
    <version>1.58.0</version>
</dependency>

<!-- Jaeger Exporter - Legacy Jaeger protocol (deprecated but still useful) -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-jaeger</artifactId>
    <version>1.58.0</version>
</dependency>

<!-- Logging Exporter - Useful for development and debugging -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-logging</artifactId>
    <version>1.58.0</version>
</dependency>
```

**Note on Exporter Selection:**
- **OTLP (Recommended)**: Modern, standardized protocol supported by Jaeger 1.35+, Grafana Tempo, and other backends
- **Zipkin**: Direct Zipkin protocol for native Zipkin deployments
- **Jaeger**: Legacy protocol, being phased out in favor of OTLP
- **Logging**: Development tool for debugging trace data

### 2. Architecture and Design

#### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    OJP Server                                │
│                                                              │
│  ┌────────────────────────────────────────────────────┐    │
│  │         OpenTelemetry SDK                          │    │
│  │                                                     │    │
│  │  ┌──────────────┐      ┌──────────────┐          │    │
│  │  │ MeterProvider│      │TracerProvider│          │    │
│  │  │   (Metrics)  │      │   (Traces)   │          │    │
│  │  └──────┬───────┘      └──────┬───────┘          │    │
│  │         │                     │                    │    │
│  │         │                     │                    │    │
│  │  ┌──────▼──────┐      ┌──────▼──────────┐        │    │
│  │  │ Prometheus  │      │  Trace Exporter │        │    │
│  │  │  Exporter   │      │  (OTLP/Zipkin/  │        │    │
│  │  └──────┬──────┘      │    Jaeger)      │        │    │
│  │         │             └──────┬──────────┘        │    │
│  └─────────┼────────────────────┼───────────────────┘    │
│            │                    │                          │
└────────────┼────────────────────┼──────────────────────────┘
             │                    │
             │                    │
             ▼                    ▼
      ┌─────────────┐      ┌──────────────┐
      │ Prometheus  │      │ Zipkin/Jaeger│
      │   Server    │      │   Backend    │
      └─────────────┘      └──────────────┘
```

#### Instrumentation Points

Distributed tracing should capture the following operations in OJP:

1. **gRPC Operations** (Already instrumented via `GrpcTelemetry`):
   - Incoming gRPC requests (server-side spans)
   - Outgoing gRPC calls (client-side spans)
   - Automatic context propagation via gRPC metadata

2. **Database Operations** (New instrumentation required):
   - Connection acquisition from pool
   - SQL statement execution
   - Transaction lifecycle (begin, commit, rollback)
   - Result set processing

3. **Connection Pool Operations** (New instrumentation required):
   - Pool initialization
   - Connection creation/closure
   - Connection validation
   - Pool health checks

4. **SQL Enhancer Operations** (If enabled):
   - SQL validation
   - Query optimization
   - Dialect translation
   - Schema operations

### 3. Configuration Options

#### New Configuration Properties

Add the following configuration properties to `ServerConfiguration.java`:

| Property | Environment Variable | Type | Default | Description |
|----------|---------------------|------|---------|-------------|
| `ojp.tracing.enabled` | `OJP_TRACING_ENABLED` | boolean | `true` | Enable/disable distributed tracing |
| `ojp.tracing.exporter.type` | `OJP_TRACING_EXPORTER_TYPE` | string | `otlp` | Exporter type: `otlp`, `zipkin`, `jaeger`, `logging`, `none` |
| `ojp.tracing.exporter.endpoint` | `OJP_TRACING_EXPORTER_ENDPOINT` | string | `http://localhost:4318/v1/traces` | OTLP/Zipkin/Jaeger endpoint URL |
| `ojp.tracing.sampling.ratio` | `OJP_TRACING_SAMPLING_RATIO` | double | `1.0` | Trace sampling ratio (0.0 to 1.0) |
| `ojp.tracing.service.name` | `OJP_TRACING_SERVICE_NAME` | string | `ojp-server` | Service name in traces |
| `ojp.tracing.resource.attributes` | `OJP_TRACING_RESOURCE_ATTRIBUTES` | string | `""` | Additional resource attributes (key=value,key=value) |
| `ojp.tracing.compression` | `OJP_TRACING_COMPRESSION` | string | `gzip` | Compression for OTLP: `none`, `gzip` |
| `ojp.tracing.timeout.seconds` | `OJP_TRACING_TIMEOUT_SECONDS` | int | `10` | Export timeout in seconds |
| `ojp.tracing.batch.maxQueueSize` | `OJP_TRACING_BATCH_MAX_QUEUE_SIZE` | int | `2048` | Max queue size for batch span processor |
| `ojp.tracing.batch.maxExportBatchSize` | `OJP_TRACING_BATCH_MAX_EXPORT_BATCH_SIZE` | int | `512` | Max batch size for export |
| `ojp.tracing.batch.scheduleDelayMillis` | `OJP_TRACING_BATCH_SCHEDULE_DELAY_MILLIS` | long | `5000` | Batch export delay in milliseconds |
| `ojp.tracing.db.statements` | `OJP_TRACING_DB_STATEMENTS` | boolean | `true` | Include SQL statements in traces (may contain sensitive data) |
| `ojp.tracing.db.parameters` | `OJP_TRACING_DB_PARAMETERS` | boolean | `false` | Include SQL parameters in traces (security risk) |

#### Default Exporter Endpoints

- **OTLP gRPC**: `http://localhost:4317` (Jaeger, Grafana Tempo, OpenTelemetry Collector)
- **OTLP HTTP**: `http://localhost:4318/v1/traces` (Default in configuration)
- **Zipkin**: `http://localhost:9411/api/v2/spans`
- **Jaeger (legacy)**: `http://localhost:14250` (gRPC) or `http://localhost:14268/api/traces` (HTTP)

### 4. Implementation Changes

#### 4.1 Update `OjpServerTelemetry.java`

The class needs to be enhanced to support trace provider configuration:

**Key Changes:**
1. Add `SdkTracerProvider` alongside the existing `SdkMeterProvider`
2. Configure trace exporters based on configuration
3. Support multiple exporter types (OTLP, Zipkin, Jaeger, Logging)
4. Configure span processors (batch processing for production)
5. Set up sampling strategies
6. Configure resource attributes (service name, version, etc.)

**Pseudo-code structure:**

```java
public class OjpServerTelemetry {
    
    // Create OpenTelemetry with both metrics and tracing
    public GrpcTelemetry createGrpcTelemetry(ServerConfiguration config) {
        
        // 1. Create Resource with service information
        Resource resource = Resource.getDefault()
            .merge(Resource.create(Attributes.of(
                ResourceAttributes.SERVICE_NAME, config.getTracingServiceName(),
                ResourceAttributes.SERVICE_VERSION, "0.3.2-snapshot"
            )));
        
        // 2. Create Metrics Provider (existing)
        PrometheusHttpServer prometheusServer = ...;
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(prometheusServer)
            .setResource(resource)
            .build();
        
        // 3. Create Trace Provider (new)
        SdkTracerProvider tracerProvider = null;
        if (config.isTracingEnabled()) {
            SpanExporter spanExporter = createSpanExporter(config);
            
            tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
                    .setMaxQueueSize(config.getTracingBatchMaxQueueSize())
                    .setMaxExportBatchSize(config.getTracingBatchMaxExportBatchSize())
                    .setScheduleDelay(Duration.ofMillis(config.getTracingBatchScheduleDelayMillis()))
                    .build())
                .setSampler(Sampler.traceIdRatioBased(config.getTracingSamplingRatio()))
                .setResource(resource)
                .build();
        }
        
        // 4. Build OpenTelemetry with both providers
        OpenTelemetrySdkBuilder builder = OpenTelemetrySdk.builder()
            .setMeterProvider(meterProvider);
        
        if (tracerProvider != null) {
            builder.setTracerProvider(tracerProvider);
        }
        
        OpenTelemetry openTelemetry = builder.build();
        
        // 5. Return GrpcTelemetry
        return GrpcTelemetry.create(openTelemetry);
    }
    
    private SpanExporter createSpanExporter(ServerConfiguration config) {
        String exporterType = config.getTracingExporterType();
        String endpoint = config.getTracingExporterEndpoint();
        
        switch (exporterType.toLowerCase()) {
            case "otlp":
                return OtlpGrpcSpanExporter.builder()
                    .setEndpoint(endpoint)
                    .setTimeout(Duration.ofSeconds(config.getTracingTimeoutSeconds()))
                    .setCompression(config.getTracingCompression())
                    .build();
                    
            case "zipkin":
                return ZipkinSpanExporter.builder()
                    .setEndpoint(endpoint)
                    .build();
                    
            case "jaeger":
                return JaegerGrpcSpanExporter.builder()
                    .setEndpoint(endpoint)
                    .setTimeout(Duration.ofSeconds(config.getTracingTimeoutSeconds()))
                    .build();
                    
            case "logging":
                return LoggingSpanExporter.create();
                
            case "none":
            default:
                // Return no-op exporter
                return SpanExporter.composite();
        }
    }
}
```

#### 4.2 Update `ServerConfiguration.java`

Add new configuration properties and getters for tracing configuration:

```java
// Add new configuration keys
private static final String TRACING_ENABLED_KEY = "ojp.tracing.enabled";
private static final String TRACING_EXPORTER_TYPE_KEY = "ojp.tracing.exporter.type";
private static final String TRACING_EXPORTER_ENDPOINT_KEY = "ojp.tracing.exporter.endpoint";
// ... (add all configuration keys from section 3)

// Add default values
public static final boolean DEFAULT_TRACING_ENABLED = true;
public static final String DEFAULT_TRACING_EXPORTER_TYPE = "otlp";
public static final String DEFAULT_TRACING_EXPORTER_ENDPOINT = "http://localhost:4318/v1/traces";
// ... (add all defaults)

// Add fields and initialize in constructor
private final boolean tracingEnabled;
private final String tracingExporterType;
// ...

// Add getters
public boolean isTracingEnabled() { return tracingEnabled; }
public String getTracingExporterType() { return tracingExporterType; }
// ...
```

#### 4.3 Add Database Instrumentation (Optional but Recommended)

Create a new class `OjpDatabaseInstrumentation.java` to wrap database operations with tracing:

```java
public class OjpDatabaseInstrumentation {
    
    private final Tracer tracer;
    private final boolean includeStatements;
    private final boolean includeParameters;
    
    public OjpDatabaseInstrumentation(OpenTelemetry openTelemetry, 
                                     boolean includeStatements,
                                     boolean includeParameters) {
        this.tracer = openTelemetry.getTracer("ojp-database", "0.3.2");
        this.includeStatements = includeStatements;
        this.includeParameters = includeParameters;
    }
    
    public <T> T executeWithTracing(String operationName, 
                                    Supplier<T> operation,
                                    String sql,
                                    String dbSystem,
                                    String dbName) {
        Span span = tracer.spanBuilder(operationName)
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(SemanticAttributes.DB_SYSTEM, dbSystem)
            .setAttribute(SemanticAttributes.DB_NAME, dbName)
            .setAttribute(SemanticAttributes.DB_OPERATION, parseOperationType(sql))
            .startSpan();
        
        if (includeStatements && sql != null) {
            span.setAttribute(SemanticAttributes.DB_STATEMENT, sql);
        }
        
        try (Scope scope = span.makeCurrent()) {
            T result = operation.get();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
    
    private String parseOperationType(String sql) {
        if (sql == null) return "unknown";
        String trimmed = sql.trim().toUpperCase();
        if (trimmed.startsWith("SELECT")) return "SELECT";
        if (trimmed.startsWith("INSERT")) return "INSERT";
        if (trimmed.startsWith("UPDATE")) return "UPDATE";
        if (trimmed.startsWith("DELETE")) return "DELETE";
        return "OTHER";
    }
}
```

#### 4.4 Update `StatementServiceImpl.java`

Integrate database instrumentation into statement execution:

```java
// Add field
private final OjpDatabaseInstrumentation dbInstrumentation;

// Inject in constructor
public StatementServiceImpl(SessionManager sessionManager, 
                           CircuitBreaker circuitBreaker,
                           ServerConfiguration config,
                           OjpDatabaseInstrumentation dbInstrumentation) {
    this.dbInstrumentation = dbInstrumentation;
    // ...
}

// Wrap database operations
public void executeQuery(ExecuteQueryRequest request, StreamObserver<ExecuteQueryResponse> responseObserver) {
    dbInstrumentation.executeWithTracing(
        "database.executeQuery",
        () -> {
            // Existing query execution logic
            return null;
        },
        request.getSql(),
        session.getDbSystem(),
        session.getDbName()
    );
}
```

#### 4.5 Update `GrpcServer.java`

Initialize tracing components:

```java
// Initialize database instrumentation if tracing enabled
OjpDatabaseInstrumentation dbInstrumentation = null;
if (config.isTracingEnabled()) {
    OpenTelemetry openTelemetry = grpcTelemetry.getOpenTelemetry();
    dbInstrumentation = new OjpDatabaseInstrumentation(
        openTelemetry,
        config.isTracingDbStatements(),
        config.isTracingDbParameters()
    );
}

// Pass to StatementServiceImpl
SessionManagerImpl sessionManager = new SessionManagerImpl();
ServerBuilder<?> serverBuilder = NettyServerBuilder
    .forPort(config.getServerPort())
    .addService(new StatementServiceImpl(
        sessionManager,
        circuitBreaker,
        config,
        dbInstrumentation
    ))
    // ...
```

### 5. Testing Strategy

#### 5.1 Unit Tests

Create `OjpTracingConfigurationTest.java`:

```java
@Test
void shouldCreateOtlpExporter() {
    ServerConfiguration config = createConfigWithExporter("otlp");
    OjpServerTelemetry telemetry = new OjpServerTelemetry();
    GrpcTelemetry grpcTelemetry = telemetry.createGrpcTelemetry(config);
    assertNotNull(grpcTelemetry);
}

@Test
void shouldCreateZipkinExporter() {
    ServerConfiguration config = createConfigWithExporter("zipkin");
    // ... similar test
}

@Test
void shouldDisableTracingWhenConfigured() {
    ServerConfiguration config = createConfigWithTracingDisabled();
    // ... verify no tracer provider created
}

@Test
void shouldApplySamplingRatio() {
    ServerConfiguration config = createConfigWithSampling(0.1);
    // ... verify only 10% of traces are sampled
}
```

#### 5.2 Integration Tests

Create `OjpTracingIntegrationTest.java`:

```java
@Test
void shouldExportTracesToZipkin() {
    // Start mock Zipkin server
    MockWebServer zipkinServer = new MockWebServer();
    zipkinServer.start();
    
    // Configure OJP with Zipkin exporter
    System.setProperty("ojp.tracing.exporter.type", "zipkin");
    System.setProperty("ojp.tracing.exporter.endpoint", 
        zipkinServer.url("/api/v2/spans").toString());
    
    // Start OJP server and execute query
    // ...
    
    // Verify Zipkin received trace
    RecordedRequest request = zipkinServer.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(request);
    assertTrue(request.getBody().readUtf8().contains("database.executeQuery"));
}

@Test
void shouldPropagateContextAcrossGrpcCalls() {
    // Test that trace context is propagated in gRPC metadata
    // ...
}
```

#### 5.3 Manual Testing

**Test with Jaeger (using Docker):**

```bash
# Start Jaeger all-in-one
docker run -d --name jaeger \
  -p 16686:16686 \
  -p 4317:4317 \
  -p 4318:4318 \
  jaegertracing/all-in-one:latest

# Start OJP with Jaeger
java -jar ojp-server-shaded.jar \
  -Dojp.tracing.enabled=true \
  -Dojp.tracing.exporter.type=otlp \
  -Dojp.tracing.exporter.endpoint=http://localhost:4317

# Access Jaeger UI
# Open http://localhost:16686
```

**Test with Zipkin (using Docker):**

```bash
# Start Zipkin
docker run -d --name zipkin \
  -p 9411:9411 \
  openzipkin/zipkin

# Start OJP with Zipkin
java -jar ojp-server-shaded.jar \
  -Dojp.tracing.enabled=true \
  -Dojp.tracing.exporter.type=zipkin \
  -Dojp.tracing.exporter.endpoint=http://localhost:9411/api/v2/spans

# Access Zipkin UI
# Open http://localhost:9411
```

### 6. Documentation Updates

#### 6.1 Update `documents/telemetry/README.md`

Remove the "Limitations" section about missing distributed tracing and add:

```markdown
## Distributed Tracing

OJP supports distributed tracing via OpenTelemetry with multiple backend options:

### Supported Backends

- **Jaeger** - Using OTLP protocol (recommended) or legacy Jaeger protocol
- **Zipkin** - Native Zipkin protocol support
- **Grafana Tempo** - Using OTLP protocol
- **OpenTelemetry Collector** - OTLP protocol for flexible routing
- **Any OTLP-compatible backend**

### Quick Start

#### With Jaeger (Docker)

```bash
# Start Jaeger
docker run -d --name jaeger \
  -p 16686:16686 -p 4317:4317 \
  jaegertracing/all-in-one:latest

# Configure OJP for Jaeger
export OJP_TRACING_ENABLED=true
export OJP_TRACING_EXPORTER_TYPE=otlp
export OJP_TRACING_EXPORTER_ENDPOINT=http://localhost:4317

# Start OJP
java -jar ojp-server-shaded.jar

# View traces at http://localhost:16686
```

#### With Zipkin (Docker)

```bash
# Start Zipkin
docker run -d --name zipkin -p 9411:9411 openzipkin/zipkin

# Configure OJP for Zipkin
export OJP_TRACING_ENABLED=true
export OJP_TRACING_EXPORTER_TYPE=zipkin
export OJP_TRACING_EXPORTER_ENDPOINT=http://localhost:9411/api/v2/spans

# Start OJP
java -jar ojp-server-shaded.jar

# View traces at http://localhost:9411
```

### Configuration

See [Configuration Options](#configuration-options) for all tracing properties.

### What Gets Traced

OJP creates spans for:
- gRPC requests (automatic via OpenTelemetry gRPC instrumentation)
- Database operations (connection acquisition, statement execution)
- SQL query processing
- Transaction lifecycle events

### Security Considerations

- SQL statements may contain sensitive data. Control inclusion via `ojp.tracing.db.statements`
- SQL parameters (prepared statement values) are NOT included by default
- Only enable `ojp.tracing.db.parameters` in non-production environments
- Use sampling to reduce overhead in high-traffic scenarios
```

#### 6.2 Create Configuration Guide

Add a section to `documents/configuration/ojp-server-configuration.md`:

```markdown
## Distributed Tracing Configuration

[Include the configuration table from section 3]

### Production Recommendations

1. **Sampling**: Use sampling ratio < 1.0 for high-traffic deployments
2. **Batch Processing**: Keep default batch settings unless experiencing memory issues
3. **Security**: Disable `ojp.tracing.db.parameters` in production
4. **Exporter**: Use OTLP for best compatibility and future-proofing
```

### 7. Performance Considerations

#### Impact Assessment

1. **CPU Overhead**:
   - Span creation: ~1-5 microseconds per span
   - Context propagation: Minimal (gRPC metadata handling)
   - Batch export: Asynchronous, non-blocking

2. **Memory Overhead**:
   - Span queue: Configurable (default 2048 spans)
   - Each span: ~1-2 KB depending on attributes
   - Total: ~2-4 MB for default queue size

3. **Network Overhead**:
   - Batch exports every 5 seconds by default
   - gzip compression reduces payload by ~70-80%
   - Typical: 10-50 KB per batch for moderate load

#### Optimization Strategies

1. **Sampling**: Reduce sampling ratio for high-traffic services
2. **Batch Tuning**: Increase batch size and delay for lower export frequency
3. **Attribute Selection**: Only include necessary attributes in spans
4. **SQL Statement Truncation**: Consider truncating long SQL statements

### 8. Deployment Considerations

#### Docker Integration

Update `Dockerfile.proprietary` and Jib configuration to support tracing environment variables:

```dockerfile
ENV OJP_TRACING_ENABLED=true
ENV OJP_TRACING_EXPORTER_TYPE=otlp
ENV OJP_TRACING_EXPORTER_ENDPOINT=http://localhost:4318/v1/traces
```

#### Kubernetes Deployment

Example ConfigMap for tracing:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: ojp-tracing-config
data:
  OJP_TRACING_ENABLED: "true"
  OJP_TRACING_EXPORTER_TYPE: "otlp"
  OJP_TRACING_EXPORTER_ENDPOINT: "http://jaeger-collector:4317"
  OJP_TRACING_SAMPLING_RATIO: "0.1"
  OJP_TRACING_SERVICE_NAME: "ojp-server"
```

#### Cloud Provider Integration

**AWS X-Ray**: Use OpenTelemetry Collector with X-Ray exporter
**Google Cloud Trace**: Use OTLP exporter to Cloud Trace endpoint
**Azure Monitor**: Use OpenTelemetry Collector with Azure exporter

### 9. Migration Path

For existing OJP deployments, tracing can be enabled incrementally:

**Phase 1: Enable Logging Exporter**
```properties
ojp.tracing.enabled=true
ojp.tracing.exporter.type=logging
```
- Validates tracing works without external dependencies
- Review log output to ensure spans are created correctly

**Phase 2: Enable Backend Export with Sampling**
```properties
ojp.tracing.exporter.type=otlp
ojp.tracing.sampling.ratio=0.01  # 1% sampling
```
- Gradually increase sampling as comfortable
- Monitor performance impact

**Phase 3: Full Production Deployment**
```properties
ojp.tracing.sampling.ratio=0.1  # 10% sampling (typical production value)
```

### 10. Best Practices

1. **Service Naming**: Use descriptive service names that include environment
   - Example: `ojp-server-production`, `ojp-server-staging`

2. **Resource Attributes**: Add deployment-specific attributes
   - `deployment.environment=production`
   - `service.version=0.3.2`
   - `service.instance.id=pod-xyz`

3. **Sampling Strategy**: 
   - Development: 100% sampling
   - Staging: 50% sampling
   - Production: 5-10% sampling

4. **SQL Statement Security**:
   - Always review SQL statements before enabling tracing
   - Consider implementing SQL sanitization for sensitive queries
   - Use query parameter placeholders instead of inline values

5. **Error Handling**:
   - All exceptions should be recorded in spans using `span.recordException()`
   - Set span status to ERROR on failures
   - Include error details in span attributes

6. **Context Propagation**:
   - gRPC automatically propagates trace context
   - For JDBC driver clients, context is maintained across the proxy boundary
   - Custom clients should properly propagate W3C Trace Context headers

### 11. Example: End-to-End Trace

A typical query execution trace would show:

```
ojp-server: grpc.server.execute_query (duration: 125ms)
├─ ojp-server: pool.acquire_connection (duration: 5ms)
├─ ojp-server: database.execute_query (duration: 110ms)
│  └─ [Remote database execution - not traced by OJP]
└─ ojp-server: result.process (duration: 10ms)
```

**Span Attributes:**
- `db.system`: postgresql
- `db.name`: myapp
- `db.operation`: SELECT
- `db.statement`: SELECT * FROM users WHERE id = ?
- `rpc.system`: grpc
- `rpc.service`: StatementService
- `rpc.method`: ExecuteQuery

### 12. Troubleshooting

#### Traces Not Appearing

1. Verify tracing is enabled: `ojp.tracing.enabled=true`
2. Check exporter endpoint is correct and accessible
3. Review logs for export errors
4. Verify sampling ratio is not too low
5. Check firewall rules for exporter endpoint

#### High Memory Usage

1. Reduce batch queue size: `ojp.tracing.batch.maxQueueSize=1024`
2. Reduce batch export size: `ojp.tracing.batch.maxExportBatchSize=256`
3. Increase export frequency: `ojp.tracing.batch.scheduleDelayMillis=2000`
4. Lower sampling ratio: `ojp.tracing.sampling.ratio=0.05`

#### Export Failures

1. Check endpoint connectivity: `curl http://localhost:4318/v1/traces`
2. Verify exporter type matches backend (OTLP vs Zipkin vs Jaeger)
3. Check backend logs for rejection reasons
4. Verify compression setting matches backend capabilities

### 13. Recommended Implementation Timeline

**Week 1-2: Foundation**
- Add dependencies to pom.xml
- Update `ServerConfiguration` with tracing properties
- Enhance `OjpServerTelemetry` with trace provider support
- Add unit tests

**Week 3: Core Instrumentation**
- Implement database instrumentation
- Integrate with `StatementServiceImpl`
- Add integration tests
- Document configuration

**Week 4: Testing & Documentation**
- Manual testing with Jaeger and Zipkin
- Performance benchmarking
- Update all documentation
- Create example configurations

**Week 5: Release**
- Code review and refinement
- Beta release with tracing disabled by default
- Gather feedback from early adopters

### 14. Future Enhancements

1. **Custom Instrumentation API**: Allow users to add custom spans for business logic
2. **Automatic SQL Sanitization**: Remove sensitive data from SQL statements
3. **Trace Sampling Strategies**: Dynamic sampling based on error rates or latency
4. **Span Links**: Link related spans across multiple traces
5. **Baggage Support**: Propagate custom metadata across service boundaries
6. **Metrics from Traces**: Generate metrics from trace data (e.g., error rates, latency percentiles)

## Conclusion

Integrating OpenTelemetry distributed tracing into OJP is a straightforward enhancement that builds upon the existing metrics infrastructure. The main requirements are:

1. **Dependencies**: Add 3-4 exporter libraries (~500KB total)
2. **Code Changes**: Minimal changes to existing classes, mostly configuration additions
3. **Configuration**: 12 new configuration properties with sensible defaults
4. **Testing**: Standard unit and integration tests
5. **Documentation**: Update telemetry guide and configuration docs

The implementation provides significant value for production deployments:
- End-to-end visibility into request flows
- Database query performance monitoring
- Error tracking and debugging
- Integration with popular observability platforms
- Zero-configuration defaults for common scenarios

With proper sampling and batch configuration, the performance impact is minimal (<1% CPU overhead, <5MB memory overhead), making it suitable for production use.

## References

- [OpenTelemetry Java Documentation](https://opentelemetry.io/docs/languages/java/)
- [OpenTelemetry Specification](https://opentelemetry.io/docs/specs/otel/)
- [Jaeger Documentation](https://www.jaegertracing.io/docs/)
- [Zipkin Documentation](https://zipkin.io/pages/documentation.html)
- [gRPC OpenTelemetry Instrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/instrumentation/grpc-1.6)
- [OpenTelemetry Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/)

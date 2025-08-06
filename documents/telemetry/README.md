# Telemetry Documentation

OJP (Open JDBC Proxy) provides observability features to monitor database operations through its telemetry system using OpenTelemetry.

## Currently Implemented Features

### Metrics via Prometheus Exporter ✅
OJP exposes operational metrics through a Prometheus-compatible endpoint, providing insights into:
- gRPC server metrics (request/response times, error rates)
- Connection and operation counters
- System health indicators

**Status**: Fully implemented and production-ready.

### OpenTelemetry Integration ✅
OJP integrates with OpenTelemetry for:
- gRPC method instrumentation
- Metrics collection and export
- Request/response monitoring

**Status**: Basic implementation complete with gRPC instrumentation.

## Planned Features (Not Yet Implemented)

### Distributed Tracing ⚠️
The following tracing features are planned but not yet implemented:
- SQL operation tracing across proxy and database layers
- Connection lifecycle tracing
- Request correlation across client-proxy-database
- Trace export to external systems

### Trace Exporters ❌
The following trace backends are not yet supported:
- Jaeger
- Zipkin  
- OTLP receivers
- Cloud providers (AWS X-Ray, Google Cloud Trace, Azure Monitor)

## Accessing Telemetry Data

### Prometheus Metrics
Metrics are exposed via HTTP endpoint and can be scraped by Prometheus:
- **Default endpoint**: `http://localhost:9090/metrics`
- **Format**: Prometheus text-based exposition format
- **Update frequency**: Real-time metrics updated on each operation

To access metrics:
1. Configure Prometheus to scrape the OJP server metrics endpoint
2. Set up Grafana dashboards to visualize the metrics
3. Create alerts based on connection pool health and performance thresholds

### OpenTelemetry Traces
Traces can be exported to various backends supported by OpenTelemetry:
- **Jaeger**: For distributed tracing visualization
- **Zipkin**: Alternative tracing backend
- **OTLP receivers**: Any OpenTelemetry Protocol compatible system
- **Cloud providers**: AWS X-Ray, Google Cloud Trace, Azure Monitor

## Configuration

### Current Configuration Options

The telemetry system can be configured through JVM system properties or environment variables:

```bash
# Enable/disable OpenTelemetry (default: true)
-Dojp.opentelemetry.enabled=true
# or
export OJP_OPENTELEMETRY_ENABLED=true

# Prometheus metrics port (default: 9090)
-Dojp.prometheus.port=9090
# or  
export OJP_PROMETHEUS_PORT=9090

# IP whitelist for Prometheus endpoint (default: allow all)
-Dojp.prometheus.allowedIps=127.0.0.1,10.0.0.0/8
# or
export OJP_PROMETHEUS_ALLOWED_IPS=127.0.0.1,10.0.0.0/8

# Server configuration
-Dojp.server.port=1059
-Dojp.server.threadPoolSize=200
```

### Example Docker Configuration

```yaml
version: '3.8'
services:
  ojp-server:
    image: rrobetti/ojp:0.0.8-alpha
    ports:
      - "1059:1059"    # gRPC server
      - "9090:9090"    # Prometheus metrics
    environment:
      - OJP_OPENTELEMETRY_ENABLED=true
      - OJP_PROMETHEUS_PORT=9090
      - OJP_PROMETHEUS_ALLOWED_IPS=0.0.0.0/0
```

### Future Configuration (Planned)

When tracing features are implemented, additional configuration will include:

```bash
# Trace exporter configuration (planned)
-Dojp.opentelemetry.traces.exporter=otlp  # otlp, jaeger, zipkin
-Dojp.opentelemetry.traces.endpoint=http://jaeger:14250
-Dojp.opentelemetry.traces.sampler.ratio=0.1
```

## Integration Examples

### Current Integration: Prometheus and Grafana

#### 1. Configure Prometheus to scrape OJP metrics

Create or update your `prometheus.yml`:

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'ojp-server'
    static_configs:
      - targets: ['localhost:9090']  # Adjust host/port as needed
    scrape_interval: 5s
    metrics_path: /metrics
```

#### 2. Start OJP Server with telemetry enabled

```bash
# Using Docker
docker run -p 1059:1059 -p 9090:9090 \
  -e OJP_OPENTELEMETRY_ENABLED=true \
  rrobetti/ojp:0.0.8-alpha

# Or using Java directly
java -Dojp.opentelemetry.enabled=true \
     -Dojp.prometheus.port=9090 \
     -jar ojp-server-0.0.8-alpha-shaded.jar
```

#### 3. Verify metrics are available

```bash
curl http://localhost:9090/metrics
```

You should see metrics like:
```
# HELP grpc_server_started_total Total number of RPCs started on the server.
# TYPE grpc_server_started_total counter
grpc_server_started_total{grpc_method="ExecuteStatement",grpc_service="StatementService",grpc_type="UNARY"} 0.0
```

#### 4. Create Grafana dashboard

Import or create a dashboard with queries like:
```promql
# Request rate
rate(grpc_server_started_total[5m])

# Error rate
rate(grpc_server_handled_total{grpc_code!="OK"}[5m])

# Response time
histogram_quantile(0.95, rate(grpc_server_handling_seconds_bucket[5m]))
```

### Future Integrations (When Tracing is Implemented)

#### With Jaeger (Planned)
```yaml
# docker-compose.yml
version: '3.8'
services:
  jaeger:
    image: jaegertracing/all-in-one:latest
    ports:
      - "16686:16686"
      - "14250:14250"
    environment:
      - COLLECTOR_OTLP_ENABLED=true
      
  ojp-server:
    image: rrobetti/ojp:latest
    depends_on:
      - jaeger
    environment:
      - OJP_OPENTELEMETRY_TRACES_EXPORTER=otlp
      - OJP_OPENTELEMETRY_TRACES_ENDPOINT=http://jaeger:14250
```

#### With Zipkin (Planned)
```bash
# Environment variables (when implemented)
export OJP_OPENTELEMETRY_TRACES_EXPORTER=zipkin
export OJP_OPENTELEMETRY_TRACES_ENDPOINT=http://zipkin:9411/api/v2/spans
```


## Current Limitations

- **No trace data**: Only metrics are collected; distributed tracing is not yet implemented
- **Limited metrics**: Currently only gRPC server metrics are available
- **No sampling configuration**: Trace sampling cannot be configured as tracing is not implemented
- **No custom spans**: Database operations are not traced as individual spans

## Best Practices

### For Current Implementation
- **Metrics endpoint security**: Ensure the Prometheus metrics endpoint is properly secured in production
  ```bash
  # Restrict to specific IPs
  -Dojp.prometheus.allowedIps=10.0.0.0/8,192.168.0.0/16
  ```
- **Monitoring**: Set up alerts for gRPC error rates and response times
- **Resource usage**: Monitor the performance impact of metrics collection

## Implementation Roadmap

### Phase 1: Basic Tracing (Next Steps)
To add basic distributed tracing support, the following changes would be needed:

1. **Add trace exporter dependencies**:
```xml
<!-- Add to pom.xml -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
    <version>1.52.0</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-jaeger</artifactId>
    <version>1.52.0</version>
</dependency>
```

2. **Enhance OjpServerTelemetry.java**:
```java
// Add trace processor configuration
SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
    .addSpanProcessor(BatchSpanProcessor.builder(
        OtlpGrpcSpanExporter.builder()
            .setEndpoint("http://jaeger:14250")
            .build())
        .build())
    .setResource(Resource.getDefault()
        .merge(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, "ojp-server"))))
    .build();

OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
    .setTracerProvider(tracerProvider)
    .setMeterProvider(/* existing meter provider */)
    .build();
```

3. **Add database span instrumentation**:
```java
// Example: Instrument JDBC operations
Tracer tracer = openTelemetry.getTracer("ojp-server");
Span span = tracer.spanBuilder("jdbc.query")
    .setAttribute("db.statement", sql)
    .setAttribute("db.operation", "query")
    .startSpan();
```

### Phase 2: Advanced Features
- Custom span attributes for SQL queries
- Connection pool tracing
- Performance sampling strategies
- Cloud provider integrations

## Contributing

If you're interested in implementing tracing features:

1. Check the [ADR-005](../ADRs/adr-005-use-opentelemetry.md) for the OpenTelemetry decision
2. Start with basic OTLP exporter implementation
3. Add configuration options for trace backends
4. Implement database operation spans
5. Add comprehensive tests

## References

- [OpenTelemetry Java Documentation](https://opentelemetry.io/docs/instrumentation/java/)
- [OpenTelemetry gRPC Instrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/instrumentation/grpc-1.6)
- [Prometheus Java Client](https://github.com/prometheus/client_java)
- [ADR-005: Use OpenTelemetry](../ADRs/adr-005-use-opentelemetry.md)

## Additional Resources

- [Configuration Examples](./telemetry-config-examples.properties) - Complete configuration examples for various deployment scenarios
- [Verification Script](./verify-telemetry.sh) - Script to verify your telemetry setup is working correctly

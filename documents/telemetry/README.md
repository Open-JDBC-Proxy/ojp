# Telemetry Documentation

OJP (Open J Proxy) provides observability features to monitor database operations through its telemetry system.

## Supported Telemetry Features

### Metrics via Prometheus Exporter
OJP exposes operational metrics through a Prometheus-compatible endpoint, providing insights into:
- gRPC communication metrics (request counts, latency, errors)
- Server operational metrics
- Connection and session information

### Distributed Tracing via OpenTelemetry
OJP supports distributed tracing using the OpenTelemetry SDK. Traces are automatically emitted for all gRPC calls handled by the server and can be sent to any compatible backend.

**Supported exporters:**
- **Zipkin** (default) — compatible with Zipkin and any Zipkin-compatible backend
- **OTLP** — compatible with Jaeger, Grafana Tempo, OpenTelemetry Collector, and cloud-native APM tools

Tracing is **disabled by default** and must be explicitly enabled via configuration.

## Accessing Telemetry Data

### Prometheus Metrics
Metrics are exposed via HTTP endpoint and can be scraped by Prometheus:
- **Default endpoint**: `http://localhost:9159/metrics`
- **Format**: Prometheus text-based exposition format
- **Update frequency**: Real-time metrics updated on each operation

To access metrics:
1. Configure Prometheus to scrape the OJP server metrics endpoint
2. Set up Grafana dashboards to visualize the metrics
3. Create alerts based on server performance and error thresholds

### Distributed Traces
Traces are pushed by OJP to the configured exporter endpoint:
- **Zipkin default endpoint**: `http://localhost:9411/api/v2/spans`
- **OTLP default endpoint**: `http://localhost:4317` (gRPC)

Each trace includes span attributes such as gRPC method, status code, and service name.

## Configuration Options

The telemetry system can be configured through JVM system properties or environment variables. JVM properties take precedence over environment variables.

### Metrics Configuration Properties

| Property | Environment Variable | Default | Description |
|----------|---------------------|---------|-------------|
| `ojp.telemetry.enabled` | `OJP_TELEMETRY_ENABLED` | `true` | Master switch: Enable/disable OpenTelemetry infrastructure (Prometheus server, MeterProvider, TracerProvider) |
| `ojp.prometheus.port` | `OJP_PROMETHEUS_PORT` | `9159` | Port for Prometheus metrics HTTP server |
| `ojp.prometheus.allowedIps` | `OJP_PROMETHEUS_ALLOWED_IPS` | `0.0.0.0/0` | Comma-separated list of allowed IP addresses/CIDR blocks for metrics endpoint |

### Tracing Configuration Properties

| Property | Environment Variable | Default | Description |
|----------|---------------------|---------|-------------|
| `ojp.tracing.enabled` | `OJP_TRACING_ENABLED` | `false` | Enable/disable distributed tracing (disabled by default) |
| `ojp.tracing.exporter` | `OJP_TRACING_EXPORTER` | `zipkin` | Trace exporter type: `zipkin` or `otlp` |
| `ojp.tracing.endpoint` | `OJP_TRACING_ENDPOINT` | `http://localhost:9411/api/v2/spans` | Exporter endpoint URL |
| `ojp.tracing.serviceName` | `OJP_TRACING_SERVICENAME` | `ojp-server` | Service name attached to all spans |
| `ojp.tracing.sampleRate` | `OJP_TRACING_SAMPLERATE` | `1.0` | Fraction of requests to sample (0.0–1.0) |

### Configuration Examples

**Enable Zipkin tracing (JVM properties):**
```bash
java -jar ojp-server.jar \
  -Dojp.tracing.enabled=true \
  -Dojp.tracing.endpoint=http://zipkin:9411/api/v2/spans \
  -Dojp.tracing.serviceName=my-ojp-server
```

**Enable OTLP tracing (e.g. Jaeger, Grafana Tempo):**
```bash
java -jar ojp-server.jar \
  -Dojp.tracing.enabled=true \
  -Dojp.tracing.exporter=otlp \
  -Dojp.tracing.endpoint=http://jaeger:4317 \
  -Dojp.tracing.sampleRate=0.1
```

**Using Environment Variables:**
```bash
export OJP_OPENTELEMETRY_ENABLED=true
export OJP_PROMETHEUS_PORT=9159
export OJP_PROMETHEUS_ALLOWED_IPS=127.0.0.1,10.0.0.0/8
export OJP_TRACING_ENABLED=true
export OJP_TRACING_ENDPOINT=http://zipkin:9411/api/v2/spans
java -jar ojp-server.jar
```

## Integration Examples

### With Prometheus and Grafana
1. Configure Prometheus to scrape OJP metrics:

```yaml
# prometheus.yml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'ojp-server'
    static_configs:
      - targets: ['localhost:9159']
    metrics_path: '/metrics'
    scrape_interval: 5s
```

2. Import OJP metrics into Grafana by adding Prometheus as a data source
3. Create dashboards to visualize gRPC call metrics, error rates, and server performance
4. Set up alerts for server errors and performance degradation

### With Zipkin

Start a local Zipkin instance:
```bash
docker run -d -p 9411:9411 openzipkin/zipkin
```

Start OJP with tracing enabled:
```bash
java -jar ojp-server.jar \
  -Dojp.tracing.enabled=true \
  -Dojp.tracing.endpoint=http://localhost:9411/api/v2/spans
```

Open `http://localhost:9411` in your browser to view traces.

### With Jaeger via OTLP

Start a local Jaeger instance:
```bash
docker run -d \
  -p 16686:16686 \
  -p 4317:4317 \
  jaegertracing/all-in-one:latest
```

Start OJP with OTLP tracing enabled:
```bash
java -jar ojp-server.jar \
  -Dojp.tracing.enabled=true \
  -Dojp.tracing.exporter=otlp \
  -Dojp.tracing.endpoint=http://localhost:4317
```

Open `http://localhost:16686` in your browser to view traces in Jaeger.

### Docker Compose Example (OJP + Zipkin)

```yaml
version: '3.8'
services:
  ojp-server:
    image: rrobetti/ojp:latest
    ports:
      - "1059:1059"
      - "9159:9159"
    environment:
      OJP_TRACING_ENABLED: "true"
      OJP_TRACING_ENDPOINT: "http://zipkin:9411/api/v2/spans"
      OJP_TRACING_SERVICE_NAME: "ojp-server"

  zipkin:
    image: openzipkin/zipkin
    ports:
      - "9411:9411"
```

## Sampling

The `ojp.tracing.sampleRate` property controls what fraction of requests are traced:

| Value | Behaviour |
|-------|-----------|
| `1.0` | 100% of requests are traced (default when tracing is enabled) |
| `0.5` | 50% of requests are traced |
| `0.1` | 10% of requests are traced |
| `0.0` | No requests are traced (effectively disables tracing) |

For high-throughput production environments, a sample rate of `0.01` to `0.1` is recommended to reduce overhead.

## Best Practices

- **Default off**: Tracing is disabled by default to avoid any overhead until explicitly needed
- **Sampling**: In high-throughput production environments, set `ojp.tracing.sampleRate` to a value less than `1.0` (e.g. `0.1`) to reduce overhead
- **Security**: Ensure telemetry endpoints are properly secured in production environments using the IP whitelist feature
- **Performance**: Monitor the performance impact of telemetry collection on the proxy
- **Monitoring**: Set up alerts for server errors and unusual traffic patterns

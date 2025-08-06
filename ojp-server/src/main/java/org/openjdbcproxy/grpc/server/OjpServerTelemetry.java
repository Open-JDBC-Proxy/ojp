package org.openjdbcproxy.grpc.server;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * OJP Server Telemetry Configuration for OpenTelemetry with Prometheus Exporter.
 * This class provides methods to create a GrpcTelemetry instance with Prometheus metrics.
 */
public class OjpServerTelemetry {
	private static final Logger logger = LoggerFactory.getLogger(OjpServerTelemetry.class);
	private static final int DEFAULT_PROMETHEUS_PORT = 9090;

	/**
	 * Creates GrpcTelemetry with default configuration.
	 */
	public GrpcTelemetry createGrpcTelemetry() {
		return createGrpcTelemetry(DEFAULT_PROMETHEUS_PORT, List.of(IpWhitelistValidator.ALLOW_ALL_IPS));
	}

	/**
	 * Creates GrpcTelemetry with specified Prometheus port.
	 */
	public GrpcTelemetry createGrpcTelemetry(int prometheusPort) {
		return createGrpcTelemetry(prometheusPort, List.of(IpWhitelistValidator.ALLOW_ALL_IPS));
	}

	/**
	 * Creates GrpcTelemetry with specified Prometheus port and IP whitelist.
	 */
	public GrpcTelemetry createGrpcTelemetry(int prometheusPort, List<String> allowedIps) {
		return createGrpcTelemetry(prometheusPort, allowedIps, null);
	}

	/**
	 * Creates GrpcTelemetry with specified Prometheus port, IP whitelist, and optional OTLP endpoint.
	 * Note: OTLP tracing is not yet fully implemented but configuration is prepared for future use.
	 */
	public GrpcTelemetry createGrpcTelemetry(int prometheusPort, List<String> allowedIps, String otlpEndpoint) {
		logger.info("Initializing OpenTelemetry with Prometheus on port {} with IP whitelist: {}", 
					prometheusPort, allowedIps);

		// Validate IP whitelist
		if (!IpWhitelistValidator.validateWhitelistRules(allowedIps)) {
			logger.warn("Invalid IP whitelist rules detected, falling back to allow all");
			allowedIps = List.of(IpWhitelistValidator.ALLOW_ALL_IPS);
		}

		// Configure Prometheus metrics exporter
		PrometheusHttpServer prometheusServer = PrometheusHttpServer.builder()
				.setPort(prometheusPort)
				.build();

		// Configure basic resource information
		Resource resource = Resource.getDefault()
				.merge(Resource.create(Attributes.of(
					AttributeKey.stringKey("service.name"), "ojp-server",
					AttributeKey.stringKey("service.version"), "0.0.8-alpha")));

		// For now, only metrics are fully supported. Tracing configuration is prepared for future implementation.
		if (otlpEndpoint != null && !otlpEndpoint.trim().isEmpty()) {
			logger.warn("OTLP endpoint configured ({}) but distributed tracing is not yet fully implemented. Only metrics will be available.", otlpEndpoint);
		}

		// Build OpenTelemetry with metrics support
		OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
				.setMeterProvider(
						SdkMeterProvider.builder()
								.registerMetricReader(prometheusServer)
								.setResource(resource)
								.build())
				.build();

		return GrpcTelemetry.create(openTelemetry);
	}

	/**
	 * Creates a no-op GrpcTelemetry when OpenTelemetry is disabled.
	 */
	public GrpcTelemetry createNoOpGrpcTelemetry() {
		logger.info("OpenTelemetry disabled, using no-op implementation");
		return GrpcTelemetry.create(OpenTelemetry.noop());
	}
}

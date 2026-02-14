package org.openjproxy.grpc.server;

import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OjpServerTelemetryTest {
	private static final int PROMETHEUS_PORT = 9191;
	private static final int TIMEOUT = 5000;
	private static final int RESPONSE_CODE_OK = 200;

	private static GrpcTelemetry grpcTelemetry;

	@BeforeAll
	static void setUp() {
		OjpServerTelemetry instrument = new OjpServerTelemetry();
		grpcTelemetry = instrument.createGrpcTelemetry(PROMETHEUS_PORT);
	}

	@Test
	void shouldCreateGrpcTelemetrySuccessfully() {
		assertNotNull(grpcTelemetry);
		assertNotNull(grpcTelemetry.newServerInterceptor());
		assertNotNull(grpcTelemetry.newClientInterceptor());
	}

	@Test
	void shouldExposePrometheusMetricsEndpoint() throws IOException {
		HttpURLConnection connection = (HttpURLConnection) URI.create("http://localhost:9191/metrics").toURL().openConnection();
		connection.setRequestMethod("GET");
		connection.setConnectTimeout(TIMEOUT);
		connection.setReadTimeout(TIMEOUT);

		assertEquals(RESPONSE_CODE_OK, connection.getResponseCode());
		assertEquals("text/plain; version=0.0.4; charset=utf-8", connection.getContentType());
	}

}

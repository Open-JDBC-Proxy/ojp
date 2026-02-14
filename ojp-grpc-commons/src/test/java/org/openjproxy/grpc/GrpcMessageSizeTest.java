package org.openjproxy.grpc;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;

import static org.junit.jupiter.api.Assertions.*;

public class GrpcMessageSizeTest {

	static final int PORT = 5555; // Just a random port
	static final int SIZE_LIMIT = 16 * 1024 * 1024; // Since 16MB is the default
	static final int MAGIC_100 = 100;
	static final int MAGIC_10 = 10;
	static Server server;

	@BeforeAll
	static void startServer() throws Exception {
		server = ServerBuilder.forPort(PORT)
				.addService(new DummyEchoService())
				.maxInboundMessageSize(SIZE_LIMIT)
				.build()
				.start();
	}

	@AfterAll
	static void stopServer() throws Exception {
		server.shutdownNow();
	}

	EchoServiceGrpc.EchoServiceBlockingStub getStub(int inboundSize) {
		ManagedChannel channel = GrpcChannelFactory.createChannel("localhost", PORT, inboundSize);
		return EchoServiceGrpc.newBlockingStub(channel);
	}

	String generateMessageOfSize(int sizeInBytes) {
		return "a".repeat(sizeInBytes);
	}

	// This should pass
	@Test
	void testMessageBelowSizeLimit() {
		String message = generateMessageOfSize(SIZE_LIMIT - MAGIC_100); // Just under 16MB
		EchoServiceGrpc.EchoServiceBlockingStub stub = getStub(SIZE_LIMIT);
		EchoResponse response = stub.echo(EchoRequest.newBuilder().setMessage(message).build());
		assertEquals(message, response.getMessage());
	}
	
	// This should pass
	@Test
	void testMessageAtSizeLimit() {
		String message = generateMessageOfSize(SIZE_LIMIT - MAGIC_10); // Exactly 16MB
		EchoServiceGrpc.EchoServiceBlockingStub stub = getStub(SIZE_LIMIT);
		EchoResponse response = stub.echo(EchoRequest.newBuilder().setMessage(message).build());
		assertEquals(message, response.getMessage());
	}

	// This should fail
	@Test
	void testMessageExceedsSizeLimit() {
		String message = generateMessageOfSize(SIZE_LIMIT + 1); // Just over 16MB
		EchoServiceGrpc.EchoServiceBlockingStub stub = getStub(SIZE_LIMIT);

		Exception exception = assertThrows(StatusRuntimeException.class, () -> {
			stub.echo(EchoRequest.newBuilder().setMessage(message).build());
		});

		System.out.println("Expected exception: " + exception.getMessage());
		assertTrue(exception.getMessage().contains("RESOURCE_EXHAUSTED"));
	}
}

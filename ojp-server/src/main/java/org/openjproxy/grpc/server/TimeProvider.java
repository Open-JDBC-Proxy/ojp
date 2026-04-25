package org.openjproxy.grpc.server;

/**
 * Abstraction for time operations to enable testability.
 * This interface allows us to mock time in unit tests.
 */
public interface TimeProvider {

    /**
     * Returns the current time in seconds since epoch.
     * @return current time in seconds
     */
    long currentTimeSeconds();

    /**
     * Default implementation that uses system time.
     */
    TimeProvider SYSTEM = () -> System.currentTimeMillis() / 1000L;
}

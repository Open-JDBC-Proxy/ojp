package org.openjproxy.grpc.server;

import java.util.concurrent.ConcurrentHashMap;

public class CircuitBreakerRegistry {

    private ConcurrentHashMap<String, CircuitBreaker> circuitBreakerStore = new ConcurrentHashMap<>();
    private final long openMs;
    private final int failureThreshold;

    public CircuitBreakerRegistry(long openMs, int failureThreshold){
        this.openMs = openMs;
        this.failureThreshold = failureThreshold;
    }


    public CircuitBreaker get(String key){
        return circuitBreakerStore.computeIfAbsent(key, k -> new CircuitBreaker(openMs, failureThreshold, key));
    }
}

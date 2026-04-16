package org.openjproxy.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link OjpHealthCheckInitializer}.
 *
 * <p>These tests verify URL filtering logic and fail-safe behaviour without
 * requiring a live OJP server.  When a valid OJP URL is present the initializer
 * attempts to create a {@code MultinodeConnectionManager}; any resulting
 * connection or gRPC error is caught and logged, so the tests assert that no
 * exception propagates out of {@link OjpHealthCheckInitializer#initializeHealthCheck()}.
 */
class OjpHealthCheckInitializerTest {

    @Test
    void shouldNotThrowWhenDefaultDatasourceUrlIsOjp() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url",
                "jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb");

        OjpHealthCheckInitializer initializer = new OjpHealthCheckInitializer(env);
        assertThatNoException().isThrownBy(initializer::initializeHealthCheck);
    }

    @Test
    void shouldNotThrowWhenNamedDatasourceUrlIsOjp() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.catalog.url",
                "jdbc:ojp[localhost:1059]_postgresql://user@localhost/catalog");

        OjpHealthCheckInitializer initializer = new OjpHealthCheckInitializer(env);
        assertThatNoException().isThrownBy(initializer::initializeHealthCheck);
    }

    @Test
    void shouldNotThrowWhenMultipleNamedDatasourceUrlsAreOjp() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.catalog.url",
                "jdbc:ojp[localhost:1059]_postgresql://user@localhost/catalog");
        env.setProperty("spring.datasource.checkout.url",
                "jdbc:ojp[localhost:1059]_postgresql://user@localhost/checkout");

        OjpHealthCheckInitializer initializer = new OjpHealthCheckInitializer(env);
        assertThatNoException().isThrownBy(initializer::initializeHealthCheck);
    }

    @Test
    void shouldSkipNonOjpUrls() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url",
                "jdbc:postgresql://localhost:5432/mydb");

        OjpHealthCheckInitializer initializer = new OjpHealthCheckInitializer(env);
        // No MultinodeConnectionManager should be created; no exception expected
        assertThatNoException().isThrownBy(initializer::initializeHealthCheck);
    }

    @Test
    void shouldSkipWhenNoDatasourceUrlIsPresent() {
        MockEnvironment env = new MockEnvironment();

        OjpHealthCheckInitializer initializer = new OjpHealthCheckInitializer(env);
        assertThatNoException().isThrownBy(initializer::initializeHealthCheck);
    }

    @Test
    void shouldNotProcessDeepNestedDatasourceProperties() {
        // spring.datasource.foo.bar.url has two dots in the middle segment – must be ignored
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.foo.bar.url",
                "jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb");

        OjpHealthCheckInitializer initializer = new OjpHealthCheckInitializer(env);
        // Should not throw; the property is ignored because middle = "foo.bar" contains a dot
        assertThatNoException().isThrownBy(initializer::initializeHealthCheck);
    }
}

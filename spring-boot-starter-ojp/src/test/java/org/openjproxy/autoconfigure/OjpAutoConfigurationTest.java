package org.openjproxy.autoconfigure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OjpAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OjpAutoConfiguration.class));

    @BeforeEach
    @AfterEach
    void clearOjpSystemProperties() {
        System.clearProperty("ojp.connection.pool.maximumPoolSize");
        System.clearProperty("ojp.connection.pool.minimumIdle");
        System.clearProperty("ojp.connection.pool.connectionTimeout");
        System.clearProperty("ojp.connection.pool.idleTimeout");
        System.clearProperty("ojp.connection.pool.maxLifetime");
        System.clearProperty("ojp.grpc.maxInboundMessageSize");
    }

    @Test
    void shouldRegisterBridgeBeanWhenOjpUrlIsConfigured() {
        contextRunner
                .withPropertyValues("spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb")
                .run(context -> assertThat(context).hasSingleBean(OjpSystemPropertiesBridge.class));
    }

    @Test
    void shouldRegisterBridgeBeanWhenNamedDatasourceOjpUrlIsConfigured() {
        contextRunner
                .withPropertyValues("spring.datasource.catalog.url=jdbc:ojp[localhost:1059]_postgresql://user@localhost/catalog")
                .run(context -> assertThat(context).hasSingleBean(OjpSystemPropertiesBridge.class));
    }

    @Test
    void shouldRegisterBridgeBeanWhenMultipleNamedDatasourceOjpUrlsAreConfigured() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.catalog.url=jdbc:ojp[localhost:1059]_postgresql://user@localhost/catalog",
                        "spring.datasource.checkout.url=jdbc:ojp[localhost:1059]_postgresql://user@localhost/checkout"
                )
                .run(context -> assertThat(context).hasSingleBean(OjpSystemPropertiesBridge.class));
    }

    @Test
    void shouldNotActivateWhenNoDatasourceUrlIsConfigured() {
        contextRunner
                .run(context -> assertThat(context).doesNotHaveBean(OjpSystemPropertiesBridge.class));
    }

    @Test
    void shouldNotActivateWhenNamedDatasourceUrlIsNotOjp() {
        contextRunner
                .withPropertyValues("spring.datasource.catalog.url=jdbc:postgresql://localhost:5432/catalog")
                .run(context -> assertThat(context).doesNotHaveBean(OjpSystemPropertiesBridge.class));
    }

    @Test
    void shouldForwardPoolPropertiesToSystemProperties() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb",
                        "ojp.connection.pool.maximum-pool-size=30",
                        "ojp.connection.pool.minimum-idle=5",
                        "ojp.connection.pool.connection-timeout=15000",
                        "ojp.connection.pool.idle-timeout=300000",
                        "ojp.connection.pool.max-lifetime=900000"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(OjpSystemPropertiesBridge.class);
                    assertThat(System.getProperty("ojp.connection.pool.maximumPoolSize")).isEqualTo("30");
                    assertThat(System.getProperty("ojp.connection.pool.minimumIdle")).isEqualTo("5");
                    assertThat(System.getProperty("ojp.connection.pool.connectionTimeout")).isEqualTo("15000");
                    assertThat(System.getProperty("ojp.connection.pool.idleTimeout")).isEqualTo("300000");
                    assertThat(System.getProperty("ojp.connection.pool.maxLifetime")).isEqualTo("900000");
                });
    }

    @Test
    void shouldForwardGrpcPropertiesToSystemProperties() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb",
                        "ojp.grpc.max-inbound-message-size=33554432"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(OjpSystemPropertiesBridge.class);
                    assertThat(System.getProperty("ojp.grpc.maxInboundMessageSize")).isEqualTo("33554432");
                });
    }
}

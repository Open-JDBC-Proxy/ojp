package org.openjproxy.autoconfigure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OjpEnvironmentPostProcessorTest {

    private final OjpEnvironmentPostProcessor processor = new OjpEnvironmentPostProcessor();
    private final SpringApplication application = new SpringApplication();

    private static final String[] HEALTH_CHECK_SYSTEM_PROPS = {
            "ojp.health.check.interval",
            "ojp.health.check.threshold",
            "ojp.health.check.timeout",
            "ojp.redistribution.enabled",
            "ojp.redistribution.idleRebalanceFraction",
            "ojp.redistribution.maxClosePerRecovery",
            "ojp.loadaware.selection.enabled",
            "ojp.multinode.retryAttempts",
            "ojp.multinode.retryDelayMs",
    };

    @BeforeEach
    @AfterEach
    void clearHealthCheckSystemProperties() {
        for (String prop : HEALTH_CHECK_SYSTEM_PROPS) {
            System.clearProperty(prop);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb",
            "jdbc:ojp[localhost:1059(mainApp)]_postgresql://user@localhost/mydb",
            "jdbc:ojp[host1:1059,host2:1059]_postgresql://user@localhost/mydb"
    })
    void shouldSetDefaultsWhenOjpUrlIsPresent(String ojpUrl) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", ojpUrl);

        processor.postProcessEnvironment(environment, application);

        assertEquals(OjpEnvironmentPostProcessor.OJP_DRIVER_CLASS,
                environment.getProperty(OjpEnvironmentPostProcessor.DRIVER_CLASS_NAME));
        assertEquals(OjpEnvironmentPostProcessor.SIMPLE_DRIVER_DATASOURCE,
                environment.getProperty(OjpEnvironmentPostProcessor.DATASOURCE_TYPE));
    }

    @Test
    void shouldNotOverrideExplicitDriverClassName() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", "jdbc:ojp[localhost:1059]_h2:mem:testdb");
        environment.setProperty(OjpEnvironmentPostProcessor.DRIVER_CLASS_NAME, "com.custom.Driver");

        processor.postProcessEnvironment(environment, application);

        // Driver class name should remain as user-specified value
        assertEquals("com.custom.Driver",
                environment.getProperty(OjpEnvironmentPostProcessor.DRIVER_CLASS_NAME));
    }

    @Test
    void shouldNotOverrideExplicitDatasourceType() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", "jdbc:ojp[localhost:1059]_h2:mem:testdb");
        environment.setProperty(OjpEnvironmentPostProcessor.DATASOURCE_TYPE, "com.custom.DataSource");

        processor.postProcessEnvironment(environment, application);

        // Datasource type should remain as user-specified value
        assertEquals("com.custom.DataSource",
                environment.getProperty(OjpEnvironmentPostProcessor.DATASOURCE_TYPE));
    }

    @Test
    void shouldDoNothingWhenUrlIsNotOjp() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/mydb");

        processor.postProcessEnvironment(environment, application);

        assertNull(environment.getProperty(OjpEnvironmentPostProcessor.DRIVER_CLASS_NAME));
        assertNull(environment.getProperty(OjpEnvironmentPostProcessor.DATASOURCE_TYPE));
    }

    @Test
    void shouldDoNothingWhenUrlIsAbsent() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, application);

        assertNull(environment.getProperty(OjpEnvironmentPostProcessor.DRIVER_CLASS_NAME));
        assertNull(environment.getProperty(OjpEnvironmentPostProcessor.DATASOURCE_TYPE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"catalog", "checkout", "primary"})
    void shouldSetDefaultsForNamedDatasourceOjpUrl(String dsName) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource." + dsName + ".url",
                "jdbc:ojp[localhost:1059]_postgresql://user@localhost/" + dsName);

        processor.postProcessEnvironment(environment, application);

        assertEquals(OjpEnvironmentPostProcessor.OJP_DRIVER_CLASS,
                environment.getProperty("spring.datasource." + dsName + ".driver-class-name"));
        assertEquals(OjpEnvironmentPostProcessor.SIMPLE_DRIVER_DATASOURCE,
                environment.getProperty("spring.datasource." + dsName + ".type"));
        // Default datasource properties should NOT be set
        assertNull(environment.getProperty(OjpEnvironmentPostProcessor.DRIVER_CLASS_NAME));
        assertNull(environment.getProperty(OjpEnvironmentPostProcessor.DATASOURCE_TYPE));
    }

    @Test
    void shouldSetDefaultsForMultipleNamedDatasourceOjpUrls() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.catalog.url",
                "jdbc:ojp[localhost:1059]_postgresql://user@localhost/catalog");
        environment.setProperty("spring.datasource.checkout.url",
                "jdbc:ojp[localhost:1059]_postgresql://user@localhost/checkout");

        processor.postProcessEnvironment(environment, application);

        assertEquals(OjpEnvironmentPostProcessor.OJP_DRIVER_CLASS,
                environment.getProperty("spring.datasource.catalog.driver-class-name"));
        assertEquals(OjpEnvironmentPostProcessor.SIMPLE_DRIVER_DATASOURCE,
                environment.getProperty("spring.datasource.catalog.type"));
        assertEquals(OjpEnvironmentPostProcessor.OJP_DRIVER_CLASS,
                environment.getProperty("spring.datasource.checkout.driver-class-name"));
        assertEquals(OjpEnvironmentPostProcessor.SIMPLE_DRIVER_DATASOURCE,
                environment.getProperty("spring.datasource.checkout.type"));
    }

    @Test
    void shouldNotSetDefaultsForNamedDatasourceWhenUrlIsNotOjp() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.catalog.url",
                "jdbc:postgresql://localhost:5432/catalog");

        processor.postProcessEnvironment(environment, application);

        assertNull(environment.getProperty("spring.datasource.catalog.driver-class-name"));
        assertNull(environment.getProperty("spring.datasource.catalog.type"));
    }

    @Test
    void shouldNotOverrideExplicitDriverClassNameForNamedDatasource() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.catalog.url",
                "jdbc:ojp[localhost:1059]_postgresql://user@localhost/catalog");
        environment.setProperty("spring.datasource.catalog.driver-class-name", "com.custom.Driver");

        processor.postProcessEnvironment(environment, application);

        assertEquals("com.custom.Driver",
                environment.getProperty("spring.datasource.catalog.driver-class-name"));
        assertEquals(OjpEnvironmentPostProcessor.SIMPLE_DRIVER_DATASOURCE,
                environment.getProperty("spring.datasource.catalog.type"));
    }

    // ---- health-check / multinode system property bridging ------------------

    @Test
    void shouldBridgeHealthCheckPropertiesToSystemPropertiesDuringEnvironmentPreparation() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("ojp.health.check.interval", "10s");
        environment.setProperty("ojp.health.check.threshold", "5s");
        environment.setProperty("ojp.health.check.timeout", "2s");

        processor.postProcessEnvironment(environment, application);

        assertEquals("10s", System.getProperty("ojp.health.check.interval"));
        assertEquals("5s",  System.getProperty("ojp.health.check.threshold"));
        assertEquals("2s",  System.getProperty("ojp.health.check.timeout"));
    }

    @Test
    void shouldBridgeRedistributionAndLoadAwarePropertiesToSystemProperties() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("ojp.redistribution.enabled", "false");
        environment.setProperty("ojp.redistribution.idleRebalanceFraction", "0.5");
        environment.setProperty("ojp.redistribution.maxClosePerRecovery", "10");
        environment.setProperty("ojp.loadaware.selection.enabled", "false");

        processor.postProcessEnvironment(environment, application);

        assertEquals("false", System.getProperty("ojp.redistribution.enabled"));
        assertEquals("0.5",   System.getProperty("ojp.redistribution.idleRebalanceFraction"));
        assertEquals("10",    System.getProperty("ojp.redistribution.maxClosePerRecovery"));
        assertEquals("false", System.getProperty("ojp.loadaware.selection.enabled"));
    }

    @Test
    void shouldBridgeMultinodeRetryPropertiesToSystemProperties() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("ojp.multinode.retryAttempts", "5");
        environment.setProperty("ojp.multinode.retryDelayMs", "3000");

        processor.postProcessEnvironment(environment, application);

        assertEquals("5",    System.getProperty("ojp.multinode.retryAttempts"));
        assertEquals("3000", System.getProperty("ojp.multinode.retryDelayMs"));
    }

    @Test
    void shouldNotOverrideExistingSystemPropertyWhenBridgingHealthCheckProperties() {
        System.setProperty("ojp.health.check.interval", "30s");
        try {
            MockEnvironment environment = new MockEnvironment();
            environment.setProperty("ojp.health.check.interval", "10s");

            processor.postProcessEnvironment(environment, application);

            // Pre-existing system property must be preserved
            assertEquals("30s", System.getProperty("ojp.health.check.interval"));
        } finally {
            System.clearProperty("ojp.health.check.interval");
        }
    }

    @Test
    void shouldBridgeHealthCheckKebabCasePropertiesToCamelCaseSystemProperties() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("ojp.redistribution.idle-rebalance-fraction", "0.75");

        processor.postProcessEnvironment(environment, application);

        assertEquals("0.75", System.getProperty("ojp.redistribution.idleRebalanceFraction"));
    }
}

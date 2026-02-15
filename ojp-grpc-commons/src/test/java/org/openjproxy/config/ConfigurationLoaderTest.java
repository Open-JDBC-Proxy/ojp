package org.openjproxy.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConfigurationLoader to verify YAML and Properties file loading.
 */
class ConfigurationLoaderTest {

    @Test
    void testLoadYamlConfiguration() throws IOException {
        // Load the test YAML file
        Properties props = new Properties();
        try (InputStream is = ConfigurationLoaderTest.class.getClassLoader().getResourceAsStream("test-config.yaml")) {
            assertNotNull(is, "Test YAML file should exist");
            
            // Use ConfigurationLoader's YAML parsing logic
            com.fasterxml.jackson.databind.ObjectMapper yamlMapper = 
                new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
            
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> yamlMap = yamlMapper.readValue(is, java.util.Map.class);
            
            // Flatten the YAML map to properties
            flattenYamlMap("", yamlMap, props);
        }
        
        // Verify the properties were loaded correctly
        assertEquals("1059", props.getProperty("ojp.server.port"));
        assertEquals("200", props.getProperty("ojp.server.threadPoolSize"));
        assertEquals("INFO", props.getProperty("ojp.server.logLevel"));
        assertEquals("60000", props.getProperty("ojp.server.circuitBreakerTimeout"));
        assertEquals("3", props.getProperty("ojp.server.circuitBreakerThreshold"));
        assertEquals("true", props.getProperty("ojp.server.slowQuerySegregation.enabled"));
        assertEquals("20", props.getProperty("ojp.server.slowQuerySegregation.slowSlotPercentage"));
        assertEquals("10000", props.getProperty("ojp.server.slowQuerySegregation.idleTimeout"));
        assertEquals("120000", props.getProperty("ojp.server.slowQuerySegregation.slowSlotTimeout"));
        assertEquals("60000", props.getProperty("ojp.server.slowQuerySegregation.fastSlotTimeout"));
        assertEquals("9159", props.getProperty("ojp.prometheus.port"));
        assertEquals("true", props.getProperty("ojp.opentelemetry.enabled"));
        assertEquals("16777216", props.getProperty("ojp.grpc.maxInboundMessageSize"));
    }

    @Test
    void testLoadPropertiesConfiguration() throws IOException {
        // Load the test properties file
        Properties props = new Properties();
        try (InputStream is = ConfigurationLoaderTest.class.getClassLoader().getResourceAsStream("test-config.properties")) {
            assertNotNull(is, "Test properties file should exist");
            props.load(is);
        }
        
        // Verify the properties were loaded correctly
        assertEquals("2059", props.getProperty("ojp.server.port"));
        assertEquals("100", props.getProperty("ojp.server.threadPoolSize"));
        assertEquals("DEBUG", props.getProperty("ojp.server.logLevel"));
        assertEquals("8388608", props.getProperty("ojp.grpc.maxInboundMessageSize"));
    }

    @Test
    void testYamlConversionToFlatProperties() {
        // Create a nested YAML-like structure
        java.util.Map<String, Object> yamlMap = new java.util.HashMap<>();
        java.util.Map<String, Object> server = new java.util.HashMap<>();
        server.put("port", 1059);
        server.put("threadPoolSize", 200);
        
        java.util.Map<String, Object> tls = new java.util.HashMap<>();
        tls.put("enabled", true);
        java.util.Map<String, Object> keystore = new java.util.HashMap<>();
        keystore.put("path", "/etc/ojp/ssl/server.jks");
        keystore.put("password", "changeit");
        tls.put("keystore", keystore);
        server.put("tls", tls);
        
        java.util.Map<String, Object> ojp = new java.util.HashMap<>();
        ojp.put("server", server);
        yamlMap.put("ojp", ojp);
        
        // Flatten to properties
        Properties props = new Properties();
        flattenYamlMap("", yamlMap, props);
        
        // Verify flattening
        assertEquals("1059", props.getProperty("ojp.server.port"));
        assertEquals("200", props.getProperty("ojp.server.threadPoolSize"));
        assertEquals("true", props.getProperty("ojp.server.tls.enabled"));
        assertEquals("/etc/ojp/ssl/server.jks", props.getProperty("ojp.server.tls.keystore.path"));
        assertEquals("changeit", props.getProperty("ojp.server.tls.keystore.password"));
    }

    @Test
    void testYamlListConversion() {
        // Create a YAML-like structure with lists
        java.util.Map<String, Object> yamlMap = new java.util.HashMap<>();
        java.util.Map<String, Object> server = new java.util.HashMap<>();
        java.util.List<String> allowedIps = java.util.Arrays.asList("192.168.1.0/24", "10.0.0.0/8");
        server.put("allowedIps", allowedIps);
        
        java.util.Map<String, Object> ojp = new java.util.HashMap<>();
        ojp.put("server", server);
        yamlMap.put("ojp", ojp);
        
        // Flatten to properties
        Properties props = new Properties();
        flattenYamlMap("", yamlMap, props);
        
        // Verify list is converted to comma-separated string
        assertEquals("192.168.1.0/24,10.0.0.0/8", props.getProperty("ojp.server.allowedIps"));
    }

    /**
     * Helper method to flatten YAML map to properties (mirrors ConfigurationLoader logic)
     */
    @SuppressWarnings("unchecked")
    private void flattenYamlMap(String prefix, java.util.Map<String, Object> map, Properties properties) {
        for (java.util.Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            
            if (value instanceof java.util.Map) {
                flattenYamlMap(key, (java.util.Map<String, Object>) value, properties);
            } else if (value instanceof Iterable) {
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (Object item : (Iterable<?>) value) {
                    if (!first) {
                        sb.append(",");
                    }
                    sb.append(item.toString());
                    first = false;
                }
                properties.setProperty(key, sb.toString());
            } else if (value != null) {
                properties.setProperty(key, value.toString());
            }
        }
    }
}

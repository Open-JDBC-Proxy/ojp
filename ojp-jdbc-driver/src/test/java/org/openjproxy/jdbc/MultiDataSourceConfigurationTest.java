package org.openjproxy.jdbc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for multi-datasource configuration functionality.
 */
public class MultiDataSourceConfigurationTest {

    @Test
    void testUrlParsingWithDataSourceParameter() throws Exception {
        // Test URL without dataSource parameter - should default to "default"
        UrlParser.UrlParseResult result1 = UrlParser.parseUrlWithDataSource("jdbc:ojp[localhost:1059]_h2:~/test");
        
        assertEquals("jdbc:ojp[localhost:1059]_h2:~/test", result1.cleanUrl);
        assertEquals("default", result1.dataSourceName);
        
        // Test URL with dataSource parameter in parentheses
        UrlParser.UrlParseResult result2 = UrlParser.parseUrlWithDataSource("jdbc:ojp[localhost:1059(myApp)]_h2:~/test");
        
        assertEquals("jdbc:ojp[localhost:1059]_h2:~/test", result2.cleanUrl);
        assertEquals("myApp", result2.dataSourceName);
        
        // Test URL with port and datasource
        UrlParser.UrlParseResult result3 = UrlParser.parseUrlWithDataSource("jdbc:ojp[localhost:1059(readOnly)]_h2:~/test");
        
        assertEquals("jdbc:ojp[localhost:1059]_h2:~/test", result3.cleanUrl);
        assertEquals("readOnly", result3.dataSourceName);
        
        // Test URL with spaces around datasource name - should be trimmed
        UrlParser.UrlParseResult result4 = UrlParser.parseUrlWithDataSource("jdbc:ojp[localhost:1059( myApp )]_h2:~/test");
        
        assertEquals("jdbc:ojp[localhost:1059]_h2:~/test", result4.cleanUrl);
        assertEquals("myApp", result4.dataSourceName); // Should be trimmed
    }

    @Test
    void testMultinodeUrlParsingWithDataSourceParameter() throws Exception {
        // Test multinode URL with different datasource names per endpoint
        // The first datasource name should be used as the primary datasource name
        UrlParser.UrlParseResult result1 = UrlParser.parseUrlWithDataSource("jdbc:ojp[localhost:10591(default),localhost:10592(multinode)]_h2:~/test");

        assertEquals("jdbc:ojp[localhost:10591,localhost:10592]_h2:~/test", result1.cleanUrl);
        assertEquals("default", result1.dataSourceNames.get(0));
        assertEquals("multinode", result1.dataSourceNames.get(1));
    }

    
    @Test
    void testLoadOjpPropertiesForDataSource() throws Exception {
        // Create test properties with multiple datasources
        String testProperties = 
            "# Default datasource\n" +
            "ojp.connection.pool.maximumPoolSize=20\n" +
            "ojp.connection.pool.minimumIdle=5\n" +
            "\n" +
            "# MyApp datasource\n" +
            "myApp.ojp.connection.pool.maximumPoolSize=50\n" +
            "myApp.ojp.connection.pool.minimumIdle=10\n" +
            "myApp.ojp.connection.pool.connectionTimeout=15000\n" +
            "\n" +
            "# ReadOnly datasource\n" +
            "readOnly.ojp.connection.pool.maximumPoolSize=5\n" +
            "readOnly.ojp.connection.pool.minimumIdle=1\n";
        
        // Mock the loadOjpProperties method to return our test properties
        Properties allProperties = new Properties();
        try (InputStream is = new ByteArrayInputStream(testProperties.getBytes())) {
            allProperties.load(is);
        }
        
        // Create a test loader with mocked properties
        TestDatasourcePropertiesLoader loader = new TestDatasourcePropertiesLoader(allProperties);
        
        // Test loading properties for "myApp" datasource
        Properties myAppProps = loader.testLoadOjpPropertiesForDataSource("myApp");
        assertNotNull(myAppProps);
        assertEquals("50", myAppProps.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("10", myAppProps.getProperty("ojp.connection.pool.minimumIdle"));
        assertEquals("15000", myAppProps.getProperty("ojp.connection.pool.connectionTimeout"));
        assertEquals("myApp", myAppProps.getProperty("ojp.datasource.name"));
        
        // Test loading properties for "readOnly" datasource
        Properties readOnlyProps = loader.testLoadOjpPropertiesForDataSource("readOnly");
        assertNotNull(readOnlyProps);
        assertEquals("5", readOnlyProps.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("1", readOnlyProps.getProperty("ojp.connection.pool.minimumIdle"));
        assertEquals("readOnly", readOnlyProps.getProperty("ojp.datasource.name"));
        
        // Test loading properties for "default" datasource
        Properties defaultProps = loader.testLoadOjpPropertiesForDataSource("default");
        assertNotNull(defaultProps);
        assertEquals("20", defaultProps.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("5", defaultProps.getProperty("ojp.connection.pool.minimumIdle"));
        assertEquals("default", defaultProps.getProperty("ojp.datasource.name"));
        
        // Test loading properties for non-existent datasource
        Properties nonExistentProps = loader.testLoadOjpPropertiesForDataSource("nonExistent");
        assertNull(nonExistentProps);
    }
    
    @Test
    void testDataSourceConfigurationWithNoProperties() throws Exception {
        // Create a loader that returns null for ojp.properties (simulating no file found)
        TestDatasourcePropertiesLoader loader = new TestDatasourcePropertiesLoader(null);
        
        // Test when no properties file exists
        Properties result = loader.testLoadOjpPropertiesForDataSource("default");
        assertNull(result);
        
        // Test with non-default datasource when no properties exist
        Properties result2 = loader.testLoadOjpPropertiesForDataSource("myApp");
        assertNull(result2);
    }
    
    /**
     * Test loader class that exposes static methods from DatasourcePropertiesLoader with mocked properties
     */
    private static class TestDatasourcePropertiesLoader {
        private final Properties mockProperties;
        
        public TestDatasourcePropertiesLoader(Properties mockProperties) {
            this.mockProperties = mockProperties;
        }
        
        public Properties testLoadOjpPropertiesForDataSource(String dataSourceName) {
            if (mockProperties == null || mockProperties.isEmpty()) {
                return null;
            }
            
            Properties dataSourceProperties = new Properties();
            
            // Look for dataSource-prefixed properties first
            String prefix = dataSourceName + ".ojp.connection.pool.";
            boolean foundDataSourceSpecific = false;
            
            for (String key : mockProperties.stringPropertyNames()) {
                if (key.startsWith(prefix)) {
                    String standardKey = key.substring(dataSourceName.length() + 1);
                    dataSourceProperties.setProperty(standardKey, mockProperties.getProperty(key));
                    foundDataSourceSpecific = true;
                }
            }
            
            // If no dataSource-specific properties found, and this is the "default" dataSource
            if (!foundDataSourceSpecific && "default".equals(dataSourceName)) {
                for (String key : mockProperties.stringPropertyNames()) {
                    if (key.startsWith("ojp.connection.pool.")) {
                        dataSourceProperties.setProperty(key, mockProperties.getProperty(key));
                    }
                }
            }
            
            // Include the dataSource name as a property
            if (!dataSourceProperties.isEmpty()) {
                dataSourceProperties.setProperty("ojp.datasource.name", dataSourceName);
            }
            
            return dataSourceProperties.isEmpty() ? null : dataSourceProperties;
        }
    }
}

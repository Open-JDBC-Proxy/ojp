package org.openjproxy.grpc.server.pool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for transaction isolation configuration parsing in DataSourceConfigurationManager.
 * These tests verify that the configuration properly parses string names and rejects numeric values.
 */
class TransactionIsolationConfigurationTest {

    @AfterEach
    void tearDown() {
        DataSourceConfigurationManager.clearCache();
    }

    @Test
    @DisplayName("Should parse READ_COMMITTED string name for non-XA")
    void testParseReadCommittedNonXA() {
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.defaultTransactionIsolation", "READ_COMMITTED");
        
        DataSourceConfigurationManager.DataSourceConfiguration config = 
            DataSourceConfigurationManager.getConfiguration(props);
        
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, config.getDefaultTransactionIsolation());
    }

    @Test
    @DisplayName("Should parse SERIALIZABLE string name for XA")
    void testParseSerializableXA() {
        Properties props = new Properties();
        props.setProperty("ojp.xa.connection.pool.defaultTransactionIsolation", "SERIALIZABLE");
        
        DataSourceConfigurationManager.XADataSourceConfiguration config = 
            DataSourceConfigurationManager.getXAConfiguration(props);
        
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, config.getDefaultTransactionIsolation());
    }

    @Test
    @DisplayName("Should parse TRANSACTION_READ_COMMITTED constant name")
    void testParseTransactionConstantName() {
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.defaultTransactionIsolation", "TRANSACTION_READ_COMMITTED");
        
        DataSourceConfigurationManager.DataSourceConfiguration config = 
            DataSourceConfigurationManager.getConfiguration(props);
        
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, config.getDefaultTransactionIsolation());
    }

    @Test
    @DisplayName("Should be case-insensitive")
    void testCaseInsensitive() {
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.defaultTransactionIsolation", "read_committed");
        
        DataSourceConfigurationManager.DataSourceConfiguration config = 
            DataSourceConfigurationManager.getConfiguration(props);
        
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, config.getDefaultTransactionIsolation());
    }

    @Test
    @DisplayName("Should NOT accept numeric values")
    void testNumericValuesNotAccepted() {
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.defaultTransactionIsolation", "2");
        
        DataSourceConfigurationManager.DataSourceConfiguration config = 
            DataSourceConfigurationManager.getConfiguration(props);
        
        // Numeric values should be rejected and return null (auto-detect)
        assertNull(config.getDefaultTransactionIsolation(), 
                "Numeric values should not be accepted - should fall back to null");
    }

    @Test
    @DisplayName("Should return null for invalid values")
    void testInvalidValueReturnsNull() {
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.defaultTransactionIsolation", "INVALID_LEVEL");
        
        DataSourceConfigurationManager.DataSourceConfiguration config = 
            DataSourceConfigurationManager.getConfiguration(props);
        
        assertNull(config.getDefaultTransactionIsolation(), 
                "Invalid values should return null for auto-detection");
    }

    @Test
    @DisplayName("Should return null when property not set")
    void testPropertyNotSet() {
        Properties props = new Properties();
        // Don't set the property
        
        DataSourceConfigurationManager.DataSourceConfiguration config = 
            DataSourceConfigurationManager.getConfiguration(props);
        
        assertNull(config.getDefaultTransactionIsolation(), 
                "When property not set, should return null for auto-detection");
    }

    @Test
    @DisplayName("Should parse all valid isolation levels")
    void testAllValidIsolationLevels() {
        String[] validNames = {
            "NONE", "TRANSACTION_NONE",
            "READ_UNCOMMITTED", "TRANSACTION_READ_UNCOMMITTED",
            "READ_COMMITTED", "TRANSACTION_READ_COMMITTED",
            "REPEATABLE_READ", "TRANSACTION_REPEATABLE_READ",
            "SERIALIZABLE", "TRANSACTION_SERIALIZABLE"
        };
        
        int[] expectedValues = {
            Connection.TRANSACTION_NONE, Connection.TRANSACTION_NONE,
            Connection.TRANSACTION_READ_UNCOMMITTED, Connection.TRANSACTION_READ_UNCOMMITTED,
            Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_READ_COMMITTED,
            Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_REPEATABLE_READ,
            Connection.TRANSACTION_SERIALIZABLE, Connection.TRANSACTION_SERIALIZABLE
        };
        
        for (int i = 0; i < validNames.length; i++) {
            Properties props = new Properties();
            props.setProperty("ojp.connection.pool.defaultTransactionIsolation", validNames[i]);
            
            DataSourceConfigurationManager.DataSourceConfiguration config = 
                DataSourceConfigurationManager.getConfiguration(props);
            
            assertEquals(expectedValues[i], config.getDefaultTransactionIsolation(), 
                    "Failed to parse: " + validNames[i]);
            
            // Clear cache for next iteration
            DataSourceConfigurationManager.clearCache();
        }
    }

    @Test
    @DisplayName("Should handle whitespace in configuration values")
    void testWhitespaceHandling() {
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.defaultTransactionIsolation", "  READ_COMMITTED  ");
        
        DataSourceConfigurationManager.DataSourceConfiguration config = 
            DataSourceConfigurationManager.getConfiguration(props);
        
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, config.getDefaultTransactionIsolation(),
                "Should trim whitespace from configuration values");
    }

    @Test
    @DisplayName("Should handle empty string as null")
    void testEmptyStringAsNull() {
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.defaultTransactionIsolation", "");
        
        DataSourceConfigurationManager.DataSourceConfiguration config = 
            DataSourceConfigurationManager.getConfiguration(props);
        
        assertNull(config.getDefaultTransactionIsolation(), 
                "Empty string should be treated as null");
    }

    @Test
    @DisplayName("XA configuration should work independently")
    void testXAConfiguration() {
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.defaultTransactionIsolation", "READ_COMMITTED");
        props.setProperty("ojp.xa.connection.pool.defaultTransactionIsolation", "SERIALIZABLE");
        
        DataSourceConfigurationManager.DataSourceConfiguration nonXAConfig = 
            DataSourceConfigurationManager.getConfiguration(props);
        DataSourceConfigurationManager.XADataSourceConfiguration xaConfig = 
            DataSourceConfigurationManager.getXAConfiguration(props);
        
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, nonXAConfig.getDefaultTransactionIsolation());
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, xaConfig.getDefaultTransactionIsolation());
    }
}

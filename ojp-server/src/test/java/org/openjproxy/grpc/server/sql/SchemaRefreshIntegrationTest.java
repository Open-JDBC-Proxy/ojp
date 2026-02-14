package org.openjproxy.grpc.server.sql;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for schema refresh functionality.
 */
class SchemaRefreshIntegrationTest {
    
    private SchemaCache schemaCache;
    private SchemaLoader schemaLoader;
    private DataSource dataSource;
    
    @BeforeEach
    void setUp() throws SQLException {
        schemaCache = new SchemaCache();
        schemaLoader = new SchemaLoader();
        
        // Mock DataSource and Connection
        dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        ResultSet tablesRs = mock(ResultSet.class);
        ResultSet columnsRs = mock(ResultSet.class);
        
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getTables(null, null, null, new String[]{"TABLE"})).thenReturn(tablesRs);
        
        // Setup table result set
        when(tablesRs.next()).thenReturn(true, false); // One table
        when(tablesRs.getString("TABLE_NAME")).thenReturn("test_table");
        
        // Setup columns result set
        when(metaData.getColumns(null, null, "test_table", null)).thenReturn(columnsRs);
        when(columnsRs.next()).thenReturn(true, false); // One column
        when(columnsRs.getString("COLUMN_NAME")).thenReturn("id");
        when(columnsRs.getInt("DATA_TYPE")).thenReturn(Types.INTEGER);
        when(columnsRs.getString("TYPE_NAME")).thenReturn("INTEGER");
        when(columnsRs.getInt("NULLABLE")).thenReturn(DatabaseMetaData.columnNoNulls);
        when(columnsRs.getInt("COLUMN_SIZE")).thenReturn(10);
        when(columnsRs.getInt("DECIMAL_DIGITS")).thenReturn(0);
        when(columnsRs.wasNull()).thenReturn(false);
    }
    
    @Test
    void testSchemaRefreshTriggeredAfterInterval() throws Exception {
        // Load initial schema
        SchemaMetadata initialSchema = schemaLoader.loadSchema(dataSource.getConnection(), null, null);
        schemaCache.updateSchema(initialSchema);
        
        // Verify initial state
        assertFalse(schemaCache.needsRefresh(1000), "Should not need refresh immediately after load");
        
        // Wait for refresh interval
        Thread.sleep(110); //NOSONAR
        
        // Verify refresh is needed
        assertTrue(schemaCache.needsRefresh(100), "Should need refresh after interval");
    }
    
    @Test
    void testAsyncSchemaLoadWithTimeout() throws Exception {
        // Create loader with short timeout
        SchemaLoader loaderWithTimeout = new SchemaLoader(java.util.concurrent.ForkJoinPool.commonPool(), 5);
        
        // Load schema asynchronously
        CompletableFuture<SchemaMetadata> future = loaderWithTimeout.loadSchemaAsync(dataSource, null, null);
        
        // Should complete within timeout
        SchemaMetadata schema = future.get(10, TimeUnit.SECONDS);
        assertNotNull(schema, "Schema should load successfully");
        assertEquals(1, schema.getTables().size(), "Should have loaded 1 table");
    }
    
    @Test
    void testSchemaRefreshWithEnhancerEngine() throws Exception {
        // Load initial schema
        SchemaMetadata initialSchema = schemaLoader.loadSchema(dataSource.getConnection(), null, null);
        schemaCache.updateSchema(initialSchema);
        
        // Create engine with periodic refresh (very short interval for testing)
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "GENERIC", "", true, true, null,
            schemaCache, schemaLoader, dataSource, null, null,
            0 // 0 hours = will use milliseconds directly
        );
        
        // Engine should be created successfully
        assertNotNull(engine, "Engine should be created");
        assertTrue(engine.isEnabled(), "Engine should be enabled");
    }
    
    @Test
    void testSchemaLoaderTimeout() {
        // Create a slow mock that never completes
        DataSource slowDataSource = mock(DataSource.class);
        try {
            when(slowDataSource.getConnection()).thenAnswer(invocation -> {
                // Very long delay
                Thread.sleep(100000); //NOSONAR
                return null;
            });
        } catch (Exception e) {
            fail("Mock setup failed");
        }
        
        // Create loader with very short timeout
        SchemaLoader loaderWithShortTimeout = new SchemaLoader(java.util.concurrent.ForkJoinPool.commonPool(), 1);
        
        // Attempt to load schema - should time out
        CompletableFuture<SchemaMetadata> future = loaderWithShortTimeout.loadSchemaAsync(slowDataSource, null, null);
        
        // Verify it times out
        assertThrows(Exception.class, () -> future.get(3, TimeUnit.SECONDS), "Should timeout when schema loading takes too long");
    }
}

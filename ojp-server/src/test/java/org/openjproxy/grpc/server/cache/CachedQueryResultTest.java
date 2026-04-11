package org.openjproxy.grpc.server.cache;

import com.openjproxy.grpc.OpQueryResultProto;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.ResultRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CachedQueryResult that now stores proto objects directly.
 */
class CachedQueryResultTest {

    private OpQueryResultProto createTestProto(int rowCount, int columnCount) {
        OpQueryResultProto.Builder builder = OpQueryResultProto.newBuilder();
        builder.setResultSetUUID("test-uuid");
        
        // Add column labels
        for (int i = 0; i < columnCount; i++) {
            builder.addLabels("col" + i);
        }
        
        // Add rows
        for (int r = 0; r < rowCount; r++) {
            ResultRow.Builder rowBuilder = ResultRow.newBuilder();
            for (int c = 0; c < columnCount; c++) {
                rowBuilder.addColumns(ParameterValue.newBuilder()
                        .setStringValue("value" + r + "_" + c)
                        .build());
            }
            builder.addRows(rowBuilder.build());
        }
        
        return builder.build();
    }

    @Test
    void testConstructorWithValidParameters() {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(300);
        OpQueryResultProto proto = createTestProto(2, 3);
        
        CachedQueryResult result = new CachedQueryResult(
            proto,
            now,
            expiresAt,
            Set.of("users")
        );
        
        assertEquals(2, result.getRowCount());
        assertEquals(3, result.getColumnCount());
        assertEquals(now, result.getCachedAt());
        assertEquals(expiresAt, result.getExpiresAt());
        assertEquals(Set.of("users"), result.getAffectedTables());
        assertSame(proto, result.getQueryResultProto());
    }

    @Test
    void testConstructorWithNullProto() {
        assertThrows(NullPointerException.class, () ->
            new CachedQueryResult(null, Instant.now(), Instant.now().plusSeconds(60), Set.of())
        );
    }

    @Test
    void testConstructorWithNullCachedAt() {
        OpQueryResultProto proto = createTestProto(1, 1);
        assertThrows(NullPointerException.class, () ->
            new CachedQueryResult(proto, null, Instant.now(), Set.of())
        );
    }

    @Test
    void testConstructorWithNullExpiresAt() {
        OpQueryResultProto proto = createTestProto(1, 1);
        assertThrows(NullPointerException.class, () ->
            new CachedQueryResult(proto, Instant.now(), null, Set.of())
        );
    }

    @Test
    void testConstructorWithNullAffectedTables() {
        Instant now = Instant.now();
        OpQueryResultProto proto = createTestProto(1, 1);
        
        CachedQueryResult result = new CachedQueryResult(proto, now, now.plusSeconds(60), null);
        
        assertNotNull(result.getAffectedTables());
        assertTrue(result.getAffectedTables().isEmpty());
    }

    @Test
    void testConstructorWithEmptyAffectedTables() {
        Instant now = Instant.now();
        OpQueryResultProto proto = createTestProto(1, 1);
        
        CachedQueryResult result = new CachedQueryResult(proto, now, now.plusSeconds(60), Set.of());
        
        assertNotNull(result.getAffectedTables());
        assertTrue(result.getAffectedTables().isEmpty());
    }

    @Test
    void testIsExpired_NotExpired() {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(300);
        OpQueryResultProto proto = createTestProto(1, 1);
        
        CachedQueryResult result = new CachedQueryResult(proto, now, expiresAt, Set.of());
        
        assertFalse(result.isExpired());
    }

    @Test
    void testIsExpired_Expired() {
        Instant now = Instant.now();
        Instant expiresAt = now.minusSeconds(1); // Already expired
        OpQueryResultProto proto = createTestProto(1, 1);
        
        CachedQueryResult result = new CachedQueryResult(proto, now, expiresAt, Set.of());
        
        assertTrue(result.isExpired());
    }

    @Test
    void testIsExpired_WithTime_NotExpired() {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(300);
        OpQueryResultProto proto = createTestProto(1, 1);
        
        CachedQueryResult result = new CachedQueryResult(proto, now, expiresAt, Set.of());
        
        assertFalse(result.isExpired(now.plusSeconds(100)));
    }

    @Test
    void testIsExpired_WithTime_Expired() {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(300);
        OpQueryResultProto proto = createTestProto(1, 1);
        
        CachedQueryResult result = new CachedQueryResult(proto, now, expiresAt, Set.of());
        
        assertTrue(result.isExpired(now.plusSeconds(400)));
    }

    @Test
    void testEstimatedSizeBytes() {
        OpQueryResultProto proto = createTestProto(10, 5);
        Instant now = Instant.now();
        
        CachedQueryResult result = new CachedQueryResult(proto, now, now.plusSeconds(60), Set.of());
        
        // Proto's serialized size should be used
        assertTrue(result.getEstimatedSizeBytes() > 0);
        assertEquals(proto.getSerializedSize(), result.getEstimatedSizeBytes());
    }

    @Test
    void testRowCountMatchesProto() {
        OpQueryResultProto proto = createTestProto(5, 3);
        Instant now = Instant.now();
        
        CachedQueryResult result = new CachedQueryResult(proto, now, now.plusSeconds(60), Set.of());
        
        assertEquals(5, result.getRowCount());
        assertEquals(proto.getRowsCount(), result.getRowCount());
    }

    @Test
    void testColumnCountMatchesProto() {
        OpQueryResultProto proto = createTestProto(3, 7);
        Instant now = Instant.now();
        
        CachedQueryResult result = new CachedQueryResult(proto, now, now.plusSeconds(60), Set.of());
        
        assertEquals(7, result.getColumnCount());
        assertEquals(proto.getLabelsCount(), result.getColumnCount());
    }

    @Test
    void testGetQueryResultProto() {
        OpQueryResultProto proto = createTestProto(2, 2);
        Instant now = Instant.now();
        
        CachedQueryResult result = new CachedQueryResult(proto, now, now.plusSeconds(60), Set.of());
        
        assertSame(proto, result.getQueryResultProto());
    }

    @Test
    void testAffectedTablesImmutable() {
        OpQueryResultProto proto = createTestProto(1, 1);
        Instant now = Instant.now();
        Set<String> tables = Set.of("users", "orders");
        
        CachedQueryResult result = new CachedQueryResult(proto, now, now.plusSeconds(60), tables);
        
        assertEquals(tables, result.getAffectedTables());
        // Should be a copy, not the same instance
        assertNotSame(tables, result.getAffectedTables());
    }

    @Test
    void testToString() {
        OpQueryResultProto proto = createTestProto(10, 5);
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(300);
        
        CachedQueryResult result = new CachedQueryResult(proto, now, expiresAt, Set.of("users"));
        
        String str = result.toString();
        assertTrue(str.contains("rowCount=10"));
        assertTrue(str.contains("columnCount=5"));
        assertTrue(str.contains("affectedTables"));
    }

    @Test
    void testEmptyResult() {
        OpQueryResultProto proto = OpQueryResultProto.newBuilder()
                .setResultSetUUID("empty")
                .build();
        Instant now = Instant.now();
        
        CachedQueryResult result = new CachedQueryResult(proto, now, now.plusSeconds(60), Set.of());
        
        assertEquals(0, result.getRowCount());
        assertEquals(0, result.getColumnCount());
        assertTrue(result.getEstimatedSizeBytes() > 0); // Proto still has overhead
    }
}

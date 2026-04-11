package org.openjproxy.jdbc;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OjpDataSource}.
 */
class OjpDataSourceTest {

    @Test
    void testNoArgConstructor() {
        OjpDataSource ds = new OjpDataSource();
        assertNotNull(ds);
        assertNull(ds.getUrl());
        assertNull(ds.getUser());
        assertNull(ds.getPassword());
    }

    @Test
    void testConvenienceConstructor() {
        OjpDataSource ds = new OjpDataSource(
                "jdbc:ojp[localhost:1059]_postgresql://localhost/testdb",
                "testuser",
                "testpass"
        );
        assertNotNull(ds);
        assertEquals("jdbc:ojp[localhost:1059]_postgresql://localhost/testdb", ds.getUrl());
        assertEquals("testuser", ds.getUser());
        assertEquals("testpass", ds.getPassword());
    }

    @Test
    void testSettersAndGetters() {
        OjpDataSource ds = new OjpDataSource();

        ds.setUrl("jdbc:ojp[localhost:1059]_postgresql://localhost/testdb");
        ds.setUser("user1");
        ds.setPassword("pass1");

        assertEquals("jdbc:ojp[localhost:1059]_postgresql://localhost/testdb", ds.getUrl());
        assertEquals("user1", ds.getUser());
        assertEquals("pass1", ds.getPassword());
    }

    @Test
    void testLoginTimeout() throws SQLException {
        OjpDataSource ds = new OjpDataSource();

        assertEquals(0, ds.getLoginTimeout());

        ds.setLoginTimeout(30);
        assertEquals(30, ds.getLoginTimeout());
        // setLoginTimeout delegates to DriverManager (JVM-wide setting)
        assertEquals(30, java.sql.DriverManager.getLoginTimeout());

        // Reset to avoid side-effects on other tests
        ds.setLoginTimeout(0);
    }

    @Test
    void testLogWriter() throws SQLException {
        OjpDataSource ds = new OjpDataSource();

        assertNull(ds.getLogWriter());

        PrintWriter writer = new PrintWriter(System.out);
        ds.setLogWriter(writer);
        assertEquals(writer, ds.getLogWriter());
    }

    @Test
    void testGetConnectionWithoutUrlThrowsSQLException() {
        OjpDataSource ds = new OjpDataSource();
        ds.setUser("testuser");
        ds.setPassword("testpass");

        assertThrows(SQLException.class, ds::getConnection);
    }

    @Test
    void testGetConnectionWithUsernamePasswordWithoutUrlThrowsSQLException() {
        OjpDataSource ds = new OjpDataSource();

        assertThrows(SQLException.class, () -> ds.getConnection("user", "pass"));
    }

    @Test
    void testGetConnectionWithEmptyUrlThrowsSQLException() {
        OjpDataSource ds = new OjpDataSource();
        ds.setUrl("");

        assertThrows(SQLException.class, ds::getConnection);
    }

    @Test
    void testGetParentLoggerThrowsSQLFeatureNotSupportedException() {
        OjpDataSource ds = new OjpDataSource();

        assertThrows(SQLFeatureNotSupportedException.class, ds::getParentLogger);
    }

    @Test
    void testIsWrapperFor() throws SQLException {
        OjpDataSource ds = new OjpDataSource();

        assertTrue(ds.isWrapperFor(OjpDataSource.class));
        assertTrue(ds.isWrapperFor(javax.sql.DataSource.class));
        assertFalse(ds.isWrapperFor(String.class));
    }

    @Test
    void testUnwrap() throws SQLException {
        OjpDataSource ds = new OjpDataSource();

        assertSame(ds, ds.unwrap(OjpDataSource.class));
        assertSame(ds, ds.unwrap(javax.sql.DataSource.class));
    }

    @Test
    void testUnwrapIncompatibleTypeThrowsSQLException() {
        OjpDataSource ds = new OjpDataSource();

        assertThrows(SQLException.class, () -> ds.unwrap(String.class));
    }

    @Test
    void testImplementsDataSource() {
        OjpDataSource ds = new OjpDataSource();

        assertInstanceOf(javax.sql.DataSource.class, ds);
    }
}

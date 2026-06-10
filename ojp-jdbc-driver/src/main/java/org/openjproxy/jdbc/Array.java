package org.openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/**
 * Stub implementation of {@link java.sql.Array} for the OJP JDBC driver.
 *
 * <p><strong>Status: not yet implemented.</strong> All methods return empty/null values and
 * log a debug message at the moment they are called.
 *
 * <p><strong>JDBC specification reference:</strong> JDBC 4.2 specification, Chapter 14
 * "Advanced Data Types", section 14.2 — {@code java.sql.Array} is the Java mapping for the
 * SQL standard {@code ARRAY} type (also referred to as a "collection type").
 *
 * <p><strong>Known use-cases that require a full implementation:</strong>
 * <ul>
 *   <li>Reading SQL {@code ARRAY} columns from PostgreSQL (e.g., {@code integer[]},
 *       {@code text[]}) via {@link java.sql.ResultSet#getArray(int)}.</li>
 *   <li>Binding array parameters to a {@link java.sql.PreparedStatement} through
 *       {@link java.sql.Connection#createArrayOf(String, Object[])} and
 *       {@link java.sql.PreparedStatement#setArray(int, java.sql.Array)}.</li>
 *   <li>Receiving array-typed {@code OUT} or {@code INOUT} parameters from stored
 *       procedures / functions via {@link java.sql.CallableStatement#getArray(int)}.</li>
 *   <li>Any application that maps an ORM array column type (e.g., Hibernate
 *       {@code @Array}, Spring Data JPA array projections) to a JDBC {@code Array}.</li>
 * </ul>
 *
 * <p>When implementing this class, the array data will need to be transported over the
 * OJP gRPC protocol. The {@code .proto} contract in {@code ojp-grpc-commons} will likely
 * need a new field or message type to carry typed array payloads.
 */
@Slf4j
public class Array implements java.sql.Array {

    @Override
    public String getBaseTypeName() throws SQLException {
        log.debug("getBaseTypeName called");
        return "";
    }

    @Override
    public int getBaseType() throws SQLException {
        log.debug("getBaseType called");
        return 0;
    }

    @Override
    public Object getArray() throws SQLException {
        log.debug("getArray called");
        return null;
    }

    @Override
    public Object getArray(Map<String, Class<?>> map) throws SQLException {
        log.debug("getArray: <Map> called");
        return null;
    }

    @Override
    public Object getArray(long index, int count) throws SQLException {
        log.debug("getArray: {}, {} called", index, count);
        return null;
    }

    @Override
    public Object getArray(long index, int count, Map<String, Class<?>> map) throws SQLException {
        log.debug("getArray: {}, {}, <Map> called", index, count);
        return null;
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        log.debug("getResultSet called");
        return null;
    }

    @Override
    public ResultSet getResultSet(Map<String, Class<?>> map) throws SQLException {
        log.debug("getResultSet: <Map> called");
        return null;
    }

    @Override
    public ResultSet getResultSet(long index, int count) throws SQLException {
        log.debug("getResultSet: {}, {} called", index, count);
        return null;
    }

    @Override
    public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) throws SQLException {
        log.debug("getResultSet: {}, {}, <Map> called", index, count);
        return null;
    }

    @Override
    public void free() throws SQLException {
        log.debug("free called");
    }
}

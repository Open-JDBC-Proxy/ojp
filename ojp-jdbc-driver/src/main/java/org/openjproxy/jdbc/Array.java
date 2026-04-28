package org.openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

@Slf4j
//TODO implement this class
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

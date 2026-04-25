package org.openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;

//TODO implement methods
@Slf4j
public class ParameterMetaData implements java.sql.ParameterMetaData {

    @Override
    public int getParameterCount() throws SQLException {
        log.debug("getParameterCount called");
        return 0;
    }

    @Override
    public int isNullable(int param) throws SQLException {
        log.debug("isNullable: {}", param);
        return 0;
    }

    @Override
    public boolean isSigned(int param) throws SQLException {
        log.debug("isSigned: {}", param);
        return false;
    }

    @Override
    public int getPrecision(int param) throws SQLException {
        log.debug("getPrecision: {}", param);
        return 0;
    }

    @Override
    public int getScale(int param) throws SQLException {
        log.debug("getScale: {}", param);
        return 0;
    }

    @Override
    public int getParameterType(int param) throws SQLException {
        log.debug("getParameterType: {}", param);
        return 0;
    }

    @Override
    public String getParameterTypeName(int param) throws SQLException {
        log.debug("getParameterTypeName: {}", param);
        return "";
    }

    @Override
    public String getParameterClassName(int param) throws SQLException {
        log.debug("getParameterClassName: {}", param);
        return "";
    }

    @Override
    public int getParameterMode(int param) throws SQLException {
        log.debug("getParameterMode: {}", param);
        return 0;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        log.debug("unwrap: {}", iface);
        return null;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        log.debug("isWrapperFor: {}", iface);
        return false;
    }
}

package org.openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.sql.SQLException;

//TODO to be implemented
@Slf4j
public class SQLXML implements java.sql.SQLXML {

    @Override
    public void free() throws SQLException {
        log.debug("free called");
    }

    @Override
    public InputStream getBinaryStream() throws SQLException {
        log.debug("getBinaryStream called");
        return null;
    }

    @Override
    public OutputStream setBinaryStream() throws SQLException {
        log.debug("setBinaryStream called");
        return null;
    }

    @Override
    public Reader getCharacterStream() throws SQLException {
        log.debug("getCharacterStream called");
        return null;
    }

    @Override
    public Writer setCharacterStream() throws SQLException {
        log.debug("setCharacterStream called");
        return null;
    }

    @Override
    public String getString() throws SQLException {
        log.debug("getString called");
        return "";
    }

    @Override
    public void setString(String value) throws SQLException {
        log.debug("setString called: {}", value);
    }

    @Override
    public <T extends Source> T getSource(Class<T> sourceClass) throws SQLException {
        log.debug("getSource called: {}", sourceClass);
        return null;
    }

    @Override
    public <T extends Result> T setResult(Class<T> resultClass) throws SQLException {
        log.debug("setResult called: {}", resultClass);
        return null;
    }
}

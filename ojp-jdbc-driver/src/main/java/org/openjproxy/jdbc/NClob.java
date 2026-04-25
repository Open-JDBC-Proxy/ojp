package org.openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.sql.Clob;
import java.sql.SQLException;

//TODO to be implemented
@Slf4j
public class NClob implements java.sql.NClob {

    @Override
    public long length() throws SQLException {
        log.debug("length called");
        return 0;
    }

    @Override
    public String getSubString(long pos, int length) throws SQLException {
        log.debug("getSubString: {}, {}", pos, length);
        return "";
    }

    @Override
    public Reader getCharacterStream() throws SQLException {
        log.debug("getCharacterStream called");
        return null;
    }

    @Override
    public InputStream getAsciiStream() throws SQLException {
        log.debug("getAsciiStream called");
        return null;
    }

    @Override
    public long position(String searchstr, long start) throws SQLException {
        log.debug("position: {}, {}", searchstr, start);
        return 0;
    }

    @Override
    public long position(Clob searchstr, long start) throws SQLException {
        log.debug("position: <Clob>, {}", start);
        return 0;
    }

    @Override
    public int setString(long pos, String str) throws SQLException {
        log.debug("setString: {}, {}", pos, str);
        return 0;
    }

    @Override
    public int setString(long pos, String str, int offset, int len) throws SQLException {
        log.debug("setString: {}, {}, {}, {}", pos, str, offset, len);
        return 0;
    }

    @Override
    public OutputStream setAsciiStream(long pos) throws SQLException {
        log.debug("setAsciiStream: {}", pos);
        return null;
    }

    @Override
    public Writer setCharacterStream(long pos) throws SQLException {
        log.debug("setCharacterStream: {}", pos);
        return null;
    }

    @Override
    public void truncate(long len) throws SQLException {
        log.debug("truncate: {}", len);
    }

    @Override
    public void free() throws SQLException {
        log.debug("free called");
    }

    @Override
    public Reader getCharacterStream(long pos, long length) throws SQLException {
        log.debug("getCharacterStream: {}, {}", pos, length);
        return null;
    }
}

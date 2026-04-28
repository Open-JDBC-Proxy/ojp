package org.openjproxy.grpc.server;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ConnectionBuilder;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverAction;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLData;
import java.sql.SQLOutput;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.ShardingKey;
import java.sql.ShardingKeyBuilder;
import java.sql.Statement;
import java.sql.Struct;

public class JavaSqlInterfacesConverter {

    private JavaSqlInterfacesConverter() {}

    /**
     * Always try to operate on the java.sql interfaces as per some implementations of JDBC drivers block reflection calls
     * directly to implementation methods.
     *
     * @param clazz - Class of concrete resource object.
     * @return Class of the java.sql Interface the resource implements.
     */
    public static Class<?> interfaceClass(Class<?> clazz) {
        if (Savepoint.class.isAssignableFrom(clazz)) {
            return Savepoint.class;
        } else if (CallableStatement.class.isAssignableFrom(clazz)) { // CallableStatement has to be before PreparedStatement as per PreparedStatement extends CallableStatement
            return CallableStatement.class;
        } else if (PreparedStatement.class.isAssignableFrom(clazz)) {
            return PreparedStatement.class;
        } else if (Statement.class.isAssignableFrom(clazz)) { // Statement has to be after PreparedStatement and CallableStatement as per both inherit Statement
            return Statement.class;
        } else if (Connection.class.isAssignableFrom(clazz)) {
            return Connection.class;
        } else if (ResultSet.class.isAssignableFrom(clazz)) {
            return ResultSet.class;
        } else if (Blob.class.isAssignableFrom(clazz)) {
            return Blob.class;
        } else if (Array.class.isAssignableFrom(clazz)) {
            return Array.class;
        } else if (Clob.class.isAssignableFrom(clazz)) {
            return Clob.class;
        } else if (ConnectionBuilder.class.isAssignableFrom(clazz)) {
            return ConnectionBuilder.class;
        } else if (DatabaseMetaData.class.isAssignableFrom(clazz)) {
            return DatabaseMetaData.class;
        } else if (Driver.class.isAssignableFrom(clazz)) {
            return Driver.class;
        } else if (DriverAction.class.isAssignableFrom(clazz)) {
            return DriverAction.class;
        } else if (NClob.class.isAssignableFrom(clazz)) {
            return NClob.class;
        } else if (ParameterMetaData.class.isAssignableFrom(clazz)) {
            return ParameterMetaData.class;
        } else if (Ref.class.isAssignableFrom(clazz)) {
            return Ref.class;
        } else if (ResultSetMetaData.class.isAssignableFrom(clazz)) {
            return ResultSetMetaData.class;
        } else if (RowId.class.isAssignableFrom(clazz)) {
            return RowId.class;
        } else if (ShardingKey.class.isAssignableFrom(clazz)) {
            return ShardingKey.class;
        } else if (ShardingKeyBuilder.class.isAssignableFrom(clazz)) {
            return ShardingKeyBuilder.class;
        } else if (SQLData.class.isAssignableFrom(clazz)) {
            return SQLData.class;
        } else if (SQLOutput.class.isAssignableFrom(clazz)) {
            return SQLOutput.class;
        } else if (SQLType.class.isAssignableFrom(clazz)) {
            return SQLType.class;
        } else if (SQLXML.class.isAssignableFrom(clazz)) {
            return SQLXML.class;
        } else if (Struct.class.isAssignableFrom(clazz)) {
            return Struct.class;
        }
        return clazz;
    }
}

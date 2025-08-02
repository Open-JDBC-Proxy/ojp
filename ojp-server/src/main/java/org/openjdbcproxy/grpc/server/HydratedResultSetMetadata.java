package org.openjdbcproxy.grpc.server;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * HydratedResultSetMetadata is a wrapper around a {@link ResultSetMetaData} object
 * that captures and stores all metadata attributes eagerly at the time of construction.
 *
 * This allows decoupling metadata usage from the lifecycle of the original ResultSet,
 * which may become invalid or closed, especially when working with JDBC drivers that
 * aggressively clean up metadata.
 *
 * Only used by DB2 at the moment as per DB2 is the most aggressive to close ResultSets causing situations
 * where the ResultSet is closed before close method is called and errors in OJP.
 */
public class HydratedResultSetMetadata implements ResultSetMetaData {

    private static class ColumnMeta {
        int columnType;
        String columnTypeName;
        String columnLabel;
        String columnName;
        String schemaName;
        String tableName;
        String catalogName;
        int columnDisplaySize;
        int precision;
        int scale;
        int nullable;
        boolean autoIncrement;
        boolean caseSensitive;
        boolean searchable;
        boolean currency;
        boolean signed;
        boolean readOnly;
        boolean writable;
        boolean definitelyWritable;
        String columnClassName;
    }

    private final List<ColumnMeta> columns = new ArrayList<>();

    public HydratedResultSetMetadata(ResultSetMetaData original) throws SQLException {
        int count = original.getColumnCount();
        for (int i = 1; i <= count; i++) {
            ColumnMeta col = new ColumnMeta();
            col.columnType = original.getColumnType(i);
            col.columnTypeName = original.getColumnTypeName(i);
            col.columnLabel = original.getColumnLabel(i);
            col.columnName = original.getColumnName(i);
            col.schemaName = original.getSchemaName(i);
            col.tableName = original.getTableName(i);
            col.catalogName = original.getCatalogName(i);
            col.columnDisplaySize = original.getColumnDisplaySize(i);
            col.precision = original.getPrecision(i);
            col.scale = original.getScale(i);
            col.nullable = original.isNullable(i);
            col.autoIncrement = original.isAutoIncrement(i);
            col.caseSensitive = original.isCaseSensitive(i);
            col.searchable = original.isSearchable(i);
            col.currency = original.isCurrency(i);
            col.signed = original.isSigned(i);
            col.readOnly = original.isReadOnly(i);
            col.writable = original.isWritable(i);
            col.definitelyWritable = original.isDefinitelyWritable(i);
            col.columnClassName = original.getColumnClassName(i);
            columns.add(col);
        }
    }

    private ColumnMeta get(int column) throws SQLException {
        if (column < 1 || column > columns.size()) {
            throw new SQLException("Invalid column index: " + column);
        }
        return columns.get(column - 1);
    }

    @Override public int getColumnCount() { return columns.size(); }
    @Override public boolean isAutoIncrement(int column) throws SQLException { return get(column).autoIncrement; }
    @Override public boolean isCaseSensitive(int column) throws SQLException { return get(column).caseSensitive; }
    @Override public boolean isSearchable(int column) throws SQLException { return get(column).searchable; }
    @Override public boolean isCurrency(int column) throws SQLException { return get(column).currency; }
    @Override public int isNullable(int column) throws SQLException { return get(column).nullable; }
    @Override public boolean isSigned(int column) throws SQLException { return get(column).signed; }
    @Override public int getColumnDisplaySize(int column) throws SQLException { return get(column).columnDisplaySize; }
    @Override public String getColumnLabel(int column) throws SQLException { return get(column).columnLabel; }
    @Override public String getColumnName(int column) throws SQLException { return get(column).columnName; }
    @Override public String getSchemaName(int column) throws SQLException { return get(column).schemaName; }
    @Override public int getPrecision(int column) throws SQLException { return get(column).precision; }
    @Override public int getScale(int column) throws SQLException { return get(column).scale; }
    @Override public String getTableName(int column) throws SQLException { return get(column).tableName; }
    @Override public String getCatalogName(int column) throws SQLException { return get(column).catalogName; }
    @Override public int getColumnType(int column) throws SQLException { return get(column).columnType; }
    @Override public String getColumnTypeName(int column) throws SQLException { return get(column).columnTypeName; }
    @Override public boolean isReadOnly(int column) throws SQLException { return get(column).readOnly; }
    @Override public boolean isWritable(int column) throws SQLException { return get(column).writable; }
    @Override public boolean isDefinitelyWritable(int column) throws SQLException { return get(column).definitelyWritable; }
    @Override public String getColumnClassName(int column) throws SQLException { return get(column).columnClassName; }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("unwrap not supported"); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
}
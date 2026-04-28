package org.openjproxy.grpc.server.sql;

import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.AnsiSqlDialect;
import org.apache.calcite.sql.dialect.H2SqlDialect;
import org.apache.calcite.sql.dialect.MssqlSqlDialect;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import org.apache.calcite.sql.dialect.OracleSqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.fun.SqlLibrary;

/**
 * Supported SQL dialects for the SQL enhancer engine.
 * Maps to Apache Calcite's dialect implementations.
 */
public enum OjpSqlDialect {
    GENERIC(AnsiSqlDialect.DEFAULT, SqlLibrary.STANDARD),
    POSTGRESQL(PostgresqlSqlDialect.DEFAULT, SqlLibrary.POSTGRESQL),
    MYSQL(MysqlSqlDialect.DEFAULT, SqlLibrary.MYSQL),
    ORACLE(OracleSqlDialect.DEFAULT, SqlLibrary.ORACLE),
    SQL_SERVER(MssqlSqlDialect.DEFAULT, SqlLibrary.MSSQL),
    H2(H2SqlDialect.DEFAULT, SqlLibrary.STANDARD);

    private final SqlDialect calciteDialect;
    private final SqlLibrary sqlLibrary;

    OjpSqlDialect(SqlDialect calciteDialect, SqlLibrary sqlLibrary) {
        this.calciteDialect = calciteDialect;
        this.sqlLibrary = sqlLibrary;
    }

    public SqlDialect getCalciteDialect() {
        return calciteDialect;
    }

    /**
     * Get the Calcite SQL library for this dialect.
     * Used to load dialect-specific operators and functions.
     *
     * @return SqlLibrary for this dialect
     */
    public SqlLibrary getSqlLibrary() {
        return sqlLibrary;
    }

    /**
     * Get dialect by name, case-insensitive.
     * Defaults to GENERIC if not found.
     */
    public static OjpSqlDialect fromString(String name) {
        if (name == null || name.trim().isEmpty()) {
            return GENERIC;
        }

        try {
            return valueOf(name.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return GENERIC;
        }
    }
}

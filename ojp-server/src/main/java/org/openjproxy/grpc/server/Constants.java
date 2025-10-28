package org.openjproxy.grpc.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reusable constants. Mainly to save memory by creating less repeated objects.
 */
public class Constants {
    public static final String SHA_256 = "SHA-256";
    public static final String EMPTY_STRING = "";
    public static final List EMPTY_LIST = new ArrayList<>();
    public static final Map EMPTY_MAP = new HashMap<>();
    public static final String H2_DRIVER_CLASS = "org.h2.Driver";
    public static final String POSTGRES_DRIVER_CLASS = "org.postgresql.Driver";
    public static final String MYSQL_DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";
    public static final String MARIADB_DRIVER_CLASS = "org.mariadb.jdbc.Driver";
    public static final String ORACLE_DRIVER_CLASS = "oracle.jdbc.OracleDriver";
    public static final String SQLSERVER_DRIVER_CLASS = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    public static final String DB2_DRIVER_CLASS = "com.ibm.db2.jcc.DB2Driver";
}

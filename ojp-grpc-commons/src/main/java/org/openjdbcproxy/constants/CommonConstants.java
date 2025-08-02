package org.openjdbcproxy.constants;

/**
 * Holds common constants used in both the JDBC driver and the OJP proxy server.
 */
public class CommonConstants {
    public static final int ROWS_PER_RESULT_SET_DATA_BLOCK = 100;
    public static final int MAX_LOB_DATA_BLOCK_SIZE = 1024;//1KB per block
    public static final int PREPARED_STATEMENT_BINARY_STREAM_INDEX = 1;
    public static final int PREPARED_STATEMENT_BINARY_STREAM_LENGTH = 2;
    public static final int PREPARED_STATEMENT_BINARY_STREAM_SQL = 3;
    public static final int PREPARED_STATEMENT_UUID_BINARY_STREAM = 4;
    public static final String PREPARED_STATEMENT_SQL_KEY = "PREPARED_STATEMENT_SQL_KEY";
    public static final String PREPARED_STATEMENT_ADD_BATCH_FLAG = "PREPARED_STATEMENT_ADD_BATCH_FLAG";
    public static final String PREPARED_STATEMENT_EXECUTE_BATCH_FLAG = "PREPARED_STATEMENT_EXECUTE_BATCH_FLAG";
    public static final String STATEMENT_RESULT_SET_TYPE_KEY = "STATEMENT_RESULT_SET_TYPE_KEY";
    public static final String STATEMENT_RESULT_SET_CONCURRENCY_KEY = "STATEMENT_RESULT_SET_CONCURRENCY_KEY";
    public static final String STATEMENT_RESULT_SET_HOLDABILITY_KEY = "STATEMENT_RESULT_SET_HOLDABILITY_KEY";
    public static final String STATEMENT_AUTO_GENERATED_KEYS_KEY = "STATEMENT_AUTO_GENERATED_KEYS_KEY";
    public static final String STATEMENT_COLUMN_INDEXES_KEY = "STATEMENT_COLUMN_INDEXES_KEY";
    public static final String STATEMENT_COLUMN_NAMES_KEY = "STATEMENT_COLUMN_NAMES_KEY";
    public static final String RESULT_SET_ROW_BY_ROW_MODE = "RESULT_SET_ROW_BY_ROW_MODE";
    public static final int DEFAULT_PORT_NUMBER = 1059;
    public static final String OJP_REGEX_PATTERN = "ojp\\[([^\\]]+)\\]";
    public static final String OJP_CLOB_PREFIX = "OJP_CLOB_PREFIX:";

    // HikariCP default connection pool settings
    public static final int DEFAULT_MAXIMUM_POOL_SIZE = 10;
    public static final int DEFAULT_MINIMUM_IDLE = 10;
    public static final long DEFAULT_IDLE_TIMEOUT = 600000;
    public static final long DEFAULT_MAX_LIFETIME = 1800000;
    public static final long DEFAULT_CONNECTION_TIMEOUT = 30000;
}

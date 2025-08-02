# IBM DB2 Database Testing Guide

This document explains how to set up and run IBM DB2 Database tests with OJP.

## Prerequisites

1. **Docker** - Required to run IBM DB2 locally
2. **IBM DB2 JDBC Driver** - Automatically included in dependencies

## Setup Instructions

### 1. Start IBM DB2 Database

Use the official IBM DB2 image for testing:

```bash
docker run -d --name ojp-db2   --privileged   -p 50000:50000 -m 6g  -e LICENSE=accept   -e DB2INSTANCE=db2inst1   -e DB2INST1_PASSWORD=testpass   -e DBNAME=testdb   ibmcom/db2:11.5.8.0
```

Wait for the database to fully start (may take several minutes). You can check the logs:

```bash
docker logs db2
```

### 2. IBM DB2 JDBC Driver

The IBM DB2 JDBC driver is automatically included in the ojp-server dependencies:

```xml
<dependency>
    <groupId>com.ibm.db2</groupId>
    <artifactId>jcc</artifactId>
    <version>11.5.9.0</version>
</dependency>
```

### 3. Start OJP Server

In a separate terminal:
```bash
cd ojp
mvn verify -pl ojp-server -Prun-ojp-server
```

### 4. Run DB2 Tests

To run only DB2 tests:

```bash
cd ojp-jdbc-driver
mvn test -DenableDb2Tests -DdisablePostgresTests -DdisableMySQLTests -DdisableMariaDBTests
```

To run DB2 tests alongside other databases:

```bash
cd ojp-jdbc-driver
mvn test -DenableDb2Tests -DenableOracleTests
```

To run specific DB2 test classes:

```bash
cd ojp-jdbc-driver
mvn test -Dtest=Db2* -DenableDb2Tests=true
```

## Test Configuration Files

- `db2_connections.csv` - DB2-only connection configuration
- `h2_postgres_mysql_mariadb_oracle_sqlserver_connections.csv` - Multi-database configuration including DB2

### Database Connection Details

- **URL**: `jdbc:ojp[localhost:1059]_db2://localhost:50000/defaultdb`
- **User**: `db2inst1`
- **Password**: `testpassword`
- **Database**: `defaultdb`

## Connection String Format

The DB2 connection string for OJP follows this format:

```
jdbc:ojp[localhost:1059]_db2://db2host:50000/database
```

Where:
- `localhost:1059` - OJP server address and port
- `db2://localhost:50000` - DB2 instance
- `database` - Target database name

## Skipping DB2 Tests

DB2 tests are skipped by default, use:
```bash
mvn test
```

Also can explicitly disable DB2 tests as in:

```bash
mvn test -DenableDb2Tests=false
```

## LOBs and ResultSetMetadata special treatment

In DB2 JDBC driver, both LOBs and ResultSetMetaData become invalid once the cursor advances or the ResultSet is accessed from another thread. To handle this, OJP reads rows one at a time instead of batching multiple rows and eagerly caches LOB data and metadata immediately upon row access to ensure consistency and prevent driver exceptions.
# IBM DB2 Database Testing Guide

This document explains how to set up and run IBM DB2 Database tests with OJP.

## Prerequisites

1. **Docker** - Required to run IBM DB2 locally
2. **IBM DB2 JDBC Driver** - Must be downloaded and placed in `ojp-libs` folder

## Setup Instructions

### 1. Start IBM DB2 Database

Use the official IBM DB2 image for testing:

```bash
docker pull icr.io/db2_community/db2
```

After pulling:

```bash
docker run -d --name ojp-db2   --privileged   -p 50000:50000 -m 6g  -e LICENSE=accept   -e DB2INSTANCE=db2inst1   -e DB2INST1_PASSWORD=testpass   -e DBNAME=testdb   icr.io/db2_community/db2
```

Wait for the database to fully start (may take several minutes). You can check the logs:

```bash
docker logs db2
```

### 2. IBM DB2 JDBC Driver

> **⚠️ For Version 0.4.0-beta and Later:**  
> JDBC drivers are no longer added to pom.xml. Instead, download the driver and place it in the `ojp-libs` folder.

Download the IBM DB2 JDBC driver and place it in the `ojp-libs` directory:

```bash
# Option 1: Copy from your DB2 installation
# Common locations:
# Linux: /opt/ibm/db2/V11.5/java/db2jcc4.jar
# Windows: C:\Program Files\IBM\SQLLIB\java\db2jcc4.jar

# Create ojp-libs directory if it doesn't exist
mkdir -p ojp-libs

# Copy the driver
cp /opt/ibm/db2/V11.5/java/db2jcc4.jar ./ojp-libs/

# Optional: Add license jar if needed
cp /opt/ibm/db2/V11.5/java/db2jcc_license_cu.jar ./ojp-libs/

# Option 2: Download from Maven Central
# You can download the DB2 JDBC driver from Maven Central:
# https://mvnrepository.com/artifact/com.ibm.db2/jcc
# For example, version 11.5.9.0:
wget -P ojp-libs https://repo1.maven.org/maven2/com/ibm/db2/jcc/11.5.9.0/jcc-11.5.9.0.jar
# Or using curl:
# curl -o ojp-libs/jcc-11.5.9.0.jar https://repo1.maven.org/maven2/com/ibm/db2/jcc/11.5.9.0/jcc-11.5.9.0.jar

# Verify the files are in place
ls -lh ojp-libs/db2jcc*.jar
```

For more details on driver setup, see the [Database Drivers Configuration Guide](../configuration/DRIVERS_AND_LIBS.md).

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
mvn test -DenableDb2Tests
```

To run DB2 tests alongside other databases:

```bash
cd ojp-jdbc-driver
mvn test -DenableDb2Tests -DenableOracleTests -DenableSqlServerTests
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

In DB2 JDBC driver, both LOBs and ResultSetMetaData become invalid once the cursor advances or the ResultSet is accessed from another thread. To handle this, OJP reads rows one at a time when LOBs are present instead of batching multiple rows and eagerly caches LOB data and metadata immediately upon row access to ensure consistency and prevent driver exceptions.

# Oracle Database Testing Guide

This document explains how to set up and run Oracle Database tests with OJP.

## Prerequisites

1. **Docker** - Required to run Oracle Database locally
2. **Oracle JDBC Driver** - Must be manually downloaded and installed

## Setup Instructions

### 1. Start Oracle Database

Use the community Oracle XE image for testing:

```bash
docker run --name ojp-oracle -e ORACLE_PASSWORD=testpassword -e APP_USER=testuser -e APP_USER_PASSWORD=testpassword -d -p 1521:1521 gvenzl/oracle-xe:21-full
```

Wait for the database to fully start (may take a few minutes).

### 2. Add Oracle JDBC Driver

> **⚠️ For Version 0.4.0-beta and Later:**  
> JDBC drivers are no longer added to pom.xml. Instead, download the driver and place it in the `ojp-libs` folder.

Download the Oracle JDBC driver from [Oracle's website](https://www.oracle.com/database/technologies/jdbc-downloads.html) and place it in the `ojp-libs` directory:

```bash
# Download ojdbc11.jar from Oracle (requires Oracle account)
# https://www.oracle.com/database/technologies/jdbc-downloads.html

# Place in ojp-libs directory (create if it doesn't exist)
mkdir -p ojp-libs
cp ~/Downloads/ojdbc11.jar ./ojp-libs/

# Verify the file is in place
ls -lh ojp-libs/ojdbc11.jar
```

For more details on driver setup, see the [Database Drivers Configuration Guide](../configuration/DRIVERS_AND_LIBS.md).

### 3. Start OJP Server

In a separate terminal:
```bash
cd ojp
mvn verify -pl ojp-server -Prun-ojp-server
```

### 4. Run Oracle Tests
To run only Oracle tests:

```bash
cd ojp-jdbc-driver
mvn test -DenableOracleTests
```

## Test Configuration Files

- `oracle_connections.csv` - Oracle-only connection configuration
- `h2_oracle_connections.csv` - Combined H2 and Oracle configuration for mixed testing
- `h2_mysql_mariadb_oracle_connections.csv` - Combined H2, MySQL, MariaDB and Oracle configurations for tests that are shared among these databases.
- `h2_postgres_mysql_mariadb_oracle_connections` - Combined H2, Postgres, MySQL, MariaDB and Oracle configurations for tests that are shared among these databases.

### Database Connection Details

- **URL**: `jdbc:ojp[localhost:1059]_oracle:thin:@localhost:1521/XEPDB1`
- **User**: `testuser`
- **Password**: `testpassword`
- **Service**: `XEPDB1` (Oracle XE pluggable database)

## Skipping Oracle Tests

Oracle tests are skipped by default, use:
```bash
mvn test
```

Also can explicitly disable oracle tests as in:

```bash
mvn test -DenableOracleTests=false
```

To build a Docker image of ojp-server follow the above steps and then follow the [Build ojp-server docker image](/ojp-server/README.md) - OpenTelemetry integration and monitoring setup.

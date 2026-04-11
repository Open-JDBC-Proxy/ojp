# SQL Server Database Testing Guide

This document explains how to set up and run SQL Server tests with OJP.

## Prerequisites

1. **Docker** - Required to run SQL Server locally
2. **Microsoft SQL Server JDBC Driver** - Must be downloaded and placed in `ojp-libs` folder

## Setup Instructions

### 1. Start SQL Server Database

Use the official Microsoft SQL Server image for testing:

```bash
docker run --name ojp-sqlserver -e ACCEPT_EULA=Y -e SA_PASSWORD=TestPassword123! -d -p 1433:1433 mcr.microsoft.com/mssql/server:2022-latest
```

Wait for the database to fully start (may take a few minutes). You can check the logs:

```bash
docker logs ojp-sqlserver
```

### 2. Create Test Database (Optional)

Connect to the SQL Server instance and create a test database:

```bash
docker run -it --network container:ojp-sqlserver mcr.microsoft.com/mssql-tools /bin/bash

sqlcmd -S localhost -U sa -P TestPassword123!
```

Then run:
```sql
CREATE DATABASE defaultdb;
GO
CREATE LOGIN testuser WITH PASSWORD = 'TestPassword123!';
GO
USE defaultdb;
GO
CREATE USER testuser FOR LOGIN testuser;
GO
ALTER ROLE db_datareader ADD MEMBER testuser;
GO
ALTER ROLE db_datawriter ADD MEMBER testuser;
GO
ALTER ROLE db_ddladmin ADD MEMBER testuser;
GO
ALTER SERVER ROLE sysadmin ADD MEMBER testuser;
GO
```

### 3. SQL Server JDBC Driver

> **⚠️ For Version 0.4.0-beta and Later:**  
> JDBC drivers are no longer added to pom.xml. Instead, download the driver and place it in the `ojp-libs` folder.

Download the Microsoft SQL Server JDBC driver and place it in the `ojp-libs` directory:

```bash
# Download from Microsoft
# https://learn.microsoft.com/en-us/sql/connect/jdbc/download-microsoft-jdbc-driver-for-sql-server
# Choose: mssql-jdbc-12.8.1.jre11.jar (or latest version)

# Create ojp-libs directory if it doesn't exist
mkdir -p ojp-libs

# Place the driver in ojp-libs
cp ~/Downloads/mssql-jdbc-12.8.1.jre11.jar ./ojp-libs/

# Verify the file is in place
ls -lh ojp-libs/mssql-jdbc*.jar
```

For more details on driver setup, see the [Database Drivers Configuration Guide](../configuration/DRIVERS_AND_LIBS.md).

### 4. Start OJP Server

In a separate terminal:
```bash
cd ojp
mvn verify -pl ojp-server -Prun-ojp-server
```

### 5. Run SQL Server Tests

To run only SQL Server tests:

```bash
cd ojp-jdbc-driver
mvn test -DenableSqlServerTests
```

To run SQL Server tests alongside other databases:

```bash
cd ojp-jdbc-driver
mvn test -DenableSqlServerTests -DenableOracleTests
```

## Test Configuration Files

- `sqlserver_connections.csv` - SQL Server-only connection configuration
- `h2_postgres_mysql_mariadb_oracle_sqlserver_connections.csv` - Multi-database configuration including SQL Server

## Connection String Format

The SQL Server connection string for OJP follows this format:

```
jdbc:ojp[localhost:1059]_sqlserver://localhost:1433;databaseName=defaultdb;encrypt=false;trustServerCertificate=true
```

Where:
- `localhost:1059` - OJP server address and port
- `sqlserver://localhost:1433` - SQL Server instance
- `databaseName=defaultdb` - Target database
- `encrypt=false;trustServerCertificate=true` - SSL configuration for testing

## LOBs special treatment
In SQL Server JDBC driver, advancing a ResultSet invalidates any associated LOBs (Blob, Clob, binary streams). To prevent errors, OJP reads LOB-containing rows one at a time instead of batching multiple rows.

Additionally, LOBs are fully read into memory upfront, which may increase memory usage depending on their size.

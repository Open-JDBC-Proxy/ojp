# SQL Server Database Testing Guide

This document explains how to set up and run SQL Server tests with OJP.

## Prerequisites

1. **Docker** - Required to run SQL Server locally
2. **Microsoft SQL Server JDBC Driver** - Automatically included in dependencies

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
docker exec -it ojp-sqlserver /opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P TestPassword123!
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
```

### 3. SQL Server JDBC Driver

The Microsoft SQL Server JDBC driver is not automatically included in the ojp-server dependencies.

```xml
<dependency>
    <groupId>com.microsoft.sqlserver</groupId>
    <artifactId>mssql-jdbc</artifactId>
    <version>12.8.1.jre11</version>
</dependency>
```

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
mvn test -DenableSqlServerTests -DdisablePostgresTests -DdisableMySQLTests -DdisableMariaDBTests
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


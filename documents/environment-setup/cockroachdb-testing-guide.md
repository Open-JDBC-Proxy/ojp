# CockroachDB Testing Guide

This document explains how to set up and run CockroachDB tests with OJP.

## Prerequisites

1. **Docker** - Required to run CockroachDB locally

## Setup Instructions

### 1. Start CockroachDB

Use the official CockroachDB Docker image:

```bash
docker run --name ojp-cockroachdb -p 26257:26257 -p 8080:8080 -d --rm cockroachdb/cockroach:v24.3.4 start-single-node --insecure
```

Wait for the database to fully start (usually takes less than a minute).

### 2. Verify CockroachDB is Running

Check the health endpoint:

```bash
curl http://localhost:8080/health?ready=1
```

Or access the admin UI at: http://localhost:8080

### 3. Start OJP Server

In a separate terminal:

```bash
cd ojp
mvn verify -pl ojp-server -Prun-ojp-server
```

Wait for the server to start (look for "Server started" in logs).

### 4. Run CockroachDB Tests

In another terminal:

```bash
cd ojp
mvn test -pl ojp-jdbc-driver -Dgpg.skip=true
```

The CockroachDB integration tests will run automatically along with H2, PostgreSQL, and MySQL tests. MariaDB tests are disabled by default.

#### Running CockroachDB Tests in Isolation

To run **only** CockroachDB integration tests, disable the other databases that are enabled by default:

```bash
mvn test -pl ojp-jdbc-driver -DdisablePostgresTests -DdisableMySQLTests -DenableMariaDBTests=false -Dgpg.skip=true
```

## Test Configuration Files

- `cockroachdb_connection.csv` - CockroachDB-only connection configuration
- `h2_cockroachdb_connections.csv` - Combined H2 and CockroachDB configuration for mixed testing
- `h2_postgres_mysql_mariadb_oracle_sqlserver_connections.csv` - Comprehensive configuration including CockroachDB

### Database Connection Details

- **URL**: `jdbc:ojp[localhost:1059]_postgresql://localhost:26257/defaultdb?sslmode=disable`
- **User**: `root`
- **Password**: (empty/none - insecure mode)
- **Database**: `defaultdb`
- **Port**: `26257` (SQL port), `8080` (HTTP/Admin UI port)

Note: CockroachDB uses the PostgreSQL wire protocol, so the JDBC URL uses `postgresql://` but connects to CockroachDB on port 26257.

## Skipping CockroachDB Tests

To skip CockroachDB tests, use the `-DdisableCockroachDBTests` flag:

```bash
mvn test -pl ojp-jdbc-driver -DdisableCockroachDBTests=true
```

## Production Setup

For production environments, you should:

1. **Enable security**: Use `--certs-dir` instead of `--insecure`
2. **Set up TLS certificates**: Generate certificates using `cockroach cert`
3. **Create users with passwords**: Use `CREATE USER` SQL statements
4. **Configure connection string**: Update JDBC URL to include proper authentication

Example secure connection string:
```
jdbc:postgresql://localhost:26257/defaultdb?sslmode=require&user=myuser&password=mypassword
```

## Troubleshooting

### Port Already in Use

If port 26257 or 8080 is already in use:

```bash
# Find and stop the process using the port
docker ps | grep cockroach
docker stop ojp-cockroachdb

# Or use different ports:
docker run --name ojp-cockroachdb -p 26258:26257 -p 8081:8080 -d --rm cockroachdb/cockroach:v24.3.4 start-single-node --insecure
```

### Connection Refused

Ensure CockroachDB is fully started and listening:

```bash
# Check CockroachDB logs
docker logs ojp-cockroachdb

# Test connection with psql (if installed)
psql -h localhost -p 26257 -U root -d defaultdb
```

### Database Not Found

CockroachDB automatically creates the `defaultdb` database. If it doesn't exist:

```bash
# Connect with SQL client
docker exec -it ojp-cockroachdb ./cockroach sql --insecure

# Create database
CREATE DATABASE defaultdb;
```

## Additional Resources

- [CockroachDB Official Documentation](https://www.cockroachlabs.com/docs/)
- [CockroachDB PostgreSQL Compatibility](https://www.cockroachlabs.com/docs/stable/postgresql-compatibility.html)
- [CockroachDB Docker Image](https://hub.docker.com/r/cockroachdb/cockroach)
- [CockroachDB SQL Reference](https://www.cockroachlabs.com/docs/stable/sql-statements.html)

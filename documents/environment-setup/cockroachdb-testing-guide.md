# CockroachDB Testing Guide

This document explains how to set up and run CockroachDB tests with OJP using TestContainers.

## Prerequisites

1. **Docker** - Required for TestContainers to run CockroachDB containers
2. **Java 11+** - Required for running the tests
3. **Maven** - For building and testing

## Running CockroachDB Tests

CockroachDB tests now use TestContainers, which automatically manages the CockroachDB container lifecycle. No manual Docker setup is required.

### Running Tests Locally

#### 1. Start OJP Server

In a terminal:

```bash
cd ojp
mvn verify -pl ojp-server -Prun-ojp-server
```

Wait for the server to start (look for "Server started" in logs).

#### 2. Run CockroachDB Tests

In another terminal:

```bash
cd ojp
mvn test -pl ojp-jdbc-driver -DenableCockroachDBTests=true -Dgpg.skip=true
```

TestContainers will automatically:
- Download the CockroachDB Docker image (if not already cached)
- Start a CockroachDB container
- Run the tests against the container
- Stop and remove the container when tests complete

#### Running CockroachDB Tests in Isolation

To run **only** CockroachDB integration tests, disable the other databases:

```bash
mvn test -pl ojp-jdbc-driver \
  -DenableCockroachDBTests=true \
  -DdisablePostgresTests=true \
  -DdisableMySQLTests=true \
  -DenableMariaDBTests=false \
  -Dgpg.skip=true \
  -Dtest="CockroachDB*"
```

## GitHub Actions Workflow

CockroachDB tests run in their own dedicated workflow: `.github/workflows/cockroachdb-testing.yml`

This workflow:
- Runs automatically on pushes and pull requests to main
- Can be triggered manually via workflow_dispatch
- Uses TestContainers to manage the CockroachDB instance
- Tests against Java 11, 17, 21, and 22

## Test Configuration

### System Properties

- **`enableCockroachDBTests`** - Set to `true` to run CockroachDB tests (default: `false`)
- **`ojp.proxy.host`** - OJP proxy host (default: `localhost`)
- **`ojp.proxy.port`** - OJP proxy port (default: `1059`)

### TestContainer Configuration

CockroachDB TestContainer settings are defined in:
- `CockroachDBTestContainer.java` - Singleton container manager
- `CockroachDBConnectionProvider.java` - Test parameterization provider
- `CockroachDBConnectionWithRecordCountsProvider.java` - Performance test provider

The TestContainer uses:
- **Image**: `cockroachdb/cockroach:v24.3.4`
- **Mode**: Single-node, insecure (for testing only)
- **Username**: `root`
- **Password**: (empty - insecure mode)
- **Database**: `defaultdb`
- **Port**: Randomly assigned by TestContainers

Note: CockroachDB uses the PostgreSQL wire protocol, so the JDBC URL uses `postgresql://` but connects to CockroachDB.

## Skipping CockroachDB Tests

CockroachDB tests are disabled by default. To explicitly skip them:

```bash
mvn test -pl ojp-jdbc-driver -DenableCockroachDBTests=false
```

Or simply omit the `-DenableCockroachDBTests=true` flag.

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

### TestContainers Issues

If TestContainers fails to start:

1. **Check Docker is running**:
   ```bash
   docker ps
   ```

2. **Check Docker disk space**:
   ```bash
   docker system df
   ```

3. **Clean up Docker resources**:
   ```bash
   docker system prune -a
   ```

### Connection Issues

If tests fail with connection errors:

1. **Check OJP server is running**:
   ```bash
   curl http://localhost:1059/health
   ```

2. **Check TestContainer logs** (in test output):
   Look for CockroachDB container startup messages

### Performance Issues

If tests are slow:

1. **Check Docker resources**: Ensure Docker has sufficient CPU/memory
2. **Use local Docker images**: Pull the CockroachDB image beforehand:
   ```bash
   docker pull cockroachdb/cockroach:v24.3.4
   ```

## Additional Resources

- [CockroachDB Official Documentation](https://www.cockroachlabs.com/docs/)
- [CockroachDB PostgreSQL Compatibility](https://www.cockroachlabs.com/docs/stable/postgresql-compatibility.html)
- [CockroachDB Docker Image](https://hub.docker.com/r/cockroachdb/cockroach)
- [TestContainers Documentation](https://www.testcontainers.org/)
- [TestContainers Generic Container](https://www.testcontainers.org/features/creating_container/)

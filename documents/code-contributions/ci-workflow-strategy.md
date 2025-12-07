# CI/CD Workflow Strategy

## Overview

The OJP project uses a **fail-fast CI/CD strategy** designed to optimize build cycles and provide rapid feedback to contributors. This approach ensures that expensive, time-consuming tests only run when basic functionality has been verified.

## Workflow Execution Order

### 1. Main CI (Primary Gate)
**Triggers:** Every push and pull request to the `main` branch

**What it does:**
- Builds all OJP components (ojp-grpc-commons, ojp-server, ojp-jdbc-driver)
- Runs comprehensive tests against **H2 database only**
- Tests across multiple Java versions (11, 17, 21, 22)
- Validates core functionality without external database dependencies

**Why H2 only?**
- **Speed**: H2 is an in-memory database that starts instantly and runs tests significantly faster than external databases
- **Fail-fast mechanism**: Catches major issues (compilation errors, API breaks, logic errors) before expensive database-specific tests run
- **Resource efficiency**: Reduces GitHub Actions runner time and costs
- **Developer experience**: Provides faster feedback loop for common issues

**Test flags used:**
```bash
mvn test -pl ojp-jdbc-driver -Dgpg.skip=true \
  -DdisablePostgresTests=true \
  -DdisableMySQLTests=true \
  -DdisableMariaDBTests=true \
  -DdisableCockroachDBTests=true
```

### 2. Dependent Workflows (Run After Main CI Success)

These workflows only execute when Main CI completes successfully. They can also be triggered manually via `workflow_dispatch`.

#### Multinode Integration Tests
- **Purpose**: Tests OJP server failover and high availability scenarios
- **Database**: PostgreSQL
- **Duration**: Longer running (15-20 minutes)
- **Trigger**: `workflow_run` on Main CI completion

#### Oracle Database Testing
- **Purpose**: Validates Oracle-specific functionality
- **Database**: Oracle XE 21
- **Duration**: Medium (10-15 minutes)
- **Special requirements**: Oracle JDBC driver dynamically added during CI
- **Trigger**: `workflow_run` on Main CI completion

#### SQL Server Integration Tests
- **Purpose**: Validates SQL Server-specific functionality
- **Database**: Microsoft SQL Server 2022 (via TestContainers)
- **Duration**: Medium (10-15 minutes)
- **Special requirements**: SQL Server JDBC driver dynamically added during CI
- **Trigger**: `workflow_run` on Main CI completion

## Benefits of This Strategy

### 1. **Reduced CI Costs**
- Expensive database containers only start when needed
- Failed builds detected early without running full test suite
- Estimated 60-70% reduction in unnecessary CI runs

### 2. **Faster Feedback**
- Developers get feedback in 5-8 minutes (H2 tests only) vs 20-30 minutes (full suite)
- Quick iteration on common issues
- Better developer experience

### 3. **Resource Optimization**
- GitHub Actions runners not wasted on tests that would fail anyway
- Parallel execution of independent workflows after Main CI passes
- Better utilization of GitHub Actions concurrent job limits

### 4. **Maintainability**
- Clear separation of concerns between workflows
- Easier to debug specific database issues
- Each workflow can be run independently for targeted testing

## Manual Workflow Execution

All dependent workflows can be manually triggered using GitHub's `workflow_dispatch` feature:

1. Navigate to Actions tab in GitHub
2. Select the workflow (e.g., "Oracle Database Testing")
3. Click "Run workflow"
4. Select branch and run

This is useful for:
- Testing specific database functionality
- Debugging workflow issues
- Running tests after Main CI fixes

## Workflow Configuration

### Main CI Success Check
Dependent workflows use this condition to only run after Main CI succeeds:

```yaml
jobs:
  job-name:
    runs-on: ubuntu-latest
    if: ${{ github.event.workflow_run.conclusion == 'success' || github.event_name == 'workflow_dispatch' }}
```

This ensures:
- Workflow runs only if Main CI succeeded
- OR workflow was manually triggered (workflow_dispatch)

## Test Flag System

All OJP integration tests support database-specific flags:

### Disable Flags (enabled by default)
- `-DdisableH2Tests` - Skip H2 tests
- `-DdisablePostgresTests` - Skip PostgreSQL tests
- `-DdisableMySQLTests` - Skip MySQL tests
- `-DdisableMariaDBTests` - Skip MariaDB tests
- `-DdisableCockroachDBTests` - Skip CockroachDB tests

### Enable Flags (disabled by default)
- `-DenableOracleTests` - Run Oracle tests
- `-DenableSqlServerTests` - Run SQL Server tests
- `-DenableDb2Tests` - Run DB2 tests

## Local Development

When developing locally, you can replicate the CI behavior:

### Run only H2 tests (fast):
```bash
cd ojp-jdbc-driver
mvn test -DdisablePostgresTests -DdisableMySQLTests -DdisableMariaDBTests -DdisableCockroachDBTests
```

### Run full test suite:
```bash
cd ojp-jdbc-driver
mvn test
```
_Note: Requires all databases running locally. See [Run Local Databases](../environment-setup/run-local-databases.md)_

### Run specific database tests:
```bash
# Only PostgreSQL
mvn test -DdisableMySQLTests -DdisableMariaDBTests -DdisableCockroachDBTests -DdisableH2Tests

# Only MySQL
mvn test -DdisablePostgresTests -DdisableMariaDBTests -DdisableCockroachDBTests -DdisableH2Tests
```

## Workflow Files

- **Main CI**: `.github/workflows/main.yml`
- **Multinode Tests**: `.github/workflows/multinode-integration.yml`
- **Oracle Tests**: `.github/workflows/oracle-testing.yml`
- **SQL Server Tests**: `.github/workflows/sqlserver-testing.yml`
- **Docker Build**: `.github/workflows/docker-build.yml` (manual only)

## Future Improvements

Potential enhancements to the CI/CD strategy:

1. **Parallel Database Tests**: Run Oracle, SQL Server, and Multinode tests in parallel after Main CI
2. **Conditional Workflows**: Only run database-specific tests if related code changed
3. **Test Result Caching**: Cache test results for unchanged code
4. **Performance Benchmarks**: Add automated performance regression tests
5. **Matrix Strategy**: Expand database version matrix testing

## Questions or Issues?

For questions about the CI/CD strategy or workflow issues, please:
1. Check existing [GitHub Issues](https://github.com/Open-J-Proxy/ojp/issues)
2. Open a new issue with the `ci/cd` label
3. Join our [Discord community](https://discord.gg/J5DdHpaUzu)

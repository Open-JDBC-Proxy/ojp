# CI/CD Workflow Structure

## Overview

The OJP project uses a multi-stage CI/CD workflow structure designed to optimize CI resource usage while maintaining comprehensive test coverage across multiple database systems.

## Workflow Execution Order

### 1. Main CI (Fail-Fast with H2)
**Purpose:** Quick feedback and fail-fast mechanism

The Main CI workflow is the first to run and serves as a gatekeeper for all other workflows. It focuses exclusively on H2 database tests, which are:
- **Fast**: H2 is an in-memory database that starts instantly
- **Lightweight**: No external database containers required
- **Comprehensive**: Tests core JDBC functionality that applies across all databases
- **Cost-effective**: Minimal CI resource consumption

**What it does:**
- Builds all modules (ojp-grpc-commons, ojp-server, ojp-jdbc-driver)
- Runs H2 integration tests only using `-DenableH2Tests=true`
- Tests across multiple Java versions (11, 17, 21, 22)
- Acts as a quality gate - if H2 tests fail, other workflows won't run

**Rationale:**
If there are fundamental issues with the code (compilation errors, basic JDBC contract violations, major bugs), they will be caught quickly in the Main CI without wasting resources running the full test suite across all databases.

### 2. Database-Specific Integration Tests (Run After Main CI)
**Purpose:** Comprehensive database compatibility validation

These workflows only execute after the Main CI succeeds:

- **MySQL Integration Tests**
- **PostgreSQL Integration Tests**
- **MariaDB Integration Tests**
- **CockroachDB Integration Tests**
- **Oracle Integration Tests**
- **SQL Server Integration Tests**
- **DB2 Integration Tests**

Each workflow:
- Uses TestContainers to manage database lifecycle
- Tests database-specific features and behaviors
- Runs in parallel (independently from each other)
- Can be triggered manually via `workflow_dispatch` for debugging

**Trigger conditions:**
- **On push to main**: Only runs if Main CI succeeds
- **On pull request**: Runs independently for PR validation
- **Manual trigger**: Can be run anytime via workflow_dispatch

### 3. Multinode Integration Tests (Run After Main CI)
**Purpose:** Tests distributed OJP server scenarios

This workflow:
- Tests multiple OJP server instances working together
- Validates failover and recovery mechanisms
- Only runs after Main CI succeeds

## Benefits of This Structure

### Resource Optimization
- **Fast Feedback**: Developers get quick feedback from H2 tests (~5-10 minutes)
- **Reduced Waste**: Database-specific tests only run if basic functionality passes
- **Parallel Execution**: Database tests run in parallel after Main CI succeeds

### Cost Savings
- **Fewer Failed Runs**: H2 tests catch issues before expensive database tests start
- **Efficient CI Usage**: Only run full suite when code is likely to pass
- **Lower Resource Consumption**: H2 tests use minimal resources compared to containerized databases

### Developer Experience
- **Clear Feedback**: If tests fail on H2, fix those first before investigating database-specific issues
- **Faster Iterations**: Quick H2 feedback loop during development
- **Targeted Testing**: Can manually trigger specific database workflows for debugging

## Testing Flags

All test workflows use standardized flags to control which database tests run:

- `-DenableH2Tests=true/false` - Enable/disable H2 tests (default: true)
- `-DenablePostgresTests=true/false` - Enable/disable PostgreSQL tests (default: false)
- `-DenableMySQLTests=true/false` - Enable/disable MySQL tests (default: false)
- `-DenableMariaDBTests=true/false` - Enable/disable MariaDB tests (default: false)
- `-DenableCockroachDBTests=true/false` - Enable/disable CockroachDB tests (default: false)
- `-DenableOracleTests=true/false` - Enable/disable Oracle tests (default: false)
- `-DenableSqlServerTests=true/false` - Enable/disable SQL Server tests (default: false)
- `-DenableDb2Tests=true/false` - Enable/disable DB2 tests (default: false)

## Local Development

For local development, developers can:

1. **Run only H2 tests** (fastest):
   ```bash
   mvn test -pl ojp-jdbc-driver -DenableH2Tests=true -DenablePostgresTests=false -DenableMySQLTests=false
   ```

2. **Run specific database tests**:
   ```bash
   mvn test -pl ojp-jdbc-driver -DenableH2Tests=false -DenablePostgresTests=true -DenableMySQLTests=false
   ```

3. **Run all tests** (requires all databases running locally):
   ```bash
   mvn test -pl ojp-jdbc-driver -DenableH2Tests=true -DenablePostgresTests=true -DenableMySQLTests=true -DenableMariaDBTests=true
   ```

See [Setup and Testing Guide](code-contributions/setup_and_testing_ojp_source.md) for more details.

## Workflow Diagram

```
                         ┌─────────────┐
                         │   Push to   │
                         │   main or   │
                         │     PR      │
                         └──────┬──────┘
                                │
                                ▼
                         ┌─────────────┐
                         │   Main CI   │
                         │ (H2 Tests)  │
                         └──────┬──────┘
                                │
                      ┌─────────┴─────────┐
                      │                   │
                  Success              Failure
                      │                   │
                      ▼                   ▼
          ┌───────────────────┐    Workflows Stop
          │ Database-Specific │    (No further tests)
          │   Integration     │
          │      Tests        │
          │   (in parallel)   │
          └───────────────────┘
                      │
          ┌───────────┼───────────┐
          │           │           │
          ▼           ▼           ▼
      ┌───────┐  ┌───────┐  ┌───────┐
      │ MySQL │  │ Postgres│ │MariaDB│
      └───────┘  └───────┘  └───────┘
          │           │           │
      ┌───▼───┐  ┌───▼───┐  ┌───▼───┐
      │Oracle │  │ SQLSvr│  │  DB2  │
      └───────┘  └───────┘  └───────┘
          │           │           │
      ┌───▼───────┐  └──┬────────┘
      │CockroachDB│     │
      └───────────┘     │
                        │
                        ▼
                ┌───────────────┐
                │   Multinode   │
                │    Tests      │
                └───────────────┘
```

## Maintenance

When adding new database support:
1. Create a new workflow file in `.github/workflows/`
2. Add workflow_run trigger to depend on Main CI
3. Use the standardized test flag pattern
4. Update this documentation

When modifying test behavior:
1. Ensure H2 tests cover core functionality
2. Keep H2 tests fast and focused
3. Database-specific tests can be more comprehensive
4. Update test flags as needed

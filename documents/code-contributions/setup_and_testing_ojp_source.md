
### Prerequisites
- Java 22 or higher
- Maven 3.9+
- Docker (for running databases and OJP server)

### Getting Started

1. **Clone the repository**
   ```bash
   git clone https://github.com/Open-J-Proxy/ojp.git
   cd ojp
   ```

2. **Build the project**
   ```bash
   mvn clean install -DskipTests
   ```

3. **Start OJP server** (required for running tests)
   ```bash
   mvn verify -pl ojp-server -Prun-ojp-server
   ```

4. **Run integration tests**
   Navigate to the ojp-jdbc-driver folder first:
   ```bash
   cd ojp-jdbc-driver
   mvn test -DdisablePostgresTests -DdisableMySQLTests -DenableMariaDBTests=false -DenableCockroachDBTests=false -DdisablePostgresXATests
   ```
**Note:** With the disable flags only H2 integration tests will run, to run the full set of integration tests you have to run all the databases locally, follow the instructions at [Run Local Databases](../../documents/environment-setup/run-local-databases.md)

### Databases with integration tests
We have comprehensive JDBC integration tests with OJP for the following databases:
- Postgres
- MariaDB
- MySQL
- CockroachDB
- Oracle
- SQL Server
- DB2
- H2

The free and open source databases (H2, Postgres, MySQL and CockroachDB) JDBC drivers are packed with OJP and have integration tests always running in our CI pipelines. MariaDB tests use TestContainers and are disabled by default (see [MariaDB Testing Guide](../../documents/environment-setup/mariadb-testing-guide.md)). For proprietary databases like Oracle, SQL Server, and DB2, see their specific sections.

### Oracle Database Setup (Optional)
Oracle integration tests require the Oracle JDBC driver and due to licensing restrictions we do not pack it with OJP.
For detailed Oracle setup instructions, see [Oracle Testing Guide](../../documents/environment-setup/oracle-testing-guide.md).

### SQL Server Database Setup (Optional)
SQL Server integration tests use the Microsoft SQL Server JDBC driver which is not included in OJP dependencies.
For detailed SQL Server setup instructions, see [SQL Server Testing Guide](../../documents/environment-setup/sqlserver-testing-guide.md).

### DB2 Database Setup (Optional)
DB2 integration tests use the IBM JDBC driver which is not included in OJP dependencies.
For detailed DB2 instructions, see [DB2 Testing Guide](../../documents/environment-setup/db2-testing-guide.md).

### MariaDB Database Setup (Optional)
MariaDB integration tests use TestContainers to automatically manage the database lifecycle. Tests are disabled by default.
For detailed MariaDB setup instructions, see [MariaDB Testing Guide](../../documents/environment-setup/mariadb-testing-guide.md).

### CockroachDB Database Setup (Optional)
CockroachDB integration tests use TestContainers which automatically manages the CockroachDB instance.
For detailed CockroachDB setup instructions, see [CockroachDB Testing Guide](../../documents/environment-setup/cockroachdb-testing-guide.md).


### Testing Configuration
- Test connection configurations are stored in CSV files under `test/resources`
- File naming indicates supported databases (e.g., `h2_postgres_connections.csv` supports H2 and PostgreSQL)
- Integration tests run against each configured connection
- See [run-local-databases.md](documents/environment-setup/run-local-databases.md) for local database setup

### Test Options
- `-DdisablePostgresTests` - Skip PostgreSQL integration tests
- `-DdisableMySQLTests` - Skip MySQL integration tests
- `-DenableMariaDBTests` - Enable MariaDB integration tests (disabled by default, uses TestContainers)
- `-DenableCockroachDBTests` - Enable CockroachDB integration tests (disabled by default, uses TestContainers)
- `-DenableOracleTests` - Enable Oracle integration tests (disabled by default, requires manual Oracle JDBC driver setup)
- `-DenableSqlServerTests` - Enable SQL Server integration tests (disabled by default, uses TestContainers)
- `-DenableDb2Tests` - Enable DB2 integration tests (disabled by default, uses TestContainers)

### Contributing code
1. Fork the repository
2. Create a feature branch
3. Make your changes with tests
4. Ensure all tests pass
5. Submit a pull request

For questions or support, please open an issue on GitHub.

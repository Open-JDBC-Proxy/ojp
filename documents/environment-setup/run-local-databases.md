## Run postgres on docker

### Preconditions
Have docker installed in your machine.

### Run command

> docker run --name ojp-postgres -e POSTGRES_USER=testuser -e POSTGRES_PASSWORD=testpassword -e POSTGRES_DB=defaultdb -d -p 5432:5432 postgres:17 

#### docker run
Tells Docker to run a new container.

#### --name ojp-postgres
Assigns the name ojp-postgres to the container (makes it easier to manage and reference).

#### -e POSTGRES_USER=testuser
Sets the database username to 'testuser'.

#### -e POSTGRES_PASSWORD=testpassword
Sets the database user password to 'testpassword'.

#### -e POSTGRES_DB=defaultdb
Creates a default database named 'defaultdb' on startup.

#### -d
Runs the container in detached mode (in the background).

#### -p 5432:5432
Maps port 5432 of your host to port 5432 of the container (PostgreSQL’s default port), allowing local access.

#### postgres:17
Specifies the Docker image to use (in this case, the official PostgreSQL image version 17 from Docker Hub).

---

## Run mysql on docker

### Preconditions
Have docker installed in your machine.

### Run command

> docker run --name ojp-mysql -e MYSQL_ROOT_PASSWORD=testpassword -e MYSQL_DATABASE=defaultdb -e MYSQL_USER=testuser -e MYSQL_PASSWORD=testpassword -d -p 3306:3306 mysql:8.0

#### docker run
Tells Docker to run a new container.

#### --name ojp-mysql
Assigns the name ojp-mysql to the container (makes it easier to manage and reference).

#### -e MYSQL_ROOT_PASSWORD=testpassword
Sets the root password for MySQL (required by the image).

#### -e MYSQL_DATABASE=defaultdb
Creates a default database named 'defaultdb' on startup.

#### -e MYSQL_USER=testuser
Creates a new user named 'testuser'.

#### -e MYSQL_PASSWORD=testpassword
Sets the password for 'testuser'.

#### -d
Runs the container in detached mode (in the background).

#### -p 3307:3306
Maps port 3307 of your host to port 3306 of the container (**3307** to not conflict with MySQL’s default port), allowing local access.

#### mysql:8.0
Specifies the Docker image to use (in this case, the official MySQL image version 8.0 from Docker Hub).


## Run MariaDB on Docker

### Preconditions
Have Docker installed on your machine.

### Run Command

> docker run --name ojp-mariadb -e MARIADB_ROOT_PASSWORD=testpassword -e MARIADB_DATABASE=defaultdb -e MARIADB_USER=testuser -e MARIADB_PASSWORD=testpassword -d -p 3307:3306 mariadb:10.11

#### docker run
Tells Docker to run a new container.

#### --name ojp-mariadb
Assigns the name `ojp-mariadb` to the container, making it easier to manage and reference.

#### -e MARIADB_ROOT_PASSWORD=testpassword
Sets the root password for MariaDB (required by the image).

#### -e MARIADB_DATABASE=defaultdb
Creates a default database named `defaultdb` on startup.

#### -e MARIADB_USER=testuser
Creates a new user named `testuser`.

#### -e MARIADB_PASSWORD=testpassword
Sets the password for the `testuser`.

#### -d
Runs the container in detached mode (in the background).

#### -p 3307:3306
Maps port 3307 of your host to port 3306 of the container (MariaDB’s default port), allowing local access.

#### mariadb:10.11
Specifies the Docker image to use (in this case, the 10.11 official MariaDB image from Docker Hub).

---

## Run Oracle on Docker

### Preconditions
Have Docker installed on your machine.

### Run Command

> docker run --name ojp-oracle -e ORACLE_PASSWORD=testpassword -e APP_USER=testuser -e APP_USER_PASSWORD=testpassword -d -p 1521:1521 gvenzl/oracle-xe:21-slim

#### docker run
Tells Docker to run a new container.

#### --name ojp-oracle
Assigns the name `ojp-oracle` to the container, making it easier to manage and reference.

#### -e ORACLE_PASSWORD=testpassword
Sets the password for the SYS and SYSTEM users (required by the image).

#### -e APP_USER=testuser
Creates a new application user named `testuser`.

#### -e APP_USER_PASSWORD=testpassword
Sets the password for the `testuser`.

#### -d
Runs the container in detached mode (in the background).

#### -p 1521:1521
Maps port 1521 of your host to port 1521 of the container (Oracle's default port), allowing local access.

#### gvenzl/oracle-xe:21-slim
Specifies the Docker image to use (in this case, the community Oracle XE 21c image from Docker Hub). This is a lightweight, license-free Oracle Express Edition suitable for development and testing.

---

## Run SQL Server on Docker

### Preconditions
Have Docker installed on your machine.

### Run Command

> docker run --name ojp-sqlserver -e ACCEPT_EULA=Y -e SA_PASSWORD=TestPassword123! -d -p 1433:1433 mcr.microsoft.com/mssql/server:2022-latest

#### docker run
Tells Docker to run a new container.

#### --name ojp-sqlserver
Assigns the name `ojp-sqlserver` to the container, making it easier to manage and reference.

#### -e ACCEPT_EULA=Y
Accepts the End-User License Agreement for SQL Server (required by Microsoft).

#### -e SA_PASSWORD=TestPassword123!
Sets the password for the SA (System Administrator) user. Must meet SQL Server password complexity requirements.

#### -d
Runs the container in detached mode (in the background).

#### -p 1433:1433
Maps port 1433 of your host to port 1433 of the container (SQL Server's default port), allowing local access.

#### mcr.microsoft.com/mssql/server:2022-latest
Specifies the Docker image to use (in this case, the official Microsoft SQL Server 2022 image from Microsoft Container Registry).

### Create Test Database and User (Optional)

After the container starts, you can create a test database and user:

```bash
# Connect to SQL Server (using sqlcmd directly - it's in the PATH)
docker exec -it ojp-sqlserver sqlcmd -S localhost -U sa -P TestPassword123!

# Create database and user
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

> **Note**: If the above `sqlcmd` command doesn't work, try these alternatives:
> ```bash
> # Alternative 1: Use the newer tools path
> docker exec -it ojp-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P TestPassword123! -C
> 
> # Alternative 2: Use bash to access sqlcmd
> docker exec -it ojp-sqlserver bash -c "sqlcmd -S localhost -U sa -P TestPassword123!"
> ```

---

## Run IBM DB2 on Docker

### Preconditions
Have Docker installed on your machine.

### Run Command

> docker run -d --name ojp-db2   --privileged   -p 50000:50000 -m 6g  -e LICENSE=accept   -e DB2INSTANCE=db2inst1   -e DB2INST1_PASSWORD=testpass   -e DBNAME=testdb   ibmcom/db2:11.5.8.0

NOTE: DB2 might take several minutes to start, you can follow the evolution in the logs running the command:

> docker logs ojp-db2 -f

#### docker run
Tells Docker to run a new container.

#### -d
Runs the container in detached mode (in the background).

#### -m 
Limits container memory to 6GB.

#### --name ojp-db2
Assigns the name `ojp-db2` to the container, making it easier to manage and reference.

#### --privileged=true
Grants extended privileges to the container (required by IBM DB2 for proper initialization).

#### -e LICENSE=accept
Accepts the IBM DB2 End-User License Agreement (required by IBM).

#### -e DB2INSTANCE=db2inst1
Sets the DB2 instance name to `db2inst1` (the default instance name).

#### -e DB2INST1_PASSWORD=testpassword
Sets the password for the DB2 instance user `db2inst1`.

#### -e DBNAME=defaultdb
Creates a default database named `defaultdb` on startup.

#### -d
Runs the container in detached mode (in the background).

#### -p 50000:50000
Maps port 50000 of your host to port 50000 of the container (DB2's default port), allowing local access.

#### ibmcom/db2:11.5.8.0
Specifies the Docker image to use (in this case, the official IBM DB2 Community Edition image version 11.5.8.0 from Docker Hub).

### Database Startup
DB2 takes several minutes to fully initialize. You can monitor the startup process with:

```bash
docker logs ojp-db2
```

Wait for the message indicating that DB2 is ready to accept connections before attempting to connect.
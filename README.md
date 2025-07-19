<img src="documents/images/ojp_rectangular_large.png" alt="Comunidade Brasil JUG" />

# Open JDBC Proxy

A JDBC Driver and Layer 7 Proxy Server to decouple applications from relational database connection management.

[!["Buy Me A Coffee"](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://buymeacoffee.com/wqoejbve8z)

## Questions we aim to answer:
#### How to autoscale our applications without overloading our relational database(s) with new connections?
#### How to replace native JDBC drivers seamlessly?
#### How to support multiple relational databases at once?

## Documentation

- [Architectural decision records (ADRs)](documents/ADRs)
- [Get started: Spring Boot, Quarkus and Micronaut](documents/java-frameworks)
- [Connection Pool Configuration](documents/configuration/CONNECTION_POOL_CONFIG.md)
- [OJP Server Configuration](documents/configuration/ojp-server-configuration.md)
- [Telemetry and Observability](documents/telemetry/README.md)

## High Level Design

<img src="documents/designs/ojp_high_level_design.gif" alt="OJP High Level Design" />


* The OJB JDBC driver is used as a replacement for the native JDBC driver(s) previously used with minimum change, the only change required being prefixing the connection URL with ojp_. For example: 
```
ojp_[localhost:1059]postgresql://user@localhost
```
instead of:
```
postgresql://user@localhost
```
* **Open Source**: OJP is an open-source project that is free to use, modify, and distribute.
* The OJP server is deployed as an independent service sitting and will serve as a smart proxy between the application(s) and their respective relational database(s) controlling the number of connections open against each database.
* **Smart Connection Management***: The proxy ensures that database connections are allocated only when needed, improving scalability and resource utilization. In example below, only when executeQuery is called a real connection is enlisted to execute the operation, reducing the time that connection is hold and allowing for it to be used by other clients meanwhile:
```
        Connection conn = DriverManager.
                getConnection("jdbc:ojp[host:port]_h2:~/test", "sa", "");

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from test_table where id = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery(); <--- *Real connection allocation*
        
        ...
```
* **Elastic Scalability**: OJP allows client applications to scale elastically without increasing the pressure on the database.
* **GRPC protocol** is used to facilitate the connection of the OJP JDBC Driver and the OJP Proxy Server allowing for efficient data transmission over a multiplex channel.
* OJP Proxy server uses **HikariCP** connection pools to efficiently manage connections.
* OJP supports **multiple relational databases**, in theory it can support any relational database that currently provides a JDBC driver implementation.
* OJP simple setup just requires OJP lib to be in the classpath and  the OJP prefix to be added to the connection URL as in jdbc:ojp[host:port]_h2:~/test. where host:port represents the location of the proxy server.
 
## Vision
Provide a free and open-source solution for a relational database-agnostic proxy connection pool. The project is designed to help efficiently manage database connections in microservices, event-driven architectures, or serverless environments while maintaining high scalability and performance.

## Target problem
In modern architectures, such as microservices, event-driven systems, or serverless (Lambda) architectures, a common issue arises in managing the number of open connections to relational databases. When applications need to elastically scale, they often maintain too many database connections. These connections can be held for longer than necessary, locking resources and making scalability difficult. In some cases, this can lead to excessive resource consumption, placing immense pressure on the database. In extreme scenarios, this can even result in database outages.

## The solution
OJP provides a smart proxy to solve this problem by dynamically managing database connections. Rather than keeping connections open continuously, OJP only allocates real database connections when an operation is performed. The proxy ensures that resources are used efficiently by allocating connections only when truly necessary. For example, a real connection to the database is established only when an actual operation (e.g., a query or update) is performed, thus optimizing resource usage and ensuring better scalability.
This intelligent allocation of connections helps prevent overloading databases and ensures that the number of open connections remains efficient, even during heavy elastic scaling of applications.

## Components

### ojp-server
The ojp-server is a gRPC server that manages a Hikari connection pool and abstracts the creation and management of database connections. It supports one or multiple relational databases and provides virtual connections to the ojp-jdbc-driver. The server ensures the number of open real connections is always under control, according to predefined settings, improving database scalability.

#### How to start a docker image

> docker run --rm -d -p 1059:1059 rrobetti/ojp:0.0.4-alpha

### ojp-jdbc-driver
The ojp-jdbc-driver is an implementation of the JDBC specification. It connects to the ojp-server via the gRPC protocol, sending SQL statements to be executed against the database and reading the responses. The driver works with virtual connections provided by the ojp-server, allowing the application to interact with the database without directly managing real database connections.

Latest version:

         <dependency>
             <groupId>org.openjdbcproxy</groupId>
             <artifactId>ojp-jdbc-driver</artifactId>
             <version>0.0.4-alpha</version>
         </dependency>


#### Disable your connection pool

### ojp-grpc-commons
The ojp-grpc-commons module contains the shared gRPC contracts used between the ojp-server and ojp-jdbc-driver. These contracts define the communication protocol and structure for requests and responses exchanged between the server and the driver.

## How to build & test

From the root of the project, follow these 3 steps to build and test the project:

### 1. Build modules

``mvn clean install -DskipTests``

### 2. Run ojp-server

This step is required to run tests.

``mvn verify -pl ojp-server -Prun-ojp-server``

### 3. Run tests
Connections configuration: There are csv files under test/resources with connection details defaulted to H2 database, the name of each file implies which database connections can be added to it, for example the file h2_postgres_connections.csv can contain connections to H2 and/or postgres databases, integration tests classes that relly on this file will run all their tests against each connection in the file.

Refer to the [run-local-databases.md](documents/environment-setup/run-local-databases.md) for more details on how to start local databases for testing.

``mvn test``

Optional flags:
- `-DdisablePostgresTests` to skip integration test that require a Postgres database.

## Partners
<a href=https://www.linkedin.com/in/devsjava/>
<img width="150px" height="150px" src="documents/images/comunidade_brasil_jug.jpeg" alt="Comunidade Brasil JUG" />
</a>
<a href=https://github.com/switcherapi>
<img width="180px" src="documents/images/switcherapi_grey.png" alt="Comunidade Brasil JUG" />
</a>

## Feature implementation status
- ✅ Basic CRUD operations.
- ✅ Streamed result set reading.
- ✅ BLOB support.
- ✅ Transactions support.
- ✅ Binary Stream support.
- ✅ ResultSet metadata enquiring.
- ❌ CLOB support.
- ✅ Statement and Prepared statement advanced features.
- ✅ Connection advanced features.
- ✅ OpenTelemetry implementation.
- ✅ Circuit Breaker.
- ❌ Slow queries segregation.
- ✅ Docker image implementation.
- ✅ Support for Spring Boot/Spring Data.
- ✅ Support for Micronaut.
- ✅ Support for Quarkus.
- ❌ BLOB and CLOB advanced features.
- ✅ Configurable data sources. 
- ❌ RAFT consensus POC.
- ❌ RAFT and connection smart balancing and resizing.


#### Other feature candidates: 
Query Routing, Sharding, Query Caching, Read/Write Splitting, Multi-Cloud/Distributed Clustering, Authentication Integration, Advanced Security Features, Failover and Automatic Replication Awareness, Helidon support.

✅ - Done
❌ - Not started
🕓 - In progress
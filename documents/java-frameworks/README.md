# Introduction
Each folder under this directory have detailed documentation on how to integrate OJP on different frameworks.

- [Spring Boot](spring-boot/README.md)
- [Quarkus](quarkus/README.md)
- [Micronaut](micronaut/README.md)
- [Jakarta EE](jakarta-ee/README.md)

Note that the steps are always similar and follow 3 basic steps:

1. Modify your connection URL to OJP pattern.
2. Remove your current connection pool from the project. OJP will take the connection pooling work over.
3. Add OJP jdbc driver dependency to your project.

Enjoy OJP!
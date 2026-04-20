# Quarkus

To integrate OJP into your Quarkus project follow the steps:

## 1 Add the maven dependency to your project.
```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>[TBD]</version>
</dependency>
```

## 2 Disable quarkus default connection pool

```properties 
## Use unpooled datasource 

quarkus.datasource.jdbc=true
quarkus.datasource.jdbc.unpooled=true
```

## 3 Change your connection URL
In your `application.properties` (or `application.yml`) file, update your database connection URL, and add the OJP jdbc driver class as in the following example:
```properties
quarkus.datasource.jdbc.url=jdbc:ojp[localhost:1059]_h2:mem:shopdb
quarkus.datasource.jdbc.driver=org.openjproxy.jdbc.Driver
```

The example above is for `h2` but it is similar to any other database, you just need to add the `ojp[host:port]_` pattern immediately after `jdbc:`. `[host:port]` indicates the host and port you have your OJP proxy server running.

> **Note:** The Quarkus connection URL (`quarkus.datasource.jdbc.url`) and driver class are
> configured in `application.properties` or `application.yml` as shown above. OJP driver-specific
> settings (connection pool sizes, health check intervals, multinode retry configuration, etc.)
> must be provided separately in an `ojp.properties` file (or an environment-specific variant such
> as `ojp-dev.properties`).
>
> See [OJP JDBC Configuration](../../configuration/ojp-jdbc-configuration.md) for the full list of
> `ojp.properties` settings.

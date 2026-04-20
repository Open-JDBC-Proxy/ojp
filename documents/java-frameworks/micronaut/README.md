# Micronaut

To integrate OJP into your Micronaut project follow the steps:

## 1 Add the maven dependency to your project.
```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>[TBD]</version>
</dependency>
```

## 2 Disable quarkus default connection pool

### Remove HikariCP maven dependency
```xml
<dependency>
    <groupId>io.micronaut.sql</groupId>
    <artifactId>micronaut-jdbc-hikari</artifactId>
</dependency>
```

### Create a new DataSourceFactory
```java
    import io.micronaut.context.annotation.Factory;
    import io.micronaut.context.annotation.Value;
    import jakarta.inject.Named;
    import jakarta.inject.Singleton;
    
    import javax.sql.DataSource;
    import java.sql.Connection;
    import java.sql.DriverManager;
    import java.sql.SQLException;

    @Factory
    public class DataSourceFactory {
    @Singleton
    @Named("default")
    public DataSource dataSource(
        @Value("${datasources.default.url}") String url,
        @Value("${datasources.default.username}") String user,
        @Value("${datasources.default.password}") String password,
        @Value("${datasources.default.driver-class-name}") String driver
    ) throws ClassNotFoundException {
        Class.forName(driver);//Guarantees that the OJP driver is registered with the DriverManager.

        DataSource ds = new DataSource() {
                @Override
                public Connection getConnection() throws SQLException {
                    return DriverManager.getConnection(url, user, password);
                }
    
                @Override
                public Connection getConnection(String username, String password) throws SQLException {
                    return DriverManager.getConnection(url, username, password);
                }
    
                // The following methods can be left as default or throw UnsupportedOperationException
                @Override
                public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
                @Override
                public boolean isWrapperFor(Class<?> iface) { throw new UnsupportedOperationException(); }
                @Override
                public java.io.PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
                @Override
                public void setLogWriter(java.io.PrintWriter out) { throw new UnsupportedOperationException(); }
                @Override
                public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
                @Override
                public int getLoginTimeout() { throw new UnsupportedOperationException(); }
                @Override
                public java.util.logging.Logger getParentLogger() { throw new UnsupportedOperationException(); }
            };

            return ds;
        }
    }
```

## 3 Change your connection URL
In your `application.properties` (or `application.yml`) file, update your database connection URL, and add the OJP jdbc driver class as in the following example:
```properties
datasources.default.url=jdbc:ojp[localhost:1059]_h2:mem:shopdb
datasources.default.driver-class-name=org.openjproxy.jdbc.Driver
jpa.default.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect
```

The example above is for `h2` but it is similar to any other database, you just need to add the `ojp[host:port]_` pattern immediately after `jdbc:`. `[host:port]` indicates the host and port you have your OJP proxy server running.
Note that `jpa.default.properties.hibernate.dialect` has to be present.

> **Note:** The Micronaut datasource URL and driver class are configured in `application.properties`
> or `application.yml` as shown above. OJP driver-specific settings (connection pool sizes, health
> check intervals, multinode retry configuration, etc.) must be provided separately in an
> `ojp.properties` file (or an environment-specific variant such as `ojp-dev.properties`).
>
> See [OJP JDBC Configuration](../../configuration/ojp-jdbc-configuration.md) for the full list of
> `ojp.properties` settings.
package org.openjproxy.grpc.server.utils;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import org.openjproxy.grpc.server.utils.DriverLoader.DriverShim;

import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Enumeration;

import static org.openjproxy.grpc.server.Constants.H2_DRIVER_CLASS;
import static org.openjproxy.grpc.server.Constants.MARIADB_DRIVER_CLASS;
import static org.openjproxy.grpc.server.Constants.MYSQL_DRIVER_CLASS;
import static org.openjproxy.grpc.server.Constants.ORACLE_DRIVER_CLASS;
import static org.openjproxy.grpc.server.Constants.POSTGRES_DRIVER_CLASS;
import static org.openjproxy.grpc.server.Constants.SQLSERVER_DRIVER_CLASS;
import static org.openjproxy.grpc.server.Constants.DB2_DRIVER_CLASS;

@Slf4j
@UtilityClass
public class DriverUtils {

    /**
     * Register all JDBC drivers supported and report their availability status.
     * This checks if the driver can be loaded via Class.forName() OR if it's registered with DriverManager.
     * @param driversPath Optional path to external libraries directory for user guidance in error messages
     */
    public void registerDrivers(String driversPath) {
        String driverPathMessage = (driversPath != null && !driversPath.trim().isEmpty())
            ? driversPath
            : "./ojp-libs";

        //Check open source drivers
        checkDriver(H2_DRIVER_CLASS, "H2",
            "https://mvnrepository.com/artifact/com.h2database/h2", "h2-*.jar", driverPathMessage);
        checkDriver(POSTGRES_DRIVER_CLASS, "PostgreSQL",
            "https://mvnrepository.com/artifact/org.postgresql/postgresql", "postgresql-*.jar", driverPathMessage);
        checkDriver(MYSQL_DRIVER_CLASS, "MySQL",
            "https://mvnrepository.com/artifact/com.mysql/mysql-connector-j", "mysql-connector-j-*.jar", driverPathMessage);
        checkDriver(MARIADB_DRIVER_CLASS, "MariaDB",
            "https://mvnrepository.com/artifact/org.mariadb.jdbc/mariadb-java-client", "mariadb-java-client-*.jar", driverPathMessage);

        //Check proprietary drivers (if present)
        checkDriver(ORACLE_DRIVER_CLASS, "Oracle",
            "https://www.oracle.com/database/technologies/jdbc-downloads.html", "ojdbc*.jar", driverPathMessage);
        checkDriver(SQLSERVER_DRIVER_CLASS, "SQL Server",
            "https://learn.microsoft.com/en-us/sql/connect/jdbc/download-microsoft-jdbc-driver-for-sql-server", "mssql-jdbc-*.jar", driverPathMessage);
        checkDriver(DB2_DRIVER_CLASS, "DB2",
            "IBM website", "db2jcc*.jar", driverPathMessage);
    }

    /**
     * Check if a driver is available either via Class.forName() or DriverManager.
     * This method checks both the main classpath and drivers registered with DriverManager
     * (which includes drivers loaded via URLClassLoader and wrapped in DriverShim).
     */
    private void checkDriver(String driverClass, String driverName,
                            String downloadUrl, String jarName, String driverPath) {
        boolean found = false;

        // First try Class.forName() - works for drivers in the main classpath
        try {
            Class.forName(driverClass);
            found = true;
        } catch (ClassNotFoundException e) {
            // Driver not in main classpath, check if it's registered with DriverManager
            // by iterating through all registered drivers and checking their class name.
            // Note: Drivers loaded via DriverLoader are wrapped in DriverShim.
            Enumeration<Driver> drivers = DriverManager.getDrivers();
            while (drivers.hasMoreElements() && !found) {
                Driver driver = drivers.nextElement();
                // Check if this is our target driver or a DriverShim wrapping it
                if (driver.getClass().getName().equals(driverClass)) {
                    found = true;
                    break;
                } else if (driver instanceof DriverShim) {
                    DriverShim shim = (DriverShim) driver;
                    if (shim.getWrappedDriverClassName().equals(driverClass)) {
                        found = true;
                        break;
                    }
                }
            }
        }

        if (found) {
            log.info("{} JDBC driver loaded successfully", driverName);
        } else {
            log.info("{} JDBC driver not found. To use {} databases:", driverName, driverName);
            log.info("  1. Download {} from {}", jarName, downloadUrl);
            log.info("  2. Place it in: {}", driverPath);
            log.info("  3. Restart OJP Server");
        }
    }

    /**
     * Register all JDBC drivers supported without path information.
     * @deprecated Use {@link #registerDrivers(String)} instead to provide better error messages
     */
    @Deprecated
    public void registerDrivers() {
        // Delegate to main method with default path
        registerDrivers(null);
    }
}

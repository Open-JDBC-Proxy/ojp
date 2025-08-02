package org.openjdbcproxy.grpc.server.utils;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import static org.openjdbcproxy.grpc.server.Constants.H2_DRIVER_CLASS;
import static org.openjdbcproxy.grpc.server.Constants.MARIADB_DRIVER_CLASS;
import static org.openjdbcproxy.grpc.server.Constants.MYSQL_DRIVER_CLASS;
import static org.openjdbcproxy.grpc.server.Constants.ORACLE_DRIVER_CLASS;
import static org.openjdbcproxy.grpc.server.Constants.POSTGRES_DRIVER_CLASS;
import static org.openjdbcproxy.grpc.server.Constants.SQLSERVER_DRIVER_CLASS;
import static org.openjdbcproxy.grpc.server.Constants.DB2_DRIVER_CLASS;

@Slf4j
@UtilityClass
public class DriverUtils {
    /**
     * Register all JDBC drivers supported.
     */
    public void registerDrivers() {
        //Register open source drivers
        try {
            Class.forName(H2_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            log.error("Failed to register H2 JDBC driver.", e);
        }
        try {
            Class.forName(POSTGRES_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            log.error("Failed to register PostgreSQL JDBC driver.", e);
        }
        try {
            Class.forName(MYSQL_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            log.error("Failed to register MySQL JDBC driver.", e);
        }
        try {
            Class.forName(MARIADB_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            log.error("Failed to register MariaDB JDBC driver.", e);
        }
        //Register proprietary drivers (if present)
        try {
            Class.forName(ORACLE_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            log.info("No Oracle JDBC driver found...");
        }
        try {
            Class.forName(SQLSERVER_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            log.info("No SQL Server JDBC driver found...");
        }
        try {
            Class.forName(DB2_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            log.info("No DB2 JDBC driver found...");
        }
    }
}

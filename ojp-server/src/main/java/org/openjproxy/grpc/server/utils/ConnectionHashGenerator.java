package org.openjproxy.grpc.server.utils;

import com.openjproxy.grpc.ConnectionDetails;
import org.openjproxy.grpc.ProtoConverter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import static org.openjproxy.grpc.server.Constants.SHA_256;

/**
 * Utility class for generating connection hashes.
 * Extracted from StatementServiceImpl to improve modularity.
 * Updated to include dataSource name in hash for multi-datasource support.
 */
public class ConnectionHashGenerator {

    /**
     * Generates a hash for connection details using SHA-256.
     * Now includes dataSource name to ensure separate pools for different dataSources
     * even when using the same connection parameters.
     *
     * @param connectionDetails The connection details to hash
     * @return Hash string for the connection details
     * @throws RuntimeException if hashing fails
     */
    public static String hashConnectionDetails(ConnectionDetails connectionDetails) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(SHA_256);

            // Concatenate all parts at once: URL, user, password, and dataSource name
            String hashInput = connectionDetails.getUrl() + connectionDetails.getUser() + connectionDetails.getPassword() +
                    extractDataSourceName(connectionDetails);

            byte[] full = messageDigest.digest(hashInput.getBytes(StandardCharsets.UTF_8));

            return Base64.getUrlEncoder().withoutPadding().encodeToString(full);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate connection hash", e);
        }
    }

    /**
     * Extracts the dataSource name from connection details properties.
     * Returns "default" if no dataSource name is specified.
     */
    private static String extractDataSourceName(ConnectionDetails connectionDetails) {
        if (connectionDetails.getPropertiesList().isEmpty()) {
            return "default";
        }

        try {
            Map<String, Object> properties = ProtoConverter.propertiesFromProto(connectionDetails.getPropertiesList());
            Object dataSourceName = properties.get("ojp.datasource.name");
            return dataSourceName != null ? dataSourceName.toString() : "default";
        } catch (Exception e) {
            // If we can't deserialize properties, fall back to default
            return "default";
        }
    }
}

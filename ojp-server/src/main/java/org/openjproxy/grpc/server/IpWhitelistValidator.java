package org.openjproxy.grpc.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Utility class for IP address validation and CIDR range matching.
 */
public class IpWhitelistValidator {
    private static final Logger logger = LoggerFactory.getLogger(IpWhitelistValidator.class);
    
    /**
     * Constant representing "allow all" CIDR range.
     */
    public static final String ALLOW_ALL_IPS = "0.0.0.0/0";

    /**
     * Validates if an IP address is allowed based on the whitelist.
     * Supports both individual IP addresses and CIDR ranges.
     *
     * @param clientIp the IP address to validate
     * @param allowedIps list of allowed IPs and CIDR ranges
     * @return true if the IP is allowed, false otherwise
     */
    public static boolean isIpAllowed(String clientIp, List<String> allowedIps) {
        if (allowedIps == null || allowedIps.isEmpty()) {
            logger.debug("No IP whitelist configured, allowing all connections");
            return true;
        }

        // Check if wildcard is present (allow all)
        if (allowedIps.contains(ALLOW_ALL_IPS) || allowedIps.contains("*")) {
            logger.debug("Wildcard found in whitelist, allowing IP: {}", clientIp);
            return true;
        }

        try {
            InetAddress clientAddress = InetAddress.getByName(clientIp);
            
            for (String allowedRule : allowedIps) {
                if (matchesIpRule(clientAddress, allowedRule.trim())) {
                    logger.debug("IP {} matches rule: {}", clientIp, allowedRule);
                    return true;
                }
            }
            
            // Log warning when denying access for audit purposes
            logger.warn("Access denied - IP {} not found in whitelist. Configured whitelist: {}", clientIp, allowedIps);
            return false;
            
        } catch (UnknownHostException e) {
            logger.warn("Invalid client IP address: {}", clientIp);
            return false;
        }
    }

    /**
     * Checks if an IP address matches a specific rule (individual IP or CIDR range).
     */
    private static boolean matchesIpRule(InetAddress clientAddress, String rule) {
        try {
            if (rule.contains("/")) {
                // CIDR range
                return matchesCidrRange(clientAddress, rule);
            } else {
                // Individual IP address
                InetAddress allowedAddress = InetAddress.getByName(rule);
                return clientAddress.equals(allowedAddress);
            }
        } catch (UnknownHostException e) {
            logger.warn("Invalid IP rule in whitelist: {}", rule);
            return false;
        }
    }

    /**
     * Checks if an IP address is within a CIDR range.
     */
    private static boolean matchesCidrRange(InetAddress clientAddress, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                logger.warn("Invalid CIDR format: {}", cidr);
                return false;
            }

            InetAddress networkAddress = InetAddress.getByName(parts[0]);
            int prefixLength = Integer.parseInt(parts[1]);

            // Only IPv4 is supported for CIDR matching in this implementation
            if (clientAddress.getAddress().length != 4 || networkAddress.getAddress().length != 4) {
                logger.debug("IPv6 CIDR matching not supported, treating as non-match for: {}", cidr);
                return false;
            }

            if (prefixLength < 0 || prefixLength > 32) {
                logger.warn("Invalid CIDR prefix length: {}", prefixLength);
                return false;
            }

            // Convert addresses to integers for bitwise operations
            int clientInt = bytesToInt(clientAddress.getAddress());
            int networkInt = bytesToInt(networkAddress.getAddress());
            
            // Create subnet mask
            int mask = 0xFFFFFFFF << (32 - prefixLength);
            
            // Check if client IP is in the network
            return (clientInt & mask) == (networkInt & mask);
            
        } catch (NumberFormatException | UnknownHostException e) {
            logger.warn("Error parsing CIDR range: {}", cidr, e);
            return false;
        }
    }

    /**
     * Converts a 4-byte array representing an IPv4 address to a 32-bit integer.
     * This method is used for CIDR range calculations where IP addresses need to be
     * compared numerically. Each byte is treated as an unsigned value (0-255) and
     * positioned according to network byte order (big-endian).
     * 
     * @param bytes 4-byte array representing IPv4 address octets
     * @return 32-bit integer representation of the IP address
     * 
     * @examples
     * - IP 192.168.1.1 (bytes: [192, 168, 1, 1]) → 3232235777
     * - IP 10.0.0.1 (bytes: [10, 0, 0, 1]) → 167772161  
     * - IP 127.0.0.1 (bytes: [127, 0, 0, 1]) → 2130706433
     */
    private static int bytesToInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) |
               ((bytes[1] & 0xFF) << 16) |
               ((bytes[2] & 0xFF) << 8) |
               (bytes[3] & 0xFF);
    }

    /**
     * Validates the format of IP whitelist rules.
     * 
     * @param allowedIps list of IP rules to validate
     * @return true if all rules are valid, false otherwise
     */
    public static boolean validateWhitelistRules(List<String> allowedIps) {
        if (allowedIps == null || allowedIps.isEmpty()) {
            return true;
        }

        for (String rule : allowedIps) {
            if (!isValidIpRule(rule.trim())) {
                logger.error("Invalid IP whitelist rule: {}", rule);
                return false;
            }
        }
        return true;
    }

    /**
     * Validates a single IP rule (individual IP or CIDR range).
     */
    private static boolean isValidIpRule(String rule) {
        if (rule.equals("*") || rule.equals(ALLOW_ALL_IPS)) {
            return true;
        }

        try {
            if (rule.contains("/")) {
                // Validate CIDR range
                String[] parts = rule.split("/");
                if (parts.length != 2) {
                    return false;
                }
                
                InetAddress.getByName(parts[0]); // Validate IP part
                int prefixLength = Integer.parseInt(parts[1]); // Validate prefix
                return prefixLength >= 0 && prefixLength <= 32;
            } else {
                // Validate individual IP
                InetAddress.getByName(rule);
                return true;
            }
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }
}
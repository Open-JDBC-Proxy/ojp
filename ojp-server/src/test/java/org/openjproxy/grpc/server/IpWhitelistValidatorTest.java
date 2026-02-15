package org.openjproxy.grpc.server;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IpWhitelistValidator class.
 */
 class IpWhitelistValidatorTest {

    @Test
     void testWildcardAccess() {
        List<String> wildcardRules = List.of(IpWhitelistValidator.ALLOW_ALL_IPS);
        assertTrue(IpWhitelistValidator.isIpAllowed("192.168.1.1", wildcardRules));
        assertTrue(IpWhitelistValidator.isIpAllowed("10.0.0.1", wildcardRules));
        assertTrue(IpWhitelistValidator.isIpAllowed("172.16.0.1", wildcardRules));

        List<String> asteriskRules = List.of("*");
        assertTrue(IpWhitelistValidator.isIpAllowed("192.168.1.1", asteriskRules));
        assertTrue(IpWhitelistValidator.isIpAllowed("10.0.0.1", asteriskRules));
    }

    @Test
     void testEmptyWhitelist() {
        List<String> emptyRules = List.of();
        assertTrue(IpWhitelistValidator.isIpAllowed("192.168.1.1", emptyRules));
        assertTrue(IpWhitelistValidator.isIpAllowed("10.0.0.1", emptyRules));

        assertTrue(IpWhitelistValidator.isIpAllowed("192.168.1.1", null));
    }

    @Test
     void testIndividualIpMatching() {
        List<String> specificIps = List.of("192.168.1.1", "10.0.0.1", "172.16.0.1");

        assertTrue(IpWhitelistValidator.isIpAllowed("192.168.1.1", specificIps));
        assertTrue(IpWhitelistValidator.isIpAllowed("10.0.0.1", specificIps));
        assertTrue(IpWhitelistValidator.isIpAllowed("172.16.0.1", specificIps));

        assertFalse(IpWhitelistValidator.isIpAllowed("192.168.1.2", specificIps));
        assertFalse(IpWhitelistValidator.isIpAllowed("10.0.0.2", specificIps));
        assertFalse(IpWhitelistValidator.isIpAllowed("8.8.8.8", specificIps));
    }

    @Test
     void testCidrRangeMatching() {
        List<String> cidrRules = List.of("192.168.1.0/24", "10.0.0.0/8");

        // Test 192.168.1.0/24 range
        assertTrue(IpWhitelistValidator.isIpAllowed("192.168.1.1", cidrRules));
        assertTrue(IpWhitelistValidator.isIpAllowed("192.168.1.100", cidrRules));
        assertTrue(IpWhitelistValidator.isIpAllowed("192.168.1.254", cidrRules));
        assertFalse(IpWhitelistValidator.isIpAllowed("192.168.2.1", cidrRules));

        // Test 10.0.0.0/8 range
        assertTrue(IpWhitelistValidator.isIpAllowed("10.1.1.1", cidrRules));
        assertTrue(IpWhitelistValidator.isIpAllowed("10.255.255.255", cidrRules));
        assertFalse(IpWhitelistValidator.isIpAllowed("11.0.0.1", cidrRules));
    }

    @Test
     void testMixedRules() {
        List<String> mixedRules = List.of("127.0.0.1", "192.168.1.0/24", "10.0.0.1");

        assertTrue(IpWhitelistValidator.isIpAllowed("127.0.0.1", mixedRules));
        assertTrue(IpWhitelistValidator.isIpAllowed("192.168.1.50", mixedRules));
        assertTrue(IpWhitelistValidator.isIpAllowed("10.0.0.1", mixedRules));

        assertFalse(IpWhitelistValidator.isIpAllowed("127.0.0.2", mixedRules));
        assertFalse(IpWhitelistValidator.isIpAllowed("192.168.2.1", mixedRules));
        assertFalse(IpWhitelistValidator.isIpAllowed("10.0.0.2", mixedRules));
    }

    @Test
     void testInvalidIpAddress() {
        List<String> rules = List.of("192.168.1.1");
        assertFalse(IpWhitelistValidator.isIpAllowed("invalid-ip", rules));
        assertFalse(IpWhitelistValidator.isIpAllowed("999.999.999.999", rules));
    }

    @Test
     void testValidateWhitelistRules() {
        // Valid rules
        assertTrue(IpWhitelistValidator.validateWhitelistRules(List.of("192.168.1.1")));
        assertTrue(IpWhitelistValidator.validateWhitelistRules(List.of("192.168.1.0/24")));
        assertTrue(IpWhitelistValidator.validateWhitelistRules(List.of("*")));
        assertTrue(IpWhitelistValidator.validateWhitelistRules(List.of(IpWhitelistValidator.ALLOW_ALL_IPS)));
        assertTrue(IpWhitelistValidator.validateWhitelistRules(List.of("127.0.0.1", "192.168.1.0/24")));
        assertTrue(IpWhitelistValidator.validateWhitelistRules(null));
        assertTrue(IpWhitelistValidator.validateWhitelistRules(List.of()));

        // Invalid rules
        assertFalse(IpWhitelistValidator.validateWhitelistRules(List.of("invalid-ip")));
        assertFalse(IpWhitelistValidator.validateWhitelistRules(List.of("192.168.1.0/33")));
        assertFalse(IpWhitelistValidator.validateWhitelistRules(List.of("192.168.1.0/-1")));
        assertFalse(IpWhitelistValidator.validateWhitelistRules(List.of("192.168.1.0/abc")));
        assertFalse(IpWhitelistValidator.validateWhitelistRules(List.of("999.999.999.999")));
    }

    @Test
     void testCidrEdgeCases() {
        // Test /32 CIDR (single host)
        List<String> singleHostCidr = List.of("192.168.1.1/32");
        assertTrue(IpWhitelistValidator.isIpAllowed("192.168.1.1", singleHostCidr));
        assertFalse(IpWhitelistValidator.isIpAllowed("192.168.1.2", singleHostCidr));

        // Test /0 CIDR (all hosts)
        List<String> allHostsCidr = List.of(IpWhitelistValidator.ALLOW_ALL_IPS);
        assertTrue(IpWhitelistValidator.isIpAllowed("192.168.1.1", allHostsCidr));
        assertTrue(IpWhitelistValidator.isIpAllowed("8.8.8.8", allHostsCidr));

        // Test larger subnet
        List<String> largeSubnet = List.of("192.168.0.0/16");
        assertTrue(IpWhitelistValidator.isIpAllowed("192.168.1.1", largeSubnet));
        assertTrue(IpWhitelistValidator.isIpAllowed("192.168.255.255", largeSubnet));
        assertFalse(IpWhitelistValidator.isIpAllowed("192.167.1.1", largeSubnet));
        assertFalse(IpWhitelistValidator.isIpAllowed("192.169.1.1", largeSubnet));
    }

    @Test
     void testInvalidCidrFormats() {
        List<String> invalidCidr = List.of("192.168.1.0/");
        assertFalse(IpWhitelistValidator.isIpAllowed("192.168.1.1", invalidCidr));

        List<String> invalidCidr2 = List.of("192.168.1.0/24/extra");
        assertFalse(IpWhitelistValidator.isIpAllowed("192.168.1.1", invalidCidr2));
    }

    @Test
     void testWhitespaceHandling() {
        List<String> rulesWithWhitespace = List.of(" 192.168.1.1 ", "  192.168.1.0/24  ");
        assertTrue(IpWhitelistValidator.isIpAllowed("192.168.1.1", rulesWithWhitespace));
        assertTrue(IpWhitelistValidator.isIpAllowed("192.168.1.50", rulesWithWhitespace));
    }
}
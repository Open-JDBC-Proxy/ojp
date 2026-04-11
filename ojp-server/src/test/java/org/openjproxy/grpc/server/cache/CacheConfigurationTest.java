package org.openjproxy.grpc.server.cache;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class CacheConfigurationTest {

    @Test
    void testConstructorWithValidParameters() {
        CacheRule rule = new CacheRule(
            Pattern.compile("SELECT .*"),
            Duration.ofMinutes(5),
            List.of("users"),
            true
        );
        
        CacheConfiguration config = new CacheConfiguration("postgres_prod", true, List.of(rule)
        );
        
        assertEquals("postgres_prod", config.getDatasourceName());
        assertTrue(config.isEnabled());
        assertEquals(1, config.getRules().size());
    }

    @Test
    void testConstructorWithNullDatasource() {
        assertThrows(NullPointerException.class, () ->
            new CacheConfiguration(null, true, List.of())
        );
    }

    @Test
    void testConstructorWithNullRules() {
        CacheConfiguration config = new CacheConfiguration("ds", true, null);
        
        assertEquals(List.of(), config.getRules());
    }

    @Test
    void testDisabledFactory() {
        CacheConfiguration config = CacheConfiguration.disabled("postgres_prod");
        
        assertEquals("postgres_prod", config.getDatasourceName());
        assertFalse(config.isEnabled());
        assertEquals(0, config.getRules().size());
    }

    @Test
    void testFindMatchingRuleWithMatch() {
        CacheRule rule1 = new CacheRule(
            Pattern.compile("SELECT .* FROM users.*"),
            Duration.ofMinutes(5),
            List.of("users"),
            true
        );
        CacheRule rule2 = new CacheRule(
            Pattern.compile("SELECT .* FROM products.*"),
            Duration.ofMinutes(10),
            List.of("products"),
            true
        );
        
        CacheConfiguration config = new CacheConfiguration("ds", true, List.of(rule1, rule2)
        );
        
        CacheRule found = config.findMatchingRule("SELECT * FROM users");
        assertEquals(rule1, found);
        
        found = config.findMatchingRule("SELECT * FROM products");
        assertEquals(rule2, found);
    }

    @Test
    void testFindMatchingRuleWithNoMatch() {
        CacheRule rule = new CacheRule(
            Pattern.compile("SELECT .* FROM users.*"),
            Duration.ofMinutes(5),
            List.of("users"),
            true
        );
        
        CacheConfiguration config = new CacheConfiguration("ds", true, List.of(rule)
        );
        
        CacheRule found = config.findMatchingRule("SELECT * FROM orders");
        assertNull(found);
    }

    @Test
    void testFindMatchingRuleWhenDisabled() {
        CacheRule rule = new CacheRule(
            Pattern.compile("SELECT .*"),
            Duration.ofMinutes(5),
            List.of(),
            true
        );
        
        CacheConfiguration config = new CacheConfiguration(
            "ds",
            false,  // Cache disabled
            List.of(rule)
        );
        
        CacheRule found = config.findMatchingRule("SELECT * FROM users");
        assertNull(found);
    }

    @Test
    void testFindMatchingRuleWithPriority() {
        // First rule should match first
        CacheRule rule1 = new CacheRule(
            Pattern.compile("SELECT .*"),  // Matches everything
            Duration.ofMinutes(1),
            List.of(),
            true
        );
        CacheRule rule2 = new CacheRule(
            Pattern.compile("SELECT .* FROM users.*"),
            Duration.ofMinutes(10),
            List.of("users"),
            true
        );
        
        CacheConfiguration config = new CacheConfiguration("ds", true, List.of(rule1, rule2)  // rule1 first
        );
        
        CacheRule found = config.findMatchingRule("SELECT * FROM users");
        assertEquals(rule1, found);  // First match wins
    }

    @Test
    void testShouldInvalidateOnWithMatchingTable() {
        CacheRule rule = new CacheRule(
            Pattern.compile("SELECT .*"),
            Duration.ofMinutes(5),
            List.of("users", "profiles"),
            true
        );
        
        CacheConfiguration config = new CacheConfiguration("ds", true, List.of(rule)
        );
        
        assertTrue(config.shouldInvalidateOn("users"));
        assertTrue(config.shouldInvalidateOn("profiles"));
    }

    @Test
    void testShouldInvalidateOnWithNoMatch() {
        CacheRule rule = new CacheRule(
            Pattern.compile("SELECT .*"),
            Duration.ofMinutes(5),
            List.of("users"),
            true
        );
        
        CacheConfiguration config = new CacheConfiguration("ds", true, List.of(rule)
        );
        
        assertFalse(config.shouldInvalidateOn("orders"));
    }

    @Test
    void testShouldInvalidateOnWhenDisabled() {
        CacheRule rule = new CacheRule(
            Pattern.compile("SELECT .*"),
            Duration.ofMinutes(5),
            List.of("users"),
            true
        );
        
        CacheConfiguration config = new CacheConfiguration(
            "ds",
            false,  // Cache disabled
            List.of(rule)
        );
        
        assertFalse(config.shouldInvalidateOn("users"));
    }

    @Test
    void testShouldInvalidateOnWithMultipleRules() {
        CacheRule rule1 = new CacheRule(
            Pattern.compile("SELECT .* FROM users.*"),
            Duration.ofMinutes(5),
            List.of("users"),
            true
        );
        CacheRule rule2 = new CacheRule(
            Pattern.compile("SELECT .* FROM products.*"),
            Duration.ofMinutes(10),
            List.of("products"),
            true
        );
        
        CacheConfiguration config = new CacheConfiguration("ds", true, List.of(rule1, rule2)
        );
        
        assertTrue(config.shouldInvalidateOn("users"));
        assertTrue(config.shouldInvalidateOn("products"));
        assertFalse(config.shouldInvalidateOn("orders"));
    }

    @Test
    void testRulesAreImmutable() {
        CacheRule rule = new CacheRule(
            Pattern.compile("SELECT .*"),
            Duration.ofMinutes(5),
            List.of(),
            true
        );
        
        CacheConfiguration config = new CacheConfiguration("ds", true, List.of(rule)
        );
        
        assertThrows(UnsupportedOperationException.class, () ->
            config.getRules().add(rule)
        );
    }

    @Test
    void testToString() {
        CacheRule rule = new CacheRule(
            Pattern.compile("SELECT .*"),
            Duration.ofMinutes(5),
            List.of(),
            true
        );
        
        CacheConfiguration config = new CacheConfiguration("postgres_prod", true, List.of(rule)
        );
        
        String str = config.toString();
        assertTrue(str.contains("postgres_prod"));
        assertTrue(str.contains("enabled=true"));
        assertTrue(str.contains("ruleCount=1"));
    }

    @Test
    void testEmptyRulesConfiguration() {
        CacheConfiguration config = new CacheConfiguration("ds", true, List.of()
        );
        
        assertNull(config.findMatchingRule("SELECT * FROM users"));
        assertFalse(config.shouldInvalidateOn("users"));
    }

    @Test
    void testDistributeFlagForFutureUse() {
        CacheConfiguration config = new CacheConfiguration(
            "ds",
            true,
            List.of()
        );
        
    }

    @Test
    void testRuleOrderingMatters() {
        CacheRule specificRule = new CacheRule(
            Pattern.compile("SELECT .* FROM users WHERE id = .*"),
            Duration.ofMinutes(10),
            List.of("users"),
            true
        );
        CacheRule generalRule = new CacheRule(
            Pattern.compile("SELECT .* FROM users.*"),
            Duration.ofMinutes(5),
            List.of("users"),
            true
        );
        
        // Specific rule first
        CacheConfiguration config1 = new CacheConfiguration("ds", true, List.of(specificRule, generalRule)
        );
        
        CacheRule found1 = config1.findMatchingRule("SELECT * FROM users WHERE id = 1");
        assertEquals(specificRule, found1);
        
        // General rule first
        CacheConfiguration config2 = new CacheConfiguration("ds", true, List.of(generalRule, specificRule)
        );
        
        CacheRule found2 = config2.findMatchingRule("SELECT * FROM users WHERE id = 1");
        assertEquals(generalRule, found2);  // First match wins
    }
}

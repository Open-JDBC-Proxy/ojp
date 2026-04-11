package org.openjproxy.grpc.server.cache;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class CacheRuleTest {

    @Test
    void testConstructorWithValidParameters() {
        Pattern pattern = Pattern.compile("SELECT .*");
        Duration ttl = Duration.ofMinutes(5);
        List<String> invalidateOn = List.of("users", "products");
        
        CacheRule rule = new CacheRule(pattern, ttl, invalidateOn, true);
        
        assertEquals(pattern, rule.getSqlPattern());
        assertEquals(ttl, rule.getTtl());
        assertEquals(invalidateOn, rule.getInvalidateOn());
        assertTrue(rule.isEnabled());
    }

    @Test
    void testConstructorWithNullPattern() {
        assertThrows(NullPointerException.class, () ->
            new CacheRule(null, Duration.ofMinutes(5), List.of(), true)
        );
    }

    @Test
    void testConstructorWithNullTtl() {
        assertThrows(NullPointerException.class, () ->
            new CacheRule(Pattern.compile(".*"), null, List.of(), true)
        );
    }

    @Test
    void testConstructorWithZeroTtl() {
        assertThrows(IllegalArgumentException.class, () ->
            new CacheRule(Pattern.compile(".*"), Duration.ZERO, List.of(), true)
        );
    }

    @Test
    void testConstructorWithNegativeTtl() {
        assertThrows(IllegalArgumentException.class, () ->
            new CacheRule(Pattern.compile(".*"), Duration.ofSeconds(-1), List.of(), true)
        );
    }

    @Test
    void testConstructorWithNullInvalidateOn() {
        CacheRule rule = new CacheRule(Pattern.compile(".*"), Duration.ofMinutes(5), null, true);
        
        assertEquals(List.of(), rule.getInvalidateOn());
    }

    @Test
    void testMatchesWithMatchingPattern() {
        CacheRule rule = new CacheRule(
            Pattern.compile("SELECT .* FROM users.*"),
            Duration.ofMinutes(5),
            List.of(),
            true
        );
        
        assertTrue(rule.matches("SELECT * FROM users"));
        assertTrue(rule.matches("SELECT id, name FROM users WHERE id = 1"));
    }

    @Test
    void testMatchesWithNonMatchingPattern() {
        CacheRule rule = new CacheRule(
            Pattern.compile("SELECT .* FROM users.*"),
            Duration.ofMinutes(5),
            List.of(),
            true
        );
        
        assertFalse(rule.matches("SELECT * FROM products"));
        assertFalse(rule.matches("INSERT INTO users VALUES (1)"));
    }

    @Test
    void testMatchesWhenDisabled() {
        CacheRule rule = new CacheRule(
            Pattern.compile(".*"),
            Duration.ofMinutes(5),
            List.of(),
            false
        );
        
        assertFalse(rule.matches("SELECT * FROM users"));
    }

    @Test
    void testShouldInvalidateOnWithMatchingTable() {
        CacheRule rule = new CacheRule(
            Pattern.compile(".*"),
            Duration.ofMinutes(5),
            List.of("users", "products"),
            true
        );
        
        assertTrue(rule.shouldInvalidateOn("users"));
        assertTrue(rule.shouldInvalidateOn("products"));
    }

    @Test
    void testShouldInvalidateOnWithNonMatchingTable() {
        CacheRule rule = new CacheRule(
            Pattern.compile(".*"),
            Duration.ofMinutes(5),
            List.of("users", "products"),
            true
        );
        
        assertFalse(rule.shouldInvalidateOn("orders"));
    }

    @Test
    void testShouldInvalidateOnWithEmptyList() {
        CacheRule rule = new CacheRule(
            Pattern.compile(".*"),
            Duration.ofMinutes(5),
            List.of(),
            true
        );
        
        // Empty list means invalidate on ANY table
        assertTrue(rule.shouldInvalidateOn("users"));
        assertTrue(rule.shouldInvalidateOn("products"));
        assertTrue(rule.shouldInvalidateOn("orders"));
    }

    @Test
    void testShouldInvalidateOnWhenDisabled() {
        CacheRule rule = new CacheRule(
            Pattern.compile(".*"),
            Duration.ofMinutes(5),
            List.of("users"),
            false
        );
        
        assertFalse(rule.shouldInvalidateOn("users"));
    }

    @Test
    void testShouldInvalidateOnCaseInsensitive() {
        CacheRule rule = new CacheRule(
            Pattern.compile(".*"),
            Duration.ofMinutes(5),
            List.of("users"),
            true
        );
        
        assertTrue(rule.shouldInvalidateOn("users"));
        assertTrue(rule.shouldInvalidateOn("USERS"));
        assertTrue(rule.shouldInvalidateOn("Users"));
    }

    @Test
    void testInvalidateOnListIsImmutable() {
        CacheRule rule = new CacheRule(
            Pattern.compile(".*"),
            Duration.ofMinutes(5),
            List.of("users"),
            true
        );
        
        List<String> invalidateOn = rule.getInvalidateOn();
        assertThrows(UnsupportedOperationException.class, () -> invalidateOn.add("products"));
    }

    @Test
    void testToString() {
        CacheRule rule = new CacheRule(
            Pattern.compile("SELECT .*"),
            Duration.ofMinutes(5),
            List.of("users"),
            true
        );
        
        String str = rule.toString();
        assertTrue(str.contains("SELECT .*"));
        assertTrue(str.contains("PT5M"));
        assertTrue(str.contains("users"));
        assertTrue(str.contains("true"));
    }

    @Test
    void testComplexPatternMatching() {
        CacheRule rule = new CacheRule(
            Pattern.compile("SELECT .* FROM products WHERE category = .*"),
            Duration.ofMinutes(10),
            List.of("products"),
            true
        );
        
        assertTrue(rule.matches("SELECT * FROM products WHERE category = 'electronics'"));
        assertTrue(rule.matches("SELECT id, name FROM products WHERE category = 'books'"));
        assertFalse(rule.matches("SELECT * FROM products"));
        assertFalse(rule.matches("SELECT * FROM products WHERE price > 100"));
    }

    @Test
    void testMultipleInvalidateOnTables() {
        CacheRule rule = new CacheRule(
            Pattern.compile(".*"),
            Duration.ofMinutes(5),
            List.of("users", "profiles", "permissions"),
            true
        );
        
        assertTrue(rule.shouldInvalidateOn("users"));
        assertTrue(rule.shouldInvalidateOn("profiles"));
        assertTrue(rule.shouldInvalidateOn("permissions"));
        assertFalse(rule.shouldInvalidateOn("orders"));
    }

    @Test
    void testDisabledRuleBehavior() {
        CacheRule rule = new CacheRule(
            Pattern.compile("SELECT .*"),
            Duration.ofMinutes(5),
            List.of("users"),
            false
        );
        
        assertFalse(rule.isEnabled());
        assertFalse(rule.matches("SELECT * FROM users"));
        assertFalse(rule.shouldInvalidateOn("users"));
    }
}

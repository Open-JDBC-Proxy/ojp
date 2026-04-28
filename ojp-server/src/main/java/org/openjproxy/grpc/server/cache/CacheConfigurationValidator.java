package org.openjproxy.grpc.server.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates cache configuration for safety, correctness, and best practices.
 * Provides clear error and warning messages for operators.
 */
public class CacheConfigurationValidator {

    private static final long MIN_TTL_SECONDS = 10;
    private static final long MAX_TTL_HOURS = 24;
    private static final int MAX_PATTERN_LENGTH = 1000;

    /**
     * Validates a cache configuration and returns validation results.
     *
     * @param config the cache configuration to validate
     * @return validation result with errors and warnings
     */
    public static ValidationResult validate(CacheConfiguration config) {
        if (config == null) {
            return new ValidationResult(
                List.of("Cache configuration cannot be null"),
                List.of()
            );
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Validate datasource name
        if (config.getDatasourceName() == null || config.getDatasourceName().trim().isEmpty()) {
            errors.add("Datasource name cannot be null or empty");
        }

        // Validate cache rules
        List<CacheRule> rules = config.getRules();
        if (rules == null || rules.isEmpty()) {
            warnings.add("No cache rules defined - caching will be disabled");
            return new ValidationResult(errors, warnings);
        }

        for (int i = 0; i < rules.size(); i++) {
            CacheRule rule = rules.get(i);
            validateRule(rule, i + 1, errors, warnings);
        }

        return new ValidationResult(errors, warnings);
    }

    private static void validateRule(CacheRule rule, int ruleNumber,
                                     List<String> errors, List<String> warnings) {
        String rulePrefix = "Rule " + ruleNumber + ": ";

        // Validate pattern
        if (rule.getSqlPattern() == null) {
            errors.add(rulePrefix + "Pattern cannot be null");
        } else {
            validatePattern(rule.getSqlPattern().pattern(), rulePrefix, errors, warnings);
        }

        // Validate TTL
        Duration ttl = rule.getTtl();
        if (ttl == null) {
            errors.add(rulePrefix + "TTL cannot be null");
        } else {
            validateTtl(ttl, rulePrefix, errors, warnings);
        }

        // Validate invalidation tables
        if (rule.getInvalidateOn() != null) {
            for (String table : rule.getInvalidateOn()) {
                validateTableName(table, rulePrefix, errors, warnings);
            }
        }
    }

    private static void validatePattern(String pattern, String rulePrefix,
                                       List<String> errors, List<String> warnings) {
        // Check pattern length
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            warnings.add(rulePrefix + "Pattern very long (" + pattern.length() +
                " chars) - may impact performance");
        }

        // Test pattern compilation
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            errors.add(rulePrefix + "Invalid regex pattern: " + e.getMessage());
            return;
        }

        // Warn about overly broad patterns
        if (pattern.equals(".*") || pattern.equals(".+")) {
            warnings.add(rulePrefix + "Pattern matches all queries - may cache too much");
        }

        // Warn about patterns without anchors
        if (!pattern.startsWith("^") && !pattern.contains("SELECT")) {
            warnings.add(rulePrefix + "Pattern not anchored and doesn't contain SELECT - " +
                "may match non-SELECT queries");
        }
    }

    private static void validateTtl(Duration ttl, String rulePrefix,
                                   List<String> errors, List<String> warnings) {
        // Check TTL is positive
        if (ttl.isNegative() || ttl.isZero()) {
            errors.add(rulePrefix + "TTL must be positive: " + ttl);
            return;
        }

        // Warn about very short TTLs
        long seconds = ttl.toSeconds();
        if (seconds < MIN_TTL_SECONDS) {
            warnings.add(rulePrefix + "TTL very short (< " + MIN_TTL_SECONDS + "s): " + ttl +
                " - may cause high database load");
        }

        // Warn about very long TTLs
        long hours = ttl.toHours();
        if (hours > MAX_TTL_HOURS) {
            warnings.add(rulePrefix + "TTL very long (> " + MAX_TTL_HOURS + "h): " + ttl +
                " - risk of stale data");
        }
    }

    private static void validateTableName(String table, String rulePrefix,
                                          List<String> errors, List<String> warnings) {
        if (table == null || table.trim().isEmpty()) {
            warnings.add(rulePrefix + "Empty table name in invalidateOn list");
            return;
        }

        // Check for SQL injection patterns
        if (table.contains(";") || table.contains("--") || table.contains("/*") ||
            table.contains("*/") || table.contains("'") || table.contains("\"")) {
            errors.add(rulePrefix + "Suspicious table name (potential SQL injection): " + table);
        }

        // Check for special characters that might indicate issues
        if (table.contains(" ") && !table.contains(".")) {
            warnings.add(rulePrefix + "Table name contains space: '" + table +
                "' - may not match correctly");
        }
    }

    /**
     * Validation result containing errors and warnings.
     */
    public static class ValidationResult {
        private final List<String> errors;
        private final List<String> warnings;

        public ValidationResult(List<String> errors, List<String> warnings) {
            this.errors = List.copyOf(errors);
            this.warnings = List.copyOf(warnings);
        }

        /**
         * Returns true if configuration is valid (no errors).
         */
        public boolean isValid() {
            return errors.isEmpty();
        }

        /**
         * Returns list of error messages (configuration cannot be used).
         */
        public List<String> getErrors() {
            return errors;
        }

        /**
         * Returns list of warning messages (configuration can be used but may have issues).
         */
        public List<String> getWarnings() {
            return warnings;
        }

        /**
         * Returns all messages (errors + warnings) formatted for logging.
         */
        public String getFormattedMessages() {
            StringBuilder sb = new StringBuilder();

            if (!errors.isEmpty()) {
                sb.append("Errors: ");
                sb.append(String.join("; ", errors));
            }

            if (!warnings.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append("Warnings: ");
                sb.append(String.join("; ", warnings));
            }

            return sb.toString();
        }
    }
}

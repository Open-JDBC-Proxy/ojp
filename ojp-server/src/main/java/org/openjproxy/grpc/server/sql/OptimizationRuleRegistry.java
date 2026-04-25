package org.openjproxy.grpc.server.sql;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.rel.rules.SubQueryRemoveRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry of available optimization rules for SQL query rewriting.
 * Provides both safe and aggressive optimization rules using Apache Calcite.
 */
public class OptimizationRuleRegistry {

    private final Map<String, RelOptRule> ruleMap = new HashMap<>();

    /**
     * Creates a new registry with all available optimization rules.
     */
    public OptimizationRuleRegistry() {
        // Register safe optimization rules - Expression simplification
        registerRule("FILTER_REDUCE", CoreRules.FILTER_REDUCE_EXPRESSIONS);
        registerRule("PROJECT_REDUCE", CoreRules.PROJECT_REDUCE_EXPRESSIONS);
        registerRule("FILTER_MERGE", CoreRules.FILTER_MERGE);
        registerRule("PROJECT_MERGE", CoreRules.PROJECT_MERGE);
        registerRule("PROJECT_REMOVE", CoreRules.PROJECT_REMOVE);

        // Register subquery removal rules (converts correlated subqueries to joins)
        // SubQueryRemoveRule works with RexSubQuery nodes to convert them to joins
        registerRule("SUB_QUERY_REMOVE", SubQueryRemoveRule.Config.PROJECT.toRule());
        registerRule("SUB_QUERY_REMOVE_RULE", SubQueryRemoveRule.Config.PROJECT.toRule());  // Backward compatibility alias
        registerRule("SUB_QUERY_REMOVE_FILTER", SubQueryRemoveRule.Config.FILTER.toRule());
        registerRule("SUB_QUERY_REMOVE_JOIN", SubQueryRemoveRule.Config.JOIN.toRule());

        // Register subquery to correlate rules (intermediate step for subquery removal)
        registerRule("PROJECT_SUB_QUERY_TO_CORRELATE", CoreRules.PROJECT_SUB_QUERY_TO_CORRELATE);
        registerRule("FILTER_SUB_QUERY_TO_CORRELATE", CoreRules.FILTER_SUB_QUERY_TO_CORRELATE);
        registerRule("JOIN_SUB_QUERY_TO_CORRELATE", CoreRules.JOIN_SUB_QUERY_TO_CORRELATE);

        // Register safe join optimization rules
        registerRule("JOIN_REDUCE_EXPRESSIONS", CoreRules.JOIN_REDUCE_EXPRESSIONS);
        registerRule("JOIN_EXTRACT_FILTER", CoreRules.JOIN_EXTRACT_FILTER);
        registerRule("JOIN_PUSH_EXPRESSIONS", CoreRules.JOIN_PUSH_EXPRESSIONS);

        // Register aggregate optimization rules
        registerRule("AGGREGATE_REDUCE_FUNCTIONS", CoreRules.AGGREGATE_REDUCE_FUNCTIONS);
        registerRule("AGGREGATE_REMOVE", CoreRules.AGGREGATE_REMOVE);
        registerRule("AGGREGATE_PROJECT_MERGE", CoreRules.AGGREGATE_PROJECT_MERGE);
        registerRule("AGGREGATE_PROJECT_PULL_UP_CONSTANTS", CoreRules.AGGREGATE_PROJECT_PULL_UP_CONSTANTS);

        // Register union optimization rules
        registerRule("UNION_REMOVE", CoreRules.UNION_REMOVE);
        registerRule("UNION_MERGE", CoreRules.UNION_MERGE);
        registerRule("UNION_TO_DISTINCT", CoreRules.UNION_TO_DISTINCT);

        // Register sort optimization rules (safe ones)
        registerRule("SORT_REMOVE", CoreRules.SORT_REMOVE);
        registerRule("SORT_REMOVE_CONSTANT_KEYS", CoreRules.SORT_REMOVE_CONSTANT_KEYS);
        registerRule("SORT_UNION_TRANSPOSE", CoreRules.SORT_UNION_TRANSPOSE);

        // Register calc optimization rules
        registerRule("CALC_MERGE", CoreRules.CALC_MERGE);
        registerRule("CALC_REMOVE", CoreRules.CALC_REMOVE);

        // Register aggressive optimization rules (require opt-in via configuration)
        registerRule("FILTER_INTO_JOIN", CoreRules.FILTER_INTO_JOIN);
        registerRule("JOIN_COMMUTE", CoreRules.JOIN_COMMUTE);
        registerRule("JOIN_ASSOCIATE", CoreRules.JOIN_ASSOCIATE);
        registerRule("JOIN_PUSH_TRANSITIVE_PREDICATES", CoreRules.JOIN_PUSH_TRANSITIVE_PREDICATES);
        registerRule("JOIN_CONDITION_PUSH", CoreRules.JOIN_CONDITION_PUSH);
        registerRule("AGGREGATE_JOIN_TRANSPOSE", CoreRules.AGGREGATE_JOIN_TRANSPOSE);
        registerRule("SORT_JOIN_TRANSPOSE", CoreRules.SORT_JOIN_TRANSPOSE);
        registerRule("SORT_PROJECT_TRANSPOSE", CoreRules.SORT_PROJECT_TRANSPOSE);
    }

    /**
     * Registers a rule in the registry.
     *
     * @param name Rule name
     * @param rule Rule instance
     */
    private void registerRule(String name, RelOptRule rule) {
        ruleMap.put(name, rule);
    }

    /**
     * Gets rules by their names.
     *
     * @param names List of rule names
     * @return List of RelOptRule instances
     */
    public List<RelOptRule> getRulesByNames(List<String> names) {
        List<RelOptRule> rules = new ArrayList<>();
        for (String name : names) {
            RelOptRule rule = ruleMap.get(name);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return rules;
    }

    /**
     * Gets all safe rules (recommended for production).
     * These rules are conservative and unlikely to cause issues.
     *
     * @return List of safe RelOptRule instances
     */
    public List<RelOptRule> getSafeRules() {
        return getRulesByNames(Arrays.asList(
            "FILTER_REDUCE",
            "PROJECT_REDUCE",
            "FILTER_MERGE",
            "PROJECT_MERGE",
            "PROJECT_REMOVE",
            "SUB_QUERY_REMOVE",
            "SUB_QUERY_REMOVE_FILTER",
            "SUB_QUERY_REMOVE_JOIN",
            "PROJECT_SUB_QUERY_TO_CORRELATE",
            "FILTER_SUB_QUERY_TO_CORRELATE",
            "JOIN_SUB_QUERY_TO_CORRELATE"
        ));
    }

    /**
     * Gets aggressive rules for advanced optimization.
     * These rules perform more complex transformations like predicate pushdown and join reordering.
     *
     * @return List of aggressive RelOptRule instances
     */
    public List<RelOptRule> getAggressiveRules() {
        return getRulesByNames(Arrays.asList(
            "FILTER_INTO_JOIN",
            "JOIN_COMMUTE",
            "JOIN_ASSOCIATE",
            "JOIN_PUSH_TRANSITIVE_PREDICATES",
            "JOIN_CONDITION_PUSH",
            "AGGREGATE_JOIN_TRANSPOSE",
            "SORT_JOIN_TRANSPOSE",
            "SORT_PROJECT_TRANSPOSE"
        ));
    }

    /**
     * Gets all available rules.
     *
     * @return List of all RelOptRule instances
     */
    public List<RelOptRule> getAllRules() {
        return new ArrayList<>(ruleMap.values());
    }

    /**
     * Gets all available rule names.
     *
     * @return Set of rule names
     */
    public Set<String> getAvailableRuleNames() {
        return new HashSet<>(ruleMap.keySet());
    }
}

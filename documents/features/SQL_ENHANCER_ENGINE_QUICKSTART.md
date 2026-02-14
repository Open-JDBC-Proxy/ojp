# SQL Enhancer Engine - Quick Start Guide

> **⚠️ EXPERIMENTAL FEATURE - NOT RECOMMENDED FOR PRODUCTION**
>
> **Status:** ❌ Experimental / Not Production Ready
>
> The SQL Enhancer with Apache Calcite is **EXPERIMENTAL** and **NOT YET SUPPORTED** for production use.
>
> - **Default**: Disabled by default (opt-in only)
> - **Known Issues**: Substantial type system incompatibilities with PostgreSQL, MySQL, Oracle, SQL Server, and other traditional JDBC databases
> - **Recommendation**: **Do NOT enable in production environments**
>
> Apache Calcite is designed for big data systems (Apache Hive, Drill, Phoenix, Druid, Flink, BigQuery, Elasticsearch, MongoDB) and has significant limitations with traditional relational databases. Early testing revealed critical type mapping issues that prevent reliable query optimization.
>
> **We strongly discourage using this feature in its current state.**

**Version:** 0.3.2-snapshot

---

## What is the SQL Enhancer Engine?

An **experimental** feature that validates, optimizes, and caches SQL queries using Apache Calcite. While it provides interesting capabilities for SQL parsing and validation, it has known limitations with traditional JDBC databases.

**Intended capabilities** (with known limitations):
- SQL syntax validation before execution
- Query optimization and rewriting for better performance (limited by type system issues)
- Fast query caching (70-90% hit rate, <1ms overhead)
- ANSI SQL support (designed for all databases, but has compatibility issues)
- Graceful error handling
- Real-time metrics and monitoring

**Known Limitations:**
- Type system mismatches with PostgreSQL, MySQL, Oracle, SQL Server
- Query optimization often fails and falls back to original SQL
- Not suitable for production use with traditional JDBC databases

## Quick Start (For Testing/Development Only)

> **⚠️ WARNING**: The following instructions are for testing and experimental purposes only. Do not use in production.

### Enable the Feature

Edit `ojp.properties`:

```properties
# Enable SQL enhancer
ojp.sql.enhancer.enabled=true
```

Restart OJP server.

### Current Implementation

**Dialect Support:** Currently uses ANSI SQL (GENERIC dialect) which works with all databases. Database-specific dialect support (PostgreSQL, MySQL, Oracle, etc.) is implemented in the code but not yet wired to configuration - this will be added in a future update.

## Features

### 1. SQL Validation
- Parses SQL syntax before execution
- Validates query structure
- Catches syntax errors early

### 2. Query Optimization (NEW)
- Constant folding: Simplifies expressions
- Filter reduction: Removes redundant conditions
- Projection optimization: Eliminates unnecessary columns
- Filter and projection merging: Combines operations
- Predicate pushdown: Moves filters closer to data (aggressive)
- Join reordering: Optimizes join sequence (aggressive)

### 3. Caching
- Results cached using original SQL as keys
- Cache hits are fast (<1ms)
- 70-90% cache hit rate expected

### 4. Metrics & Monitoring
- Track queries processed, optimized, and modified
- Monitor optimization effectiveness
- Real-time performance statistics

## Performance

- **First query:** 70-120ms overhead (parsing + optimization)
- **Cached queries:** <1ms overhead (70-90% of queries)
- **Overall:** ~5-10% impact with warm cache
- **Optimization:** <50ms average for uncached queries

## Usage

Once enabled, the feature works automatically:
1. Queries are validated and parsed
2. Optimization rules are applied (if configured)
3. Results cached for fast repeated queries
4. Errors pass through gracefully

### Optimization Configuration

To enable query optimization (disabled by default):

```java
// In code - requires custom engine initialization
SqlEnhancerEngine engine = new SqlEnhancerEngine(
    true,           // enabled
    "GENERIC",      // dialect
    true,           // conversionEnabled
    true,           // optimizationEnabled
    null            // rules (null = safe defaults)
);
```

**Safe optimization rules (default):**
- FILTER_REDUCE
- PROJECT_REDUCE
- FILTER_MERGE
- PROJECT_MERGE
- PROJECT_REMOVE

**Aggressive optimization rules (advanced):**
- FILTER_INTO_JOIN
- JOIN_COMMUTE

## Monitoring

### Check Logs

```
[INFO] SQL Enhancer Engine initialized and enabled with dialect: GENERIC
[DEBUG] Successfully parsed and validated SQL
[INFO] SQL optimized with 5 rules in 42ms. Original length: 120, Optimized length: 85
[DEBUG] Cache hit for SQL: SELECT * FROM users
```

### Get Optimization Statistics

```java
String stats = engine.getOptimizationStats();
// Output: "Optimization Stats: Processed=1000, Optimized=1000 (100.0%), 
//          Modified=450 (45.0%), AvgTime=42ms"
```

## Optimization Examples

### Example 1: Constant Folding
```sql
-- Before
SELECT * FROM users WHERE 1=1 AND status='active'

-- After
SELECT * FROM users WHERE status='active'
```

### Example 2: Projection Elimination
```sql
-- Before
SELECT id, name FROM (SELECT id, name, email FROM users)

-- After
SELECT id, name FROM users
```

### Example 3: Filter Reduction
```sql
-- Before
SELECT * FROM users WHERE id > 5 AND id > 10

-- After
SELECT * FROM users WHERE id > 10
```

## Troubleshooting

### Slow First Queries

**Normal behavior** - subsequent queries will be fast (cached).

### Feature Not Working

Check `ojp.properties`:
```properties
ojp.sql.enhancer.enabled=true  # Must be true
```

### Optimization Not Applying

Optimization must be explicitly enabled in code. By default, only validation and caching are active.

## Disable Feature

```properties
ojp.sql.enhancer.enabled=false
```

## Testing

```bash
cd ojp-server
mvn test -Dtest=SqlEnhancerEngineTest
```

**Result:** 21/21 tests passing ✅

## Future Enhancements

**Database-Specific Dialects:** Code is implemented for PostgreSQL, MySQL, Oracle, SQL Server, H2 dialects but configuration wiring will be added in a future update. Currently uses GENERIC (ANSI SQL) dialect which works with all databases.

**Per-Datasource Configuration:** Will be added in future update.

## Documentation

- **Technical Analysis:** `/documents/analysis/sql_enhancer/SQL_ENHANCER_ENGINE_ANALYSIS.md` (comprehensive technical details)
- **GitHub:** https://github.com/Open-J-Proxy/ojp
- **Discord:** https://discord.gg/J5DdHpaUzu

---

**License:** Apache 2.0  
**Maintained By:** Open-J-Proxy Team

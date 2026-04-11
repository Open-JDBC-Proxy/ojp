# GitHub Issue for JSqlParser Project

---

## Title

**[Feature Request] Add support for Transaction Control, fix SELECT FOR UPDATE detection, and expand DDL statement types**

---

## Issue Type

🚀 Enhancement / Feature Request

---

## Summary

JSqlParser v4.9 lacks support for several critical SQL statement types and has broken functionality for SELECT FOR UPDATE detection. These gaps prevent use of JSqlParser for SQL statement classification in read/write traffic splitting and connection routing scenarios.

We evaluated JSqlParser for classifying SQL operations as READ (safe for replicas) or WRITE (requires primary) in a database proxy. Out of 78 comprehensive test cases, only 50 passed (64%). The failures fall into three critical categories:

1. **Transaction Control Statements** - Cannot parse BEGIN, COMMIT, ROLLBACK, SAVEPOINT
2. **SELECT FOR UPDATE Detection** - Parses but API is broken (getForUpdateTable() returns null)
3. **Stored Procedure Calls** - Cannot parse CALL/EXEC statements

---

## Motivation / Use Case

### Real-World Scenario
Database proxies and connection pools need to route SQL statements to appropriate datasources:
- **READ operations** → Route to read replicas for load distribution
- **WRITE operations** → Route to primary database for data consistency

This requires accurate classification of every SQL statement type.

### Current Limitations Impact
1. **Transaction Control**: Cannot detect transaction boundaries → breaks ACID guarantees
2. **SELECT FOR UPDATE**: Cannot detect locking reads → DATA CORRUPTION RISK (lock acquired on wrong server)
3. **Stored Procedures**: Cannot classify procedure calls → routing failures

### Similar Use Cases
- Connection poolers (PgBouncer, ProxySQL)
- Query routers (MaxScale, Vitess)
- ORM query analysis
- SQL audit logging
- Read/write splitting frameworks

---

## Detailed Issues

### Issue 1: Transaction Control Statements Not Parsed (CRITICAL)

#### Failing Statements
```sql
BEGIN
BEGIN TRANSACTION
START TRANSACTION
COMMIT
COMMIT TRANSACTION  
ROLLBACK
ROLLBACK TRANSACTION
SAVEPOINT my_savepoint
```

#### Current Behavior
```java
Statement stmt = CCJSqlParserUtil.parse("BEGIN");
// Throws: net.sf.jsqlparser.JSQLParserException: Cannot parse statement
```

#### Expected Behavior
```java
Statement stmt = CCJSqlParserUtil.parse("BEGIN TRANSACTION");
assertTrue(stmt instanceof BeginTransaction);

Statement stmt2 = CCJSqlParserUtil.parse("COMMIT");
assertTrue(stmt2 instanceof CommitTransaction);
```

#### Proposed Solution
Add new statement types:
```java
public class BeginTransaction implements Statement {
    private TransactionAccessMode accessMode;  // READ ONLY, READ WRITE
    private IsolationLevel isolationLevel;
}

public class CommitTransaction implements Statement {
    private boolean chain;  // COMMIT AND CHAIN
}

public class RollbackTransaction implements Statement {
    private String savepointName;  // ROLLBACK TO SAVEPOINT
    private boolean chain;
}

public class Savepoint implements Statement {
    private String name;
}

public class StartTransaction implements Statement {
    // Alias for BEGIN TRANSACTION
}
```

#### Impact
**HIGH** - Transaction management is fundamental to any database operation. Without this, connection routers cannot:
- Ensure all statements in a transaction go to the same server
- Track transaction state
- Implement connection pinning during transactions

---

### Issue 2: SELECT FOR UPDATE Detection Broken (CRITICAL)

#### Failing Statements  
```sql
SELECT * FROM users FOR UPDATE
SELECT * FROM users FOR SHARE
SELECT * FROM users WHERE id = 1 FOR UPDATE
SELECT * FROM users FOR UPDATE NOWAIT
SELECT * FROM users FOR UPDATE SKIP LOCKED
SELECT * FROM users u JOIN orders o ON u.id = o.user_id FOR UPDATE
```

#### Current Behavior
```java
Statement stmt = CCJSqlParserUtil.parse("SELECT * FROM users FOR UPDATE");
Select select = (Select) stmt;
PlainSelect plainSelect = (PlainSelect) select.getSelectBody();

// BUG: Both return null even though FOR UPDATE is present
Table forUpdateTable = plainSelect.getForUpdateTable();  // null ❌
Wait wait = plainSelect.getWait();  // null ❌

// No way to detect FOR UPDATE clause
```

#### Expected Behavior
```java
Statement stmt = CCJSqlParserUtil.parse("SELECT * FROM users FOR UPDATE");
Select select = (Select) stmt;
PlainSelect plainSelect = (PlainSelect) select.getSelectBody();

ForUpdateClause forUpdate = plainSelect.getForUpdate();
assertTrue(forUpdate != null);  // ✅
assertTrue(forUpdate.isForUpdate());  // ✅
assertEquals(WaitOption.NONE, forUpdate.getWaitOption());

// For "SELECT * FROM users FOR UPDATE NOWAIT"
ForUpdateClause forUpdate2 = plainSelect2.getForUpdate();
assertEquals(WaitOption.NOWAIT, forUpdate2.getWaitOption());
```

#### Proposed Solution
Replace broken API with proper FOR UPDATE support:
```java
public class PlainSelect implements SelectBody {
    // Remove broken fields:
    // private Table forUpdateTable;  // DELETE - always null
    // private Wait wait;  // DELETE - always null
    
    // Add proper FOR UPDATE support:
    private ForUpdateClause forUpdate;
    
    public ForUpdateClause getForUpdate() { return forUpdate; }
    public void setForUpdate(ForUpdateClause forUpdate) { this.forUpdate = forUpdate; }
    public boolean hasForUpdate() { return forUpdate != null; }
}

public class ForUpdateClause {
    private ForUpdateMode mode;  // UPDATE, SHARE, KEY SHARE, NO KEY UPDATE
    private List<Table> tables;  // Empty = all tables, populated = specific tables
    private WaitOption waitOption;  // NOWAIT, SKIP LOCKED, WAIT n, null
    
    public boolean isForUpdate() { return mode == ForUpdateMode.UPDATE; }
    public boolean isForShare() { return mode == ForUpdateMode.SHARE; }
    public List<Table> getTables() { return tables; }
    public WaitOption getWaitOption() { return waitOption; }
}

public enum ForUpdateMode {
    UPDATE,         // FOR UPDATE
    SHARE,          // FOR SHARE (PostgreSQL)
    KEY_SHARE,      // FOR KEY SHARE (PostgreSQL)
    NO_KEY_UPDATE   // FOR NO KEY UPDATE (PostgreSQL)
}

public enum WaitOption {
    NONE,       // No wait option specified
    NOWAIT,     // FOR UPDATE NOWAIT
    SKIP_LOCKED, // FOR UPDATE SKIP LOCKED
    WAIT        // FOR UPDATE WAIT n (Oracle)
}
```

#### Impact
**CRITICAL** - SELECT FOR UPDATE must acquire row locks on the PRIMARY database. If routed to a read replica:
- **Data corruption**: Application assumes lock acquired, but replica doesn't honor locks
- **Lost updates**: Concurrent writes cause data inconsistency
- **Deadlocks**: Mixed locking on replica and primary

This is a correctness issue, not just a feature gap.

---

### Issue 3: Stored Procedure Calls Not Supported (HIGH)

#### Failing Statements
```sql
CALL update_user_stats()
CALL process_orders(123, 'pending')
EXEC sp_update_inventory @product_id = 123
EXECUTE my_procedure
```

#### Current Behavior
```java
Statement stmt = CCJSqlParserUtil.parse("CALL update_stats()");
// Throws: net.sf.jsqlparser.JSQLParserException: Cannot parse statement
```

#### Expected Behavior
```java
Statement stmt = CCJSqlParserUtil.parse("CALL update_stats(123, 'active')");
assertTrue(stmt instanceof Call);

Call call = (Call) stmt;
assertEquals("update_stats", call.getProcedureName());
assertEquals(2, call.getParameters().size());
```

#### Proposed Solution
```java
public class Call implements Statement {
    private String procedureName;
    private List<Expression> parameters;
    private Map<String, Expression> namedParameters;  // For @param = value syntax
    
    public String getProcedureName() { return procedureName; }
    public List<Expression> getParameters() { return parameters; }
    public Map<String, Expression> getNamedParameters() { return namedParameters; }
}
```

Grammar additions:
```antlr
callStatement:
    (CALL | EXEC | EXECUTE) procedureName 
    (LPAREN expressionList? RPAREN)?
    (namedParameterList)?
;

namedParameterList:
    namedParameter (COMMA namedParameter)*
;

namedParameter:
    AT? parameterName EQUALS expression
;
```

#### Impact
**HIGH** - Stored procedures are common in enterprise applications. Without classification:
- Cannot determine if procedure contains writes
- Must conservatively route all CALL statements to primary
- Prevents read load distribution for read-only procedures

---

### Issue 4: DDL Statement Type Hierarchy Incomplete (MEDIUM)

#### Missing Statement Types
```sql
CREATE INDEX idx_users_email ON users(email)       -- No CreateIndex class
CREATE UNIQUE INDEX idx_users_username ON users(username)
RENAME TABLE users TO customers                     -- No Rename class  
ALTER TABLE users RENAME TO customers
ALTER INDEX idx_users RENAME TO idx_customers
```

#### Current Behavior
```java
Statement stmt = CCJSqlParserUtil.parse("CREATE INDEX idx ON users(email)");
// stmt is generic Statement, not CreateIndex
// Cannot use instanceof to classify as DDL/write operation

// Current hierarchy only has:
// - CreateTable ✅
// - Alter ✅
// - Drop ✅
// - Truncate ✅
// But missing:
// - CreateIndex ❌
// - CreateView ❌
// - CreateSequence ❌
// - Rename ❌
```

#### Expected Behavior
```java
Statement stmt = CCJSqlParserUtil.parse("CREATE INDEX idx ON users(email)");
assertTrue(stmt instanceof CreateIndex);

CreateIndex createIndex = (CreateIndex) stmt;
assertEquals("idx", createIndex.getIndex().getName());
assertEquals("users", createIndex.getTable().getName());
assertFalse(createIndex.isUnique());
```

#### Proposed Solution
Add missing DDL statement types:
```java
public class CreateIndex implements Statement {
    private Index index;
    private Table table;
    private List<String> columnNames;
    private boolean unique;
    private String indexType;  // BTREE, HASH, GIN, GIST, etc.
}

public class Rename implements Statement {
    private RenameType type;  // TABLE, INDEX, COLUMN, etc.
    private Table oldName;
    private Table newName;
}

public class CreateView implements Statement {
    private View view;
    private Select select;
    private boolean orReplace;
    private boolean materialized;
}

public class CreateSequence implements Statement {
    private Sequence sequence;
    private Long startWith;
    private Long incrementBy;
}
```

#### Impact
**MEDIUM** - While these statements parse successfully, the lack of specific types makes classification difficult. Requires complex string analysis as fallback.

---

### Issue 5: SET Statements Not Supported (MEDIUM)

#### Failing Statements
```sql
SET search_path TO myschema, public
SET SESSION sql_mode = 'STRICT_TRANS_TABLES'
SET time_zone = '+00:00'
SET autocommit = 1
```

#### Current Behavior
```java
Statement stmt = CCJSqlParserUtil.parse("SET time_zone = '+00:00'");
// Throws: net.sf.jsqlparser.JSQLParserException: Cannot parse statement
```

#### Expected Behavior
```java
Statement stmt = CCJSqlParserUtil.parse("SET SESSION sql_mode = 'STRICT'");
assertTrue(stmt instanceof SetStatement);

SetStatement set = (SetStatement) stmt;
assertEquals(SetScope.SESSION, set.getScope());
assertEquals("sql_mode", set.getVariable());
assertEquals("STRICT", set.getValue().toString());
```

#### Proposed Solution
```java
public class SetStatement implements Statement {
    private SetScope scope;      // SESSION, GLOBAL, LOCAL, null
    private String variable;
    private Expression value;
    private List<String> path;   // For SET search_path TO schema1, schema2
    
    public SetScope getScope() { return scope; }
    public String getVariable() { return variable; }
    public Expression getValue() { return value; }
}

public enum SetScope {
    SESSION,
    GLOBAL,
    LOCAL,
    PERSIST,    // MySQL 8.0
    PERSIST_ONLY
}
```

#### Impact
**MEDIUM** - SET statements modify session state. Router needs to:
- Track session variables
- Ensure consistent state within a connection
- Route some SETs to primary (e.g., SET TRANSACTION)

---

### Issue 6: EXPLAIN Not Supported (LOW)

#### Failing Statements
```sql
EXPLAIN SELECT * FROM users
EXPLAIN ANALYZE SELECT * FROM users WHERE id = 1
DESCRIBE users
```

#### Current Behavior
```java
Statement stmt = CCJSqlParserUtil.parse("EXPLAIN SELECT * FROM users");
// Throws: net.sf.jsqlparser.JSQLParserException: Cannot parse statement
```

#### Expected Behavior
```java
Statement stmt = CCJSqlParserUtil.parse("EXPLAIN SELECT * FROM users");
assertTrue(stmt instanceof Explain);

Explain explain = (Explain) stmt;
assertTrue(explain.getStatement() instanceof Select);
assertFalse(explain.isAnalyze());
```

#### Impact
**LOW** - EXPLAIN is primarily used in development/debugging. Nice to have but not critical for routing.

---

## Proposed API Changes

### 1. Add Transaction Control Statements
```java
// New interfaces
public interface TransactionStatement extends Statement {
    // Marker interface for all transaction-related statements
}

public class BeginTransaction implements TransactionStatement { ... }
public class CommitTransaction implements TransactionStatement { ... }
public class RollbackTransaction implements TransactionStatement { ... }
public class Savepoint implements TransactionStatement { ... }
```

### 2. Fix SELECT FOR UPDATE
```java
public class PlainSelect implements SelectBody {
    // BEFORE (Broken):
    private Table forUpdateTable;  // Always null
    private Wait wait;             // Always null
    
    // AFTER (Fixed):
    private ForUpdateClause forUpdate;  // Properly populated
    
    public ForUpdateClause getForUpdate() { return forUpdate; }
    public boolean hasForUpdate() { return forUpdate != null; }
}
```

### 3. Add Stored Procedure Support
```java
public class Call implements Statement {
    private String procedureName;
    private List<Expression> parameters;
}
```

### 4. Expand DDL Types
```java
public class CreateIndex implements Statement { ... }
public class Rename implements Statement { ... }
public class CreateView implements Statement { ... }
```

### 5. Add Configuration Statements
```java
public class SetStatement implements Statement { ... }
public class ShowStatement implements Statement { ... }
public class UseStatement implements Statement { ... }
```

---

## Testing

We have a comprehensive test suite (78 tests) covering all these scenarios. We're happy to contribute:
1. Test cases demonstrating the issues
2. Expected parse trees
3. Grammar additions (if helpful)

Test suite available at: [SqlClassifierTest.java](https://github.com/Open-J-Proxy/ojp/blob/copilot/analyze-read-write-splitting/ojp-server/src/test/java/org/openjproxy/grpc/server/readwrite/SqlClassifierTest.java)

---

## Impact Assessment

### Current Workarounds
Without these features, we must:
1. **Maintain hybrid classifier** - JSqlParser for some statements, regex for others
2. **Conservative routing** - Route UNKNOWN statements to primary (reduces read scaling)
3. **Parse error handling** - Complex try/catch logic for unparseable SQL
4. **Version dependency** - Tied to JSqlParser evolution timeline

### Benefits of Implementation
1. **Complete SQL coverage** - Single parser for all statement types
2. **Type-safe classification** - instanceof checks instead of string matching
3. **Better tooling** - IDEs, static analysis work with strong types
4. **Community benefit** - Enables connection poolers, proxies, ORMs to use JSqlParser

---

## Priority

**Priority 1 (CRITICAL - Correctness Issues)**
1. Fix SELECT FOR UPDATE detection - **Data corruption risk**
2. Add transaction control statement parsing - **ACID violation risk**

**Priority 2 (HIGH - Feature Gaps)**
3. Add stored procedure call support - **Common in enterprise**
4. Expand DDL statement types - **Better classification**

**Priority 3 (MEDIUM - Quality of Life)**
5. Add SET statement support - **Session management**
6. Add EXPLAIN support - **Developer experience**

---

## Compatibility

All proposed changes are additive (new statement types, new methods). No breaking changes to existing API required, except for the PlainSelect.getForUpdateTable() fix which could be done with deprecation:

```java
@Deprecated
public Table getForUpdateTable() { 
    // Return something reasonable or throw UnsupportedOperationException
}

public ForUpdateClause getForUpdate() {
    // New, correct API
}
```

---

## Version

- **JSqlParser Version**: 4.9 (tested)
- **Target Version**: 5.0+ (proposed)
- **Compatibility**: Should be backward compatible with deprecation warnings

---

## Related Issues

- #XXX (if any existing issues relate to these features)

---

## Community Input

Would the maintainers be open to contributions for these features? We're willing to:
1. Provide detailed grammar specifications
2. Submit pull requests with implementations
3. Provide comprehensive test cases
4. Update documentation

---

## Summary

JSqlParser is excellent for table extraction and general SQL parsing, but lacks critical features for statement classification use cases. Adding support for:
1. Transaction control statements (BEGIN, COMMIT, ROLLBACK)
2. Fixed SELECT FOR UPDATE detection
3. Stored procedure calls (CALL/EXEC)
4. Expanded DDL types (CREATE INDEX, RENAME, etc.)
5. Configuration statements (SET, SHOW, USE)

...would make JSqlParser the definitive SQL parsing library for connection routing, query analysis, and ORM frameworks.

Thank you for considering this enhancement request!

---

## References

- **Detailed Analysis**: [JSQLPARSER_LIMITATIONS_REPORT.md](../JSQLPARSER_LIMITATIONS_REPORT.md)
- **Test Suite**: [SqlClassifierTest.java](https://github.com/Open-J-Proxy/ojp)
- **Use Case Documentation**: [Read/Write Splitting Analysis](../../../documents/designs/READ_WRITE_SPLITTING_ANALYSIS.md)

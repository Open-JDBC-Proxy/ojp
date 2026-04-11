# Copilot Prompt: Enhance JSqlParser for SQL Statement Classification

## Context

You are enhancing the JSqlParser library (Java SQL parser) to support additional SQL statement types needed for read/write traffic splitting in database proxy applications. Currently, JSqlParser v4.9 fails to parse or properly classify 28 out of 78 critical SQL patterns (36% failure rate).

## Project Information

- **Library**: JSqlParser (https://github.com/JSQLParser/JSqlParser)
- **Current Version**: 4.9
- **Target Version**: 5.0+
- **Language**: Java
- **Parser Technology**: JavaCC grammar
- **Build Tool**: Maven

## Your Mission

Implement the following enhancements to JSqlParser to achieve 100% coverage for SQL statement classification in read/write routing scenarios:

### Priority 1: Critical Fixes (Required for Correctness)

#### Task 1.1: Fix SELECT FOR UPDATE Detection

**Current Problem**:
```java
Statement stmt = CCJSqlParserUtil.parse("SELECT * FROM users FOR UPDATE");
Select select = (Select) stmt;
PlainSelect plainSelect = (PlainSelect) select.getSelectBody();

// BUG: Both return null even though FOR UPDATE is present in SQL
Table forUpdateTable = plainSelect.getForUpdateTable();  // null ❌
Wait wait = plainSelect.getWait();  // null ❌
```

**Required Solution**:
1. Create new `ForUpdateClause` class with proper structure
2. Add field to `PlainSelect`: `private ForUpdateClause forUpdate;`
3. Implement getter: `public ForUpdateClause getForUpdate()`
4. Update JavaCC grammar to populate this field when parsing FOR UPDATE
5. Support all variants:
   - `FOR UPDATE`
   - `FOR SHARE` (PostgreSQL)
   - `FOR KEY SHARE` (PostgreSQL)
   - `FOR NO KEY UPDATE` (PostgreSQL)
   - `FOR UPDATE OF table1, table2` (table-specific locking)
   - `FOR UPDATE NOWAIT` (Oracle, PostgreSQL)
   - `FOR UPDATE SKIP LOCKED` (PostgreSQL, Oracle)
   - `FOR UPDATE WAIT n` (Oracle)

**Implementation Guide**:
```java
// New class to add:
package net.sf.jsqlparser.statement.select;

public class ForUpdateClause {
    private ForUpdateMode mode;
    private List<Table> tables;
    private WaitOption waitOption;
    private Integer waitSeconds;
    
    public ForUpdateMode getMode() { return mode; }
    public void setMode(ForUpdateMode mode) { this.mode = mode; }
    
    public List<Table> getTables() { return tables; }
    public void setTables(List<Table> tables) { this.tables = tables; }
    
    public WaitOption getWaitOption() { return waitOption; }
    public void setWaitOption(WaitOption waitOption) { this.waitOption = waitOption; }
    
    public boolean isForUpdate() { return mode == ForUpdateMode.UPDATE; }
    public boolean isForShare() { return mode == ForUpdateMode.SHARE; }
    public boolean hasTableList() { return tables != null && !tables.isEmpty(); }
}

public enum ForUpdateMode {
    UPDATE,         // FOR UPDATE
    SHARE,          // FOR SHARE
    KEY_SHARE,      // FOR KEY SHARE (PostgreSQL)
    NO_KEY_UPDATE   // FOR NO KEY UPDATE (PostgreSQL)
}

public enum WaitOption {
    NONE,           // No wait clause
    NOWAIT,         // NOWAIT
    SKIP_LOCKED,    // SKIP LOCKED
    WAIT            // WAIT n (Oracle)
}
```

**Grammar Changes Required** (JavaCC):
```javacc
void ForUpdateClause() :
{
    ForUpdateClause forUpdate = new ForUpdateClause();
    Table table;
    List<Table> tables = new ArrayList<>();
}
{
    <K_FOR>
    (
        <K_UPDATE> { forUpdate.setMode(ForUpdateMode.UPDATE); }
        |
        <K_SHARE> { forUpdate.setMode(ForUpdateMode.SHARE); }
        |
        <K_KEY> <K_SHARE> { forUpdate.setMode(ForUpdateMode.KEY_SHARE); }
        |
        <K_NO> <K_KEY> <K_UPDATE> { forUpdate.setMode(ForUpdateMode.NO_KEY_UPDATE); }
    )
    [
        <K_OF> table = Table() { tables.add(table); }
        ( "," table = Table() { tables.add(table); } )*
        { forUpdate.setTables(tables); }
    ]
    [
        <K_NOWAIT> { forUpdate.setWaitOption(WaitOption.NOWAIT); }
        |
        <K_SKIP> <K_LOCKED> { forUpdate.setWaitOption(WaitOption.SKIP_LOCKED); }
        |
        <K_WAIT> <S_INTEGER> { 
            forUpdate.setWaitOption(WaitOption.WAIT);
            forUpdate.setWaitSeconds(Integer.parseInt(token.image));
        }
    ]
    { return forUpdate; }
}
```

**Test Cases to Pass**:
```java
@Test
public void testSelectForUpdate() {
    String sql = "SELECT * FROM users FOR UPDATE";
    Select select = (Select) CCJSqlParserUtil.parse(sql);
    PlainSelect ps = (PlainSelect) select.getSelectBody();
    
    assertNotNull(ps.getForUpdate());
    assertTrue(ps.getForUpdate().isForUpdate());
    assertNull(ps.getForUpdate().getTables());  // No OF clause
    assertEquals(WaitOption.NONE, ps.getForUpdate().getWaitOption());
}

@Test
public void testSelectForShare() {
    String sql = "SELECT * FROM users FOR SHARE";
    Select select = (Select) CCJSqlParserUtil.parse(sql);
    PlainSelect ps = (PlainSelect) select.getSelectBody();
    
    assertNotNull(ps.getForUpdate());
    assertTrue(ps.getForUpdate().isForShare());
}

@Test
public void testSelectForUpdateNowait() {
    String sql = "SELECT * FROM users FOR UPDATE NOWAIT";
    Select select = (Select) CCJSqlParserUtil.parse(sql);
    PlainSelect ps = (PlainSelect) select.getSelectBody();
    
    assertNotNull(ps.getForUpdate());
    assertEquals(WaitOption.NOWAIT, ps.getForUpdate().getWaitOption());
}

@Test
public void testSelectForUpdateSkipLocked() {
    String sql = "SELECT * FROM users FOR UPDATE SKIP LOCKED";
    Select select = (Select) CCJSqlParserUtil.parse(sql);
    PlainSelect ps = (PlainSelect) select.getSelectBody();
    
    assertNotNull(ps.getForUpdate());
    assertEquals(WaitOption.SKIP_LOCKED, ps.getForUpdate().getWaitOption());
}

@Test
public void testSelectForUpdateOf() {
    String sql = "SELECT * FROM users u JOIN orders o ON u.id = o.user_id FOR UPDATE OF u";
    Select select = (Select) CCJSqlParserUtil.parse(sql);
    PlainSelect ps = (PlainSelect) select.getSelectBody();
    
    assertNotNull(ps.getForUpdate());
    assertNotNull(ps.getForUpdate().getTables());
    assertEquals(1, ps.getForUpdate().getTables().size());
    assertEquals("u", ps.getForUpdate().getTables().get(0).getName());
}
```

---

#### Task 1.2: Add Transaction Control Statement Support

**Current Problem**:
```java
// All of these throw JSQLParserException: Cannot parse statement
CCJSqlParserUtil.parse("BEGIN");
CCJSqlParserUtil.parse("BEGIN TRANSACTION");
CCJSqlParserUtil.parse("START TRANSACTION");
CCJSqlParserUtil.parse("COMMIT");
CCJSqlParserUtil.parse("COMMIT TRANSACTION");
CCJSqlParserUtil.parse("ROLLBACK");
CCJSqlParserUtil.parse("ROLLBACK TRANSACTION");
CCJSqlParserUtil.parse("SAVEPOINT my_savepoint");
```

**Required Solution**:
1. Create new statement types for transaction control
2. Update JavaCC grammar to parse these statements
3. Support all major database syntaxes (PostgreSQL, MySQL, Oracle, SQL Server)

**Implementation Guide**:
```java
// New statement types to add:
package net.sf.jsqlparser.statement.transaction;

public interface TransactionStatement extends Statement {
    // Marker interface for all transaction-related statements
}

public class BeginTransaction implements TransactionStatement {
    private TransactionAccessMode accessMode;  // READ ONLY, READ WRITE
    private IsolationLevel isolationLevel;
    private boolean work;  // BEGIN WORK (MySQL)
    
    // Getters and setters
}

public class StartTransaction implements TransactionStatement {
    private TransactionAccessMode accessMode;
    private IsolationLevel isolationLevel;
    
    // Getters and setters
}

public class CommitTransaction implements TransactionStatement {
    private boolean work;  // COMMIT WORK
    private boolean chain;  // COMMIT AND CHAIN
    private boolean release;  // COMMIT AND RELEASE
    
    // Getters and setters
}

public class RollbackTransaction implements TransactionStatement {
    private String savepointName;  // ROLLBACK TO SAVEPOINT name
    private boolean work;  // ROLLBACK WORK
    private boolean chain;  // ROLLBACK AND CHAIN
    private boolean release;  // ROLLBACK AND RELEASE
    
    // Getters and setters
}

public class Savepoint implements TransactionStatement {
    private String name;
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}

public enum TransactionAccessMode {
    READ_ONLY,
    READ_WRITE
}

public enum IsolationLevel {
    READ_UNCOMMITTED,
    READ_COMMITTED,
    REPEATABLE_READ,
    SERIALIZABLE
}
```

**Grammar Changes Required** (JavaCC):
```javacc
Statement Statement() :
{
    Statement statement;
}
{
    (
        // Existing statements...
        statement = Select()
        |
        statement = Insert()
        |
        // ... other statements ...
        |
        statement = TransactionStatement()  // ADD THIS
    )
    { return statement; }
}

Statement TransactionStatement() :
{
    Statement statement;
}
{
    (
        statement = BeginTransaction()
        |
        statement = StartTransaction()
        |
        statement = CommitTransaction()
        |
        statement = RollbackTransaction()
        |
        statement = Savepoint()
    )
    { return statement; }
}

BeginTransaction BeginTransaction() :
{
    BeginTransaction stmt = new BeginTransaction();
}
{
    <K_BEGIN> [ <K_TRANSACTION> | <K_WORK> ]
    [
        <K_ISOLATION> <K_LEVEL> IsolationLevel()
        |
        <K_READ> ( <K_ONLY> | <K_WRITE> )
    ]
    { return stmt; }
}

StartTransaction StartTransaction() :
{
    StartTransaction stmt = new StartTransaction();
}
{
    <K_START> <K_TRANSACTION>
    [
        <K_ISOLATION> <K_LEVEL> IsolationLevel()
        |
        <K_READ> ( <K_ONLY> | <K_WRITE> )
    ]
    { return stmt; }
}

CommitTransaction CommitTransaction() :
{
    CommitTransaction stmt = new CommitTransaction();
}
{
    <K_COMMIT> [ <K_TRANSACTION> | <K_WORK> ]
    [
        <K_AND> ( <K_CHAIN> { stmt.setChain(true); } | <K_RELEASE> { stmt.setRelease(true); } )
    ]
    { return stmt; }
}

RollbackTransaction RollbackTransaction() :
{
    RollbackTransaction stmt = new RollbackTransaction();
}
{
    <K_ROLLBACK> [ <K_TRANSACTION> | <K_WORK> ]
    [
        <K_TO> [ <K_SAVEPOINT> ] <S_IDENTIFIER> { stmt.setSavepointName(token.image); }
        |
        <K_AND> ( <K_CHAIN> { stmt.setChain(true); } | <K_RELEASE> { stmt.setRelease(true); } )
    ]
    { return stmt; }
}

Savepoint Savepoint() :
{
    Savepoint stmt = new Savepoint();
}
{
    <K_SAVEPOINT> <S_IDENTIFIER> { stmt.setName(token.image); }
    { return stmt; }
}
```

**Test Cases to Pass**:
```java
@Test
public void testBegin() {
    Statement stmt = CCJSqlParserUtil.parse("BEGIN");
    assertTrue(stmt instanceof BeginTransaction);
}

@Test
public void testBeginTransaction() {
    Statement stmt = CCJSqlParserUtil.parse("BEGIN TRANSACTION");
    assertTrue(stmt instanceof BeginTransaction);
}

@Test
public void testStartTransaction() {
    Statement stmt = CCJSqlParserUtil.parse("START TRANSACTION");
    assertTrue(stmt instanceof StartTransaction);
}

@Test
public void testCommit() {
    Statement stmt = CCJSqlParserUtil.parse("COMMIT");
    assertTrue(stmt instanceof CommitTransaction);
}

@Test
public void testCommitTransaction() {
    Statement stmt = CCJSqlParserUtil.parse("COMMIT TRANSACTION");
    assertTrue(stmt instanceof CommitTransaction);
}

@Test
public void testRollback() {
    Statement stmt = CCJSqlParserUtil.parse("ROLLBACK");
    assertTrue(stmt instanceof RollbackTransaction);
}

@Test
public void testRollbackTransaction() {
    Statement stmt = CCJSqlParserUtil.parse("ROLLBACK TRANSACTION");
    assertTrue(stmt instanceof RollbackTransaction);
}

@Test
public void testSavepoint() {
    Statement stmt = CCJSqlParserUtil.parse("SAVEPOINT my_savepoint");
    assertTrue(stmt instanceof Savepoint);
    assertEquals("my_savepoint", ((Savepoint) stmt).getName());
}

@Test
public void testRollbackToSavepoint() {
    Statement stmt = CCJSqlParserUtil.parse("ROLLBACK TO SAVEPOINT my_savepoint");
    assertTrue(stmt instanceof RollbackTransaction);
    assertEquals("my_savepoint", ((RollbackTransaction) stmt).getSavepointName());
}
```

---

### Priority 2: Important Feature Additions

#### Task 2.1: Add Stored Procedure Call Support

**Current Problem**:
```java
// Both throw JSQLParserException
CCJSqlParserUtil.parse("CALL update_stats()");
CCJSqlParserUtil.parse("EXEC sp_update_inventory @product_id = 123");
```

**Required Solution**:
```java
package net.sf.jsqlparser.statement.call;

public class Call implements Statement {
    private String procedureName;
    private List<Expression> parameters;
    private Map<String, Expression> namedParameters;  // @param = value
    
    public String getProcedureName() { return procedureName; }
    public void setProcedureName(String name) { this.procedureName = name; }
    
    public List<Expression> getParameters() { return parameters; }
    public void setParameters(List<Expression> params) { this.parameters = params; }
    
    public Map<String, Expression> getNamedParameters() { return namedParameters; }
    public void setNamedParameters(Map<String, Expression> params) { this.namedParameters = params; }
}
```

**Test Cases**:
```java
@Test
public void testCall() {
    Statement stmt = CCJSqlParserUtil.parse("CALL update_stats()");
    assertTrue(stmt instanceof Call);
    assertEquals("update_stats", ((Call) stmt).getProcedureName());
}

@Test
public void testCallWithParams() {
    Statement stmt = CCJSqlParserUtil.parse("CALL process_orders(123, 'pending')");
    assertTrue(stmt instanceof Call);
    Call call = (Call) stmt;
    assertEquals("process_orders", call.getProcedureName());
    assertEquals(2, call.getParameters().size());
}

@Test
public void testExec() {
    Statement stmt = CCJSqlParserUtil.parse("EXEC sp_update_inventory @product_id = 123");
    assertTrue(stmt instanceof Call);
}
```

---

#### Task 2.2: Add CREATE INDEX Statement Type

**Current Problem**:
```java
// Parses but returns generic Statement, not CreateIndex
Statement stmt = CCJSqlParserUtil.parse("CREATE INDEX idx ON users(email)");
// Cannot use instanceof CreateIndex because that class doesn't exist
```

**Required Solution**:
```java
package net.sf.jsqlparser.statement.create.index;

public class CreateIndex implements Statement {
    private Index index;
    private Table table;
    private List<String> columnNames;
    private boolean unique;
    private boolean concurrently;  // PostgreSQL
    private String indexType;  // BTREE, HASH, GIN, GIST, etc.
    private String using;  // USING clause
    private Expression where;  // WHERE clause for partial indexes
    
    // Getters and setters
}
```

**Test Cases**:
```java
@Test
public void testCreateIndex() {
    Statement stmt = CCJSqlParserUtil.parse("CREATE INDEX idx ON users(email)");
    assertTrue(stmt instanceof CreateIndex);
    CreateIndex ci = (CreateIndex) stmt;
    assertEquals("idx", ci.getIndex().getName());
    assertEquals("users", ci.getTable().getName());
}

@Test
public void testCreateUniqueIndex() {
    Statement stmt = CCJSqlParserUtil.parse("CREATE UNIQUE INDEX idx ON users(username)");
    assertTrue(stmt instanceof CreateIndex);
    assertTrue(((CreateIndex) stmt).isUnique());
}
```

---

#### Task 2.3: Add RENAME Statement Type

**Current Problem**:
```java
// Parses but returns generic Statement
CCJSqlParserUtil.parse("RENAME TABLE users TO customers");
CCJSqlParserUtil.parse("ALTER TABLE users RENAME TO customers");
```

**Required Solution**:
```java
package net.sf.jsqlparser.statement.rename;

public class Rename implements Statement {
    private RenameType type;  // TABLE, INDEX, COLUMN, etc.
    private String oldName;
    private String newName;
    private Table table;  // For column renames
    
    // Getters and setters
}

public enum RenameType {
    TABLE,
    INDEX,
    COLUMN,
    SCHEMA
}
```

---

#### Task 2.4: Add SET Statement Support

**Current Problem**:
```java
// All throw JSQLParserException
CCJSqlParserUtil.parse("SET search_path TO myschema, public");
CCJSqlParserUtil.parse("SET SESSION sql_mode = 'STRICT_TRANS_TABLES'");
CCJSqlParserUtil.parse("SET time_zone = '+00:00'");
```

**Required Solution**:
```java
package net.sf.jsqlparser.statement.set;

public class SetStatement implements Statement {
    private SetScope scope;
    private String variable;
    private Expression value;
    private List<String> values;  // For SET search_path TO schema1, schema2
    
    // Getters and setters
}

public enum SetScope {
    SESSION,
    GLOBAL,
    LOCAL,
    PERSIST,
    PERSIST_ONLY
}
```

**Test Cases**:
```java
@Test
public void testSet() {
    Statement stmt = CCJSqlParserUtil.parse("SET time_zone = '+00:00'");
    assertTrue(stmt instanceof SetStatement);
    SetStatement set = (SetStatement) stmt;
    assertEquals("time_zone", set.getVariable());
}

@Test
public void testSetSession() {
    Statement stmt = CCJSqlParserUtil.parse("SET SESSION sql_mode = 'STRICT'");
    assertTrue(stmt instanceof SetStatement);
    assertEquals(SetScope.SESSION, ((SetStatement) stmt).getScope());
}
```

---

### Priority 3: Nice-to-Have

#### Task 3.1: Add EXPLAIN Statement Support

**Implementation**:
```java
package net.sf.jsqlparser.statement.explain;

public class Explain implements Statement {
    private Statement targetStatement;
    private boolean analyze;
    private boolean verbose;
    private String format;  // TEXT, JSON, XML, YAML
    
    // Getters and setters
}
```

---

## Success Criteria

Your implementation is successful when:

1. ✅ All 78 test cases in SqlClassifierTest.java pass (currently 50/78)
2. ✅ SELECT FOR UPDATE properly populates getForUpdate() (not null)
3. ✅ Transaction control statements parse without exception
4. ✅ Stored procedure calls (CALL/EXEC) parse successfully
5. ✅ CREATE INDEX returns instanceof CreateIndex
6. ✅ SET statements parse without exception
7. ✅ No breaking changes to existing API (use @Deprecated for old methods)
8. ✅ All changes compile and pass existing JSqlParser test suite

---

## Implementation Notes

### File Locations in JSqlParser

- **Grammar file**: `src/main/jjtree/net/sf/jsqlparser/parser/JSqlParserCC.jjt`
- **Statement classes**: `src/main/java/net/sf/jsqlparser/statement/`
- **Select classes**: `src/main/java/net/sf/jsqlparser/statement/select/`
- **Tests**: `src/test/java/net/sf/jsqlparser/`

### Build Commands

```bash
# Build the project
mvn clean install

# Run specific test
mvn test -Dtest=SelectTest

# Generate parser from grammar
mvn javacc:jjtree-javacc
```

### Compatibility Guidelines

1. **Don't break existing API** - Existing methods must continue to work
2. **Use @Deprecated** - Mark old methods as deprecated, don't remove them
3. **Add new methods** - Create new, properly-named methods for new functionality
4. **Update toString()** - Ensure all new statement types have proper toString()
5. **Update accept()** - Implement visitor pattern for all new statement types

---

## Testing Strategy

### Unit Tests to Add

For each new statement type:
1. Basic parsing test
2. Full syntax test (all optional clauses)
3. toString() test (parse → toString → parse should be idempotent)
4. Visitor pattern test
5. Edge case tests (empty, null, complex nesting)

### Integration Tests

Run the SqlClassifierTest suite from OJP project:
```bash
git clone https://github.com/Open-J-Proxy/ojp
cd ojp
git checkout copilot/analyze-read-write-splitting
mvn test -pl ojp-server -Dtest=SqlClassifierTest
```

Expected result: **78/78 tests pass** (currently 50/78)

---

## Deliverables

1. **Source Code** - All new statement classes and grammar changes
2. **Unit Tests** - Comprehensive tests for each new feature
3. **Documentation** - JavaDoc for all new public APIs
4. **Migration Guide** - How to update code using old APIs
5. **CHANGELOG** - Document all additions and changes
6. **README** - Update with new supported SQL statement types

---

## Example: Complete Implementation Flow

Here's how to implement SELECT FOR UPDATE fix:

### Step 1: Create ForUpdateClause class
```java
// src/main/java/net/sf/jsqlparser/statement/select/ForUpdateClause.java
package net.sf.jsqlparser.statement.select;

public class ForUpdateClause {
    private ForUpdateMode mode = ForUpdateMode.UPDATE;
    private List<Table> tables = null;
    private WaitOption waitOption = WaitOption.NONE;
    private Integer waitSeconds = null;
    
    // Getters, setters, toString(), etc.
}
```

### Step 2: Add enum types
```java
// src/main/java/net/sf/jsqlparser/statement/select/ForUpdateMode.java
public enum ForUpdateMode {
    UPDATE, SHARE, KEY_SHARE, NO_KEY_UPDATE
}

// src/main/java/net/sf/jsqlparser/statement/select/WaitOption.java
public enum WaitOption {
    NONE, NOWAIT, SKIP_LOCKED, WAIT
}
```

### Step 3: Update PlainSelect
```java
// src/main/java/net/sf/jsqlparser/statement/select/PlainSelect.java
public class PlainSelect implements SelectBody {
    // ... existing fields ...
    
    private ForUpdateClause forUpdate;  // ADD THIS
    
    public ForUpdateClause getForUpdate() { return forUpdate; }
    public void setForUpdate(ForUpdateClause forUpdate) { this.forUpdate = forUpdate; }
    
    // Deprecate old methods
    @Deprecated
    public Table getForUpdateTable() {
        throw new UnsupportedOperationException("Use getForUpdate() instead");
    }
}
```

### Step 4: Update grammar (JavaCC)
```javacc
// In SelectBody production, add:
PlainSelect SelectBody() :
{
    PlainSelect select = new PlainSelect();
    ForUpdateClause forUpdate;
}
{
    // ... existing grammar ...
    
    // Add at end:
    [ forUpdate=ForUpdateClause() { select.setForUpdate(forUpdate); } ]
    
    { return select; }
}

ForUpdateClause ForUpdateClause() :
{
    ForUpdateClause clause = new ForUpdateClause();
    // ... implementation from earlier ...
}
{
    // ... grammar from earlier ...
}
```

### Step 5: Write tests
```java
// src/test/java/net/sf/jsqlparser/statement/select/SelectForUpdateTest.java
public class SelectForUpdateTest {
    @Test
    public void testSelectForUpdate() {
        // ... test from earlier ...
    }
}
```

### Step 6: Update visitor
```java
// If visitor pattern is used, implement:
public interface SelectVisitor {
    void visit(ForUpdateClause forUpdate);
}
```

### Step 7: Build and test
```bash
mvn clean install
mvn test -Dtest=SelectForUpdateTest
```

---

## Questions to Consider

1. Should we support database-specific syntax in separate classes or unified?
2. How should we handle backward compatibility for getForUpdateTable()?
3. Should transaction statements be in a separate package?
4. What's the visitor pattern structure for new statement types?

---

## Additional Resources

- **JSqlParser Documentation**: https://github.com/JSQLParser/JSqlParser/wiki
- **JavaCC Tutorial**: https://javacc.github.io/javacc/
- **SQL Standards**: ISO/IEC 9075 (SQL:2016)
- **PostgreSQL Docs**: https://www.postgresql.org/docs/current/sql-select.html
- **Oracle Docs**: https://docs.oracle.com/en/database/oracle/oracle-database/
- **MySQL Docs**: https://dev.mysql.com/doc/refman/8.0/en/

---

## Timeline Estimate

- **SELECT FOR UPDATE fix**: 4-6 hours
- **Transaction control statements**: 6-8 hours
- **Stored procedure support**: 4-6 hours
- **DDL statement types**: 6-8 hours
- **SET statement support**: 3-4 hours
- **Testing and documentation**: 8-10 hours

**Total**: ~35-45 hours of development time

---

## Final Notes

This is a high-value enhancement that would benefit the entire JSqlParser community. Many projects need these features for:
- Database proxies and connection routers
- Query analysis tools
- ORM frameworks
- SQL audit logging
- Performance monitoring tools

Your implementation will enable all these use cases. Good luck!

---

## Contact

If you have questions or need clarification on any requirements, please refer to:
- **Detailed Report**: JSQLPARSER_LIMITATIONS_REPORT.md
- **Test Suite**: SqlClassifierTest.java
- **GitHub Issue**: JSQLPARSER_GITHUB_ISSUE.md

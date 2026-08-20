# Analysis: Full SQLWarning Transfer (message + sqlState + vendorCode + warning chain)

**Context:** PR #584 ("fix: fix SQLWarning serialization between server and JDBC driver") fixes a crash
where `getWarnings()` on `Connection` and `Statement` failed because `SQLWarning` — being a
`Throwable` subclass — cannot be transported by `ProtoConverter`'s primitive/Map/List/Properties
contract. The fix extracts only the warning's text message on the server and reconstructs a plain
`new SQLWarning(message)` on the client.

This document analyses the limitations of that approach and proposes a complete solution that
preserves all `SQLWarning` attributes, including the chained warning list.

---

## 1. What the Current PR Loses

A `SQLWarning` inherits from `SQLException`, which inherits from `Exception`. Its full data model is:

| Attribute | Source | JDBC getter | Example value |
|---|---|---|---|
| `message` | `Throwable` | `getMessage()` | "Data truncated for column 'x'" |
| `sqlState` | `SQLException` | `getSQLState()` | `"01000"`, `"01003"` |
| `vendorCode` | `SQLException` | `getErrorCode()` | `1292` (MySQL) |
| next warning | `SQLWarning` | `getNextWarning()` | another `SQLWarning` or `null` |

PR #584 transfers only `message`. Callers that inspect `sqlState` or `vendorCode` (frameworks,
monitoring tools, ORM mappers, custom code) will silently receive empty/zero values. The warning
chain (`getNextWarning()`) is completely dropped: if a database operation produces two or more
warnings, only the first one's message reaches the application, and even that loses its SQLSTATE
and vendor code.

### Real-world examples that break

- **MySQL / MariaDB** routinely chain multiple `01000` (General warning) warnings after a stored
  procedure call. Applications that iterate the chain to collect all warnings will see only one
  warning with an empty SQLSTATE.
- **PostgreSQL** uses `SQLSTATE 01P01` (deprecated feature) and `01000` (warning) with meaningful
  messages and non-zero vendor codes.
- **SQL Server** uses `SQLServerWarning` (a vendor-specific subclass) and attaches error numbers
  as the vendor code. PR #584 correctly identifies that the subclass cannot be serialised, but
  the vendorCode is still valuable and currently dropped.
- **Oracle** uses `OracleSQLWarning` (another subclass) and carries SQLSTATE codes from the
  `01xxx` family.

---

## 2. Root Cause

The generic `CallResourceAction` intercepts any `SQLWarning` returned by a reflected method call
and delegates serialisation to `ProtoConverter.toParameterValue()`. That converter supports only
primitives, `Map`, `List`, `Properties`, and null. `SQLWarning` — a Throwable — falls outside
that contract, so the call throws an `UnsupportedOperationException` at runtime.

PR #584 works around this by extracting the message string before `ProtoConverter` sees the
object. A clean fix must extract *all* attributes before that boundary.

---

## 3. Proposed Solution

### 3.1 Add a dedicated proto message for the warning chain

Extend `ojp-grpc-commons/src/main/proto/StatementService.proto` with a new message that mirrors
`SQLWarning`'s data model:

```protobuf
// Represents one node in a SQLWarning chain.
// Mirrors java.sql.SQLWarning: message + sqlState + vendorCode + optional next.
message SqlWarningProto {
    string message    = 1;
    string sqlState   = 2;
    int32  vendorCode = 3;
    // Repeated field holds the rest of the chain in order.
    // Using repeated instead of a recursive 'next' field keeps proto generation simple
    // and avoids unbounded nesting depth in the generated Java classes.
    repeated SqlWarningProto chain = 4;
}
```

Using `repeated SqlWarningProto chain` at the top-level node avoids the awkward recursive
self-reference while still carrying the full ordered chain. The alternative — a single `repeated
SqlWarningProto warnings` field — also works and is arguably cleaner because it makes the
top-level node identical to the rest. Either approach is fine; the flat `repeated` list is
simpler to iterate on both sides.

A flat list is preferred:

```protobuf
// Transports the full chain returned by Connection/Statement.getWarnings().
message SqlWarningList {
    repeated SqlWarningEntry entries = 1;
}

message SqlWarningEntry {
    string message    = 1;
    string sqlState   = 2;
    int32  vendorCode = 3;
}
```

### 3.2 Intercept on the server — `CallResourceAction`

Replace the current message-only extraction block:

```java
// Current (PR #584):
if (resultFirstLevel instanceof SQLWarning warning) {
    resultFirstLevel = warning.getMessage();
}
```

With a full chain extraction that converts to a `List<Map<String, Object>>` **or** to the new
proto message (preferred). Using the new proto:

```java
if (resultFirstLevel instanceof SQLWarning warning) {
    resultFirstLevel = toWarningList(warning);   // returns SqlWarningList proto
}
```

Helper method on the server side:

```java
private SqlWarningList toWarningList(SQLWarning head) {
    SqlWarningList.Builder listBuilder = SqlWarningList.newBuilder();
    SQLWarning cursor = head;
    while (cursor != null) {
        SqlWarningEntry.Builder entry = SqlWarningEntry.newBuilder()
            .setMessage(cursor.getMessage() != null ? cursor.getMessage() : "")
            .setVendorCode(cursor.getErrorCode());
        if (cursor.getSQLState() != null) {
            entry.setSqlState(cursor.getSQLState());
        }
        listBuilder.addEntries(entry);
        cursor = cursor.getNextWarning();
    }
    return listBuilder.build();
}
```

The `SqlWarningList` proto message must then be handled by `ProtoConverter` / transported as
bytes (same pattern as `Map`/`List` via `ProtoSerialization`). Because proto messages are not
currently in `ProtoConverter`'s supported type set, the cleanest path is to teach
`ProtoConverter.toParameterValue()` to serialise `SqlWarningList` to bytes using
`SqlWarningList.toByteArray()`, and add a matching deserialisation branch that recognises the
wire format by reading the proto tag.

An alternative that avoids modifying `ProtoConverter` is to serialize `SqlWarningList` using the
existing `ProtoSerialization` container infrastructure:
- Convert the chain to a `List<Map<String, Object>>` (message, sqlState, vendorCode per entry).
- `ProtoSerialization` already serialises `List<Map<String, Object>>` natively.
- No new proto fields or converter changes needed in the shared module.

This alternative is described in Section 3.4.

### 3.3 Reconstruct on the client — `Connection` and `Statement`

Replace both `getWarnings()` implementations (currently identical except for the validity check):

```java
// Current (PR #584):
String message = this.callProxy(CallType.CALL_GET, "Warnings", String.class);
return message == null ? null : new SQLWarning(message);

// Proposed:
List<Map<String, Object>> entries = this.callProxy(CallType.CALL_GET, "Warnings", List.class);
return buildWarningChain(entries);
```

Helper method (can live in a shared utility class or be duplicated in `Connection` and
`Statement` since the method is small):

```java
private static SQLWarning buildWarningChain(List<Map<String, Object>> entries) {
    if (entries == null || entries.isEmpty()) {
        return null;
    }
    SQLWarning head = null;
    SQLWarning tail = null;
    for (Map<String, Object> entry : entries) {
        String message    = (String)  entry.get("message");
        String sqlState   = (String)  entry.get("sqlState");
        Number vendorCode = (Number)  entry.get("vendorCode");
        SQLWarning w = new SQLWarning(
            message,
            sqlState,
            vendorCode != null ? vendorCode.intValue() : 0
        );
        if (head == null) {
            head = w;
            tail = w;
        } else {
            tail.setNextWarning(w);
            tail = w;
        }
    }
    return head;
}
```

### 3.4 Alternative: List<Map> without new proto message (lower-risk path)

If the team wants to avoid modifying `StatementService.proto` (keeping the change contained to
the server and driver modules), `ProtoSerialization` already handles `List<Map<String, Object>>`.
The serialization step on the server side becomes:

```java
if (resultFirstLevel instanceof SQLWarning warning) {
    resultFirstLevel = toWarningListMap(warning);  // returns List<Map<String, Object>>
}

private List<Map<String, Object>> toWarningListMap(SQLWarning head) {
    List<Map<String, Object>> list = new ArrayList<>();
    SQLWarning cursor = head;
    while (cursor != null) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("message",    cursor.getMessage());
        entry.put("sqlState",   cursor.getSQLState());
        entry.put("vendorCode", cursor.getErrorCode());
        list.add(entry);
        cursor = cursor.getNextWarning();
    }
    return list;
}
```

`ProtoConverter.toParameterValue()` already recognises `List` and delegates to
`ProtoSerialization.serializeToTransport()`, so this list will be transported without any change
to the converter or the proto schema.

On the client, the `callProxy` call with `List.class` (or `Object.class`) retrieves the list,
and `buildWarningChain()` from Section 3.3 reconstructs the chain.

**Trade-off versus the proto message approach:**

| Aspect | Proto message (`SqlWarningList`) | List\<Map\> |
|---|---|---|
| Type-safety | Strong; proto schema enforces field names and types | Weak; relies on string key conventions |
| Backward compat | Requires proto regeneration in both modules | No proto changes |
| Schema clarity | Explicit contract visible in `.proto` | Implicit; convention documented only in code |
| Complexity | Medium; adds 2 proto messages | Low; uses existing infrastructure |
| Risk | Low; proto addition is additive | Very low; no shared-module changes |

**Recommendation:** The `List<Map>` alternative is the safest immediate fix. The proto message
approach is the right long-term design and should be adopted once the team is ready to cut a
proto change.

---

## 4. Null Handling Edge Cases

- **`getWarnings()` returns `null`:** The server's `method.invoke()` returns `null`. The
  `instanceof SQLWarning` check is false, so `resultFirstLevel` stays `null`. `ProtoConverter`
  serialises this as the null proto value. Client receives `null` list → returns `null`.
  ✅ No change needed.

- **`warning.getMessage()` is `null`:** Some drivers return warnings with a null message (SQLSTATE
  carries the diagnostic). The helper must guard with an empty string default to avoid NPE in
  proto string fields.

- **`warning.getSQLState()` is `null`:** Similar guard required. `getSQLState()` is nullable per
  JDBC spec.

- **Very long chains:** Uncommon but possible (some MySQL routines emit dozens of `01000`
  warnings). The list approach is naturally bounded by transport; proto repeated fields have no
  practical limit at this scale. Either approach handles this correctly.

---

## 5. Files to Change

| Module | File | Change |
|---|---|---|
| `ojp-grpc-commons` | `StatementService.proto` | Add `SqlWarningList` + `SqlWarningEntry` messages (proto approach only) |
| `ojp-server` | `CallResourceAction.java` | Replace message-string extraction with full chain extraction |
| `ojp-jdbc-driver` | `Connection.java` | Replace `String.class` proxy call with list-based reconstruction |
| `ojp-jdbc-driver` | `Statement.java` | Same as Connection |
| `ojp-jdbc-driver` | (new) `SqlWarningUtils.java` | `buildWarningChain(List<Map<String, Object>>)` shared helper |

The `ojp-grpc-commons` change is required only for the proto approach (Section 3.1). The
`List<Map>` alternative (Section 3.4) touches only `ojp-server` and `ojp-jdbc-driver`.

---

## 6. Test Strategy

- **Unit test (`ProtoConverter` / `ProtoSerialization`):** round-trip a `List<Map>` containing
  warning attributes through `serializeToTransport` / `deserializeFromTransport`.
- **Integration test (H2):** execute a statement that generates a `SQLWarning` (e.g., a data
  truncation or `CALL` with a SIGNAL). Call `getWarnings()` and assert the SQLSTATE and
  vendorCode are non-empty and the chain length matches expectations.
- **Integration test (MySQL/MariaDB):** stored procedure that emits multiple `SIGNAL` warnings.
  Assert that `getNextWarning()` traversal reaches all warnings in order.

---

## 7. Summary

PR #584 is a correct minimal fix that eliminates the serialization crash. It is a safe foundation.
The next step is to extend it with the full attribute set. The `List<Map>` approach in Section 3.4
is the recommended immediate implementation: it requires no proto changes, reuses existing
transport infrastructure, and completely restores `sqlState`, `vendorCode`, and the warning chain.
The proto-based approach (Section 3.1–3.3) should follow as a separate, planned change to establish
a well-typed contract in the gRPC schema.

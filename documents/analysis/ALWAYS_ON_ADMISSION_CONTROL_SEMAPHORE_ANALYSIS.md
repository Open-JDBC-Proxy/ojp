# Always-On Admission Control Semaphore (No Flag) — Analysis and Implementation Rationale

## Context

In stress runs of `H2OpenLoopLatencyIntegrationTest` with 1000 concurrent requests, results reported in PR discussion showed:

- **Slow Query Segregation disabled:** 666 failures
- **Slow Query Segregation enabled:** 68 failures

This strongly indicated that the semaphore-based admission behavior used by Slow Query Segregation was reducing timeout storms and improving latency under burst load.

## What Was Happening Before

- **Non-XA + Slow Query Segregation disabled:** no semaphore gating (manager created disabled).
- **Non-XA + Slow Query Segregation enabled:** semaphore gating via `SlotManager` (slow/fast split + borrowing).
- **XA paths:** semaphore gating already used even when Slow Query Segregation was disabled.

That mismatch meant non-XA workloads could still flood `DataSource.getConnection()` under bursty open-loop traffic.

## Decision

Implement **always-on admission control** for non-XA, with **no rollout flag**.

- If Slow Query Segregation is enabled: keep current behavior (slow/fast segregation).
- If Slow Query Segregation is disabled: still apply semaphore gating in **admission-control-only** mode.

This keeps one gate in the request path and avoids introducing a second, stacked server-side semaphore.

## Implementation Summary

### 1) Always-on manager creation for non-XA

`CreateSlowQuerySegregationManagerAction` now creates an enabled manager even when Slow Query Segregation is disabled (admission-control-only mode), instead of creating a disabled manager.

### 2) Admission-control-only execution mode

`AdmissionControlManager` now supports an internal mode where:

- gating is enabled,
- operations acquire/release a uniform fast-slot permit,
- slow/fast classification is bypassed.

When Slow Query Segregation is enabled, existing classification behavior remains unchanged.

### 3) Slot allocation fix for 0% slow slots

`SlotManager` now treats `slowSlotPercentage=0` as:

- `slowSlots=0`
- `fastSlots=totalSlots`

This enables true all-fast admission control for the no-segregation mode.

## Why This Is the Right Tradeoff

1. Prevents burst amplification from reaching pool acquisition directly.
2. Preserves current slow-query behavior when that feature is enabled.
3. Keeps implementation simple: one admission mechanism reused across modes.
4. Removes previous inconsistency between XA and non-XA behavior.

## Tests and Validation That Led to/Back This Solution

### Reported benchmark signal (PR discussion)

- 1000 concurrent requests:
  - disabled segregation: high failure count (666)
  - enabled segregation: much lower failure count (68)

This provided the practical signal that admission control was the key differentiator.

### Unit tests updated/added

- `SlotManagerTest`
  - updated 0% slow-slot expectation to all-fast (`0 slow / total fast`).
- `XaSlotManagementTest`
  - updated expectations for 0% mode and verified admission-control-only mode.
- `AdmissionControlManagerTest`
  - added coverage for admission-control-only behavior and status reporting.

## Operational Notes

- Rollout is **always ON** as requested.
- No new configuration flag is introduced.
- Slow Query Segregation semantics are preserved when enabled.

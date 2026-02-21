# Unified Fact Store

**Branch:** `meta-rules`
**Status:** Design / Pre-implementation
**Depends on:** Current meta-rules implementation (Phases 1-5 complete)
**Issue:** `src-8z0`

---

## Summary

Replace O'Doyle's "discard unmatched facts" behavior with a **unified fact store** that
retains ALL facts (domain and meta) regardless of whether a matching rule exists. The RETE
network becomes a downstream index over the store, not the source of truth for fact existence.

This eliminates keeper rules, enables full retroactive initialization, and provides a
temporal history of all facts that have ever existed in the session.

---

## Motivation

The keeper-rule pattern is the single biggest UX wart in the meta-rules system. Every example
requires 5-8 boilerplate rules whose sole purpose is ensuring facts survive long enough for
dynamically-generated rules to see them. The root cause is O'Doyle's core design choice:
`insert` silently discards facts that match no rule's alpha node.

The unified fact store addresses this by storing every fact unconditionally. The "discard"
behavior becomes "store but don't index" — facts exist in the store and are replayed into
the RETE network whenever a matching rule is added.

---

## Goals

1. **Eliminate keeper rules** — Domain facts survive without boilerplate rules watching them.
2. **Unified storage** — One canonical store for all facts (domain + meta), replacing the
   separate `rule-meta-store` bypass mechanism.
3. **Temporal history** — Every fact value is preserved with tombstone semantics on retraction.
4. **Retroactive replay** — When `add-rule` or `add-rule!` introduces a new rule, stored
   facts matching its alpha pattern are automatically replayed.
5. **Self-managing memory** — Janitor rules can use `purge-store` to hard-delete stale data.
6. **Observability** — `query-store` provides direct access to all stored facts, including
   historical values.

---

## Non-Goals

- Datomic-level temporal queries (no `as-of` or `since` semantics)
- Automatic garbage collection / TTL (janitor rules handle this explicitly)
- Transaction log / ACID semantics
- Cross-session fact sharing (see `src-kwv` for that direction)

---

## Breaking Changes

This is a **backward-incompatible** change to the fork. The meta-rules branch is a personal
fork; backward compatibility with upstream O'Doyle is not a constraint.

Changes from current behavior:
1. **`insert` never discards** — All facts are stored, even with no matching rule.
2. **`retract` tombstones** — Retracted facts are marked dead, not deleted. RETE behavior
   is unchanged (fact is removed from the network), but the store retains history.
3. **Memory usage increases** — Sessions accumulate facts over time. Use `purge-store` for
   explicit cleanup.
4. **`rule-meta-store` is removed** — Unified fact store replaces it. Meta-facts and domain
   facts use the same storage mechanism.

---

## Data Model

### Fact Store Structure

```clojure
;; Top-level: nested map indexed by [id attr]
;; Each entry contains the value history and liveness status
{id {attr {:current val        ;; latest live value (nil if tombstoned)
           :alive?  true/false ;; false = tombstoned
           :history [val1 val2 val3]  ;; all values ever held, chronological
           }}}
```

### Secondary Index (for replay performance)

```clojure
;; Attribute index: {attr -> #{[id value] ...}}
;; Only indexes LIVE (non-tombstoned) facts
;; Maintained incrementally on insert/retract
{attr #{[id1 val1] [id2 val2] ...}}
```

### Partitioning

The store internally distinguishes meta-facts (attributes in the `::o/` namespace) from
domain facts. Both use the same data structure, but query APIs default to excluding meta-facts:

```clojure
(defn meta-attr? [attr]
  (= (namespace attr) (namespace ::o/rule)))
```

---

## API

### Modified: `insert`

```clojure
(o/insert session id attr val)
;; => session with:
;;    1. Fact written to store (always)
;;    2. Fact inserted into RETE network (if matching alpha node exists)
```

Every `insert` unconditionally writes to the fact store:
- If `[id attr]` already has a live value, the old value is appended to `:history` and the
  new value becomes `:current`.
- `:alive?` is set to `true`.
- The attribute secondary index is updated.
- Then, the existing RETE insertion logic runs as before.

### Modified: `retract`

```clojure
(o/retract session id attr)
;; => session with:
;;    1. Fact tombstoned in store (current value moved to history, :alive? false)
;;    2. Fact removed from RETE network (as before)
```

Retraction **does not delete** the store entry. It:
- Appends `:current` to `:history`.
- Sets `:current` to `nil` and `:alive?` to `false`.
- Removes the `[id val]` pair from the attribute secondary index.
- Runs existing RETE retraction logic.

### New: `query-store`

```clojure
;; All live domain facts as {id {attr val}}
(o/query-store session)

;; Include meta-facts (::o/ attributes)
(o/query-store session {:include-meta? true})

;; Include historical/tombstoned values
(o/query-store session {:include-history? true})
;; => {id {attr {:current val, :alive? bool, :history [...]}}}

;; Both flags
(o/query-store session {:include-meta? true, :include-history? true})
```

Default behavior returns a simple `{id {attr val}}` map of live domain facts only.
With `:include-history? true`, returns the full entry maps including history and tombstones.

### New: `purge-store`

```clojure
;; Hard-delete a specific [id attr] from the store (including all history)
(o/purge-store session id attr)

;; Hard-delete all facts for a set of attributes
(o/purge-store session #{:temp/x :temp/y :temp/z})
```

Purge permanently removes entries from the store, including all history and tombstones.
This is the only way to reclaim memory. It also removes the fact from the RETE network if
it was live.

### New: `purge-store!`

```clojure
;; Inside a :then or :then-finally block:
(o/purge-store! id attr)
(o/purge-store! #{:temp/x :temp/y})
```

Deferred version of `purge-store` for use inside rule bodies (same pattern as `insert!`,
`retract!`, `add-rule!`).

### Modified: `add-rule` (retroactive replay generalized)

```clojure
(o/add-rule session rule)
;; => session with rule added AND all matching stored facts replayed into it
```

Currently, `add-rule` calls `initialize-rule-against-session` which replays facts from
`id-attr-nodes` and `rule-meta-store`. This is generalized:

1. Determine which attributes the new rule's `:what` block watches.
2. Look up those attributes in the fact store's secondary index.
3. For each matching `[id value]`, insert the fact into the new rule's alpha/beta nodes.

This replaces the current `rule-meta-store` replay mechanism entirely.

### Removed: `rule-meta-store`

The `rule-meta-store` field on `Session` is removed. Its functionality is subsumed by the
unified fact store. Meta-facts are stored as ordinary facts with `::o/` attributes.

### Removed: `query-all-meta`

Replaced by `(o/query-store session {:include-meta? true})`. The existing `query-all-meta`
function becomes a thin wrapper for backward compatibility during the transition, then
is deprecated.

---

## Implementation Plan

### Phase A: Fact Store Data Structure

- Define the fact store as a new field on `Session`: `fact-store` (nested map).
- Define the attribute index as a new field: `attr-index` (`{attr -> #{[id val]}}`).
- Implement internal helper functions:
  - `store-insert [fact-store attr-index id attr val]` → updated `[fact-store attr-index]`
  - `store-retract [fact-store attr-index id attr]` → updated `[fact-store attr-index]`
  - `store-purge [fact-store attr-index id attr]` → updated `[fact-store attr-index]`
  - `store-purge-attrs [fact-store attr-index attr-set]` → updated `[fact-store attr-index]`
  - `store-query [fact-store opts]` → result map
  - `store-lookup-attr [attr-index attr]` → `#{[id val] ...}`
- All existing tests must still pass (store is additive, doesn't change RETE behavior).

### Phase B: Wire Store into Insert/Retract

- Modify `insert` to call `store-insert` before RETE insertion.
- Modify `retract` to call `store-retract` before RETE retraction.
- Modify `insert!` and `retract!` (the mutable volatile versions) to also update the store.
- Remove `rule-meta-store` population from `add-rule` (store handles it now).
- All existing tests must still pass.

### Phase C: Generalize Retroactive Replay

- Modify `initialize-rule-against-session` to use the attribute index instead of scanning
  `id-attr-nodes` and `rule-meta-store` separately.
- On `add-rule`, look up the new rule's watched attributes in `attr-index`, replay matching
  live facts.
- Remove `rule-meta-store` field from `Session`.
- Write tests:
  - Facts inserted before ANY matching rule exists are replayed when the rule is added.
  - Keeper rules are no longer needed (rewrite an existing example without them).
  - Meta-facts still work (replayed from store instead of `rule-meta-store`).

### Phase D: Public API

- Implement `query-store` with options map.
- Implement `purge-store` (specific `[id attr]` and attribute-set arities).
- Implement `purge-store!` (deferred version).
- Deprecate `query-all-meta` (thin wrapper over `query-store`).
- Write tests:
  - `query-store` returns live facts, excludes meta by default.
  - `query-store` with `:include-meta?` returns meta-facts.
  - `query-store` with `:include-history?` returns full history including tombstones.
  - `purge-store` permanently removes entries and reclaims the index slot.
  - `purge-store!` works from inside rule bodies.

### Phase E: Integration & Examples

- Rewrite existing examples (access control, elemental combos, process orchestrator)
  without keeper rules.
- Write a "janitor rule" example that uses `purge-store!` to clean stale data.
- Write a "temporal query" example that uses `query-store` with history to analyze
  how an entity's attributes changed over time.
- Performance test: insert 10k facts, add rules dynamically, measure replay time.
- Update SPEC.md with findings.

---

## Relationship to Other Issues

| Issue | Relationship |
|-------|-------------|
| `src-8z0` (this issue) | Primary implementation |
| `src-hhd` Rule provenance graph | Provenance data lives in the fact store. `query-store {:include-meta? true}` is the foundation for walking `::o/derived-from` chains. |
| `src-09r` ::o/rule spec conflict | Must be resolved before or during Phase B, since meta-facts move into the unified store. |
| `src-jv1` Lazy metadata insertion | Partially obviated — the fact store always stores meta-facts. The "zero cost when unused" goal shifts to "zero RETE cost when no meta-rule exists" (which is already true). |
| `src-357` Self-modifying rules | Fact history enables rules to observe their own past state and adjust. Direct enabler. |
| `src-s8g` Schema validation | Schema rules can validate facts in the store, not just RETE-indexed facts. |
| `src-kwv` Distributed sessions | Fact store serialization is a prerequisite for inter-session fact exchange. |

---

## Observability Strategy

Two complementary systems (connects to interview discussion about debuggability):

### 1. Rule Provenance Graph (issue `src-hhd`)

Walks `::o/derived-from` chains in the fact store to answer "why does this rule exist?"
Returns a DAG of rule derivation relationships.

### 2. Fact Event Log (new, deferred to post-implementation)

The fact store's history already provides a per-`[id attr]` timeline. A future enhancement
could add timestamps and operation types (`:insert`, `:retract`, `:purge`) to history
entries, enabling a full audit trail. This is NOT in scope for the initial implementation
but the data model accommodates it (history entries could be extended from plain values to
`{:value val, :op :insert, :t timestamp}` maps).

---

## Open Questions

1. **History entry format**: Should history entries be plain values `[v1 v2 v3]` or
   annotated `[{:value v1 :op :insert} {:value v2 :op :retract} ...]`? Plain is simpler
   for v1. Annotated enables the event log without migration. Decision: start plain, migrate
   later if needed.

2. **`query-all` behavior**: Currently returns domain facts from RETE memory nodes. Should
   it also return facts that are in the store but NOT in the RETE? Probably not — `query-all`
   is rule-scoped (it returns matches for a specific rule). `query-store` is the fact-scoped
   API. Keep them separate.

3. **`reset!` interaction**: `reset!` currently replaces all facts for an id. It should
   interact with the store the same way: retract all existing `[id *]` facts (tombstone),
   then insert the new ones.

4. **Bulk insert performance**: If `insert` now always writes to the store, bulk inserts
   (common at session initialization) do twice the work. Profile this. The store write is
   a map `assoc-in` — likely negligible, but verify.

5. **Store size observability**: Should there be a `(o/store-stats session)` function
   returning counts (total facts, live facts, tombstoned, by-attribute breakdown)?
   Useful for monitoring janitor rule effectiveness.

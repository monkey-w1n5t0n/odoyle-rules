# Unified fact store design interview

**Date:** 2026-02-21
**Branch:** `meta-rules`
**Issue:** `src-8z0` — Fact retention store (eliminate keeper rules)

## Summary

Design interview session to flesh out `src-8z0`. Started as "eliminate keeper rules" and
evolved into a much larger architectural change: a **unified fact store** that replaces
O'Doyle's core "discard unmatched facts" behavior. The RETE network becomes a downstream
index over a persistent store, not the source of truth for fact existence.

Full spec: `SPEC-fact-store.md`

---

## The Problem

Every meta-rules example requires 5-8 keeper rules — boilerplate rules with only a `:what`
block whose sole purpose is ensuring facts survive in the RETE network until dynamically
generated rules arrive. This is because O'Doyle's `insert` silently discards facts that
match no rule's alpha node.

The `rule-meta-store` field on Session is already a workaround for this same problem in
the meta-rules domain: it stores rule metadata outside the RETE network so meta-rules can
see prior rules regardless of insertion order.

The insight: **the workaround IS the solution, generalized.**

---

## Key Design Decisions

### 1. Always store, break compat

Every `insert` unconditionally writes to the fact store. The "discard unmatched" behavior
is gone entirely. This is a breaking change, acceptable because we're on a personal fork.

Alternative considered: opt-in retention (session flag or per-attribute config). Rejected
because the meta-rules system fundamentally needs all facts to survive — making it opt-in
just means everyone opts in.

### 2. Unified store replaces rule-meta-store

Rather than having two parallel storage mechanisms (rule-meta-store for meta-facts,
proposed retention store for domain facts), a single store handles both. Meta-facts use
`::o/` namespaced attributes and are partitioned by convention, not mechanism.

This simplifies the architecture: one code path for storage, one for replay, one for query.

### 3. History with tombstones

The store keeps all historical values for each `[id attr]` pair. Retraction soft-deletes
(sets `:alive? false`, moves current value to history). Only `purge-store` hard-deletes.

This was inspired by Datomic but intentionally simpler — no `as-of` temporal queries, no
transaction log, no ACID. Just a chronological value history per fact.

**Replay semantics:** When a new rule is added and matching facts are replayed from the
store, only the latest live value is replayed. History is for querying, not for RETE
activation.

**Retraction semantics:** Tombstone, not delete. The full history survives retraction.
This enables audit trails and temporal analysis without a separate event log.

### 4. Secondary indexes for replay performance

The store maintains `{attr -> #{[id val]}}` for live facts. When `add-rule` introduces
a new rule, it looks up the rule's watched attributes in this index and replays matching
facts. This is O(matching facts) not O(all facts).

This replaces the current `initialize-rule-against-session` logic which scans both
`id-attr-nodes` and `rule-meta-store` separately.

### 5. Janitor rules for memory management

No automatic GC, TTL, or eviction. Instead, the system manages its own memory through
rules. A "janitor rule" watches for staleness patterns and calls `purge-store!` to
hard-delete facts and reclaim memory.

This is conceptually elegant: the rules engine uses its own rule-evaluation capability
for housekeeping. But it requires a `query-store` function so janitor rules can see
facts that aren't in the RETE network.

### 6. purge-store takes attribute sets

For bulk purging, `purge-store` accepts a set of attributes:
`(o/purge-store session #{:temp/x :temp/y})`. This is simpler than predicate functions
or :what-style patterns and covers the janitor use case.

---

## API Surface

```clojure
;; Modified (always writes to store now)
(o/insert session id attr val)
(o/retract session id attr)     ;; tombstones, doesn't delete

;; New
(o/query-store session)                                    ;; live domain facts
(o/query-store session {:include-meta? true})               ;; + meta-facts
(o/query-store session {:include-history? true})             ;; + tombstones/history
(o/purge-store session id attr)                             ;; hard-delete specific
(o/purge-store session #{:attr1 :attr2})                    ;; hard-delete by attribute set
(o/purge-store! id attr)                                    ;; deferred (in rule bodies)
(o/purge-store! #{:attr1 :attr2})                           ;; deferred bulk

;; Removed
;; rule-meta-store field on Session
;; query-all-meta (deprecated, thin wrapper over query-store)
```

---

## Store Data Model

```clojure
;; Primary store: {id {attr {:current val, :alive? bool, :history [v1 v2 ...]}}}
;; Attribute index: {attr #{[id val] ...}} (live facts only)
```

The attribute index is maintained incrementally on every insert/retract/purge. It
enables O(1) lookup by attribute, which is the critical operation for retroactive
replay on `add-rule`.

---

## Implementation Phases

1. **Phase A** — Fact store data structure and helper functions
2. **Phase B** — Wire store into insert/retract, remove rule-meta-store population
3. **Phase C** — Generalize retroactive replay to use store, remove rule-meta-store field
4. **Phase D** — Public API (query-store, purge-store, purge-store!)
5. **Phase E** — Rewrite examples without keepers, integration tests, performance

---

## Connections to Other Issues

- **src-hhd (provenance graph):** Provenance data lives in the unified store. `query-store`
  with `:include-meta?` is the foundation for walking `::o/derived-from` chains.
- **src-09r (spec conflict):** Must resolve before/during Phase B since meta-facts move
  into the unified store.
- **src-jv1 (lazy metadata):** Partially obviated — store always holds meta-facts. "Zero
  cost" goal shifts to "zero RETE cost when no meta-rule exists" (already true).
- **src-357 (self-modifying rules):** Fact history enables rules to observe their own past
  state. Direct enabler.
- **src-kwv (distributed sessions):** Store serialization is prerequisite for inter-session
  fact exchange.

---

## Open Questions

1. **History entry format:** Plain values `[v1 v2]` or annotated
   `[{:value v1 :op :insert} ...]`? Starting plain, can migrate later.
2. **reset! interaction:** Should tombstone all existing `[id *]` facts, then insert new ones.
3. **Bulk insert performance:** Every insert now does a map `assoc-in`. Profile to verify
   it's negligible.
4. **Store stats API:** `(o/store-stats session)` for monitoring janitor effectiveness?
   Deferred to post-implementation.

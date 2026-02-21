# Elemental combo system example

**Date:** 2026-02-21
**Branch:** `meta-rules`

## Summary

Built a second non-trivial example program — an elemental combo system
(Genshin Impact / Divinity: Original Sin style) — to showcase meta-rules
capabilities that the access control example doesn't reach.  The headline
feature is a **chain reaction detector**: a meta-meta-rule that discovers
A→B reaction chains by joining meta-facts with domain facts across
generated combo rules.

File: `test/odoyle/examples/elemental_combos_test.clj`

---

## Architecture

```
Element data                   Meta-rules layer
  [::fire-elem :element/name   ┌──────────────────────┐
                :fire]    ───> │  element-materializer  │ ──> :element/fire rule
  [::fire-elem :element/       │  (watches element/*)   │     (via add-rule!)
       tick-damage 5]          └──────────────────────┘

Combo recipe data              ┌──────────────────────┐
  [::vaporize :combo/          │  combo-materializer    │ ──> :combo/vaporize rule
       element-a :fire]  ───> │  (watches combo/*)     │     (via add-rule!)
  [::vaporize :combo/          │  + inserts domain fact │
       element-b :water]       │  :combo-rule/produces  │
  [::vaporize :combo/          └──────────────────────┘
       result :steam]                                         │
                                                              v  (::o/conditions +
                               ┌──────────────────────┐         :combo-rule/produces)
                               │  chain-reaction-      │
                               │  detector             │ ──> :chain/vaporize+flash-freeze
                               │  (joins ::o/conditions│     (via add-rule! {:root? true})
                               │   with domain facts   │
                               │   to find A→B chains) │
                               └──────────────────────┘
```

---

## What's novel vs. the access control example

| Aspect | Access Control | Elemental Combos |
|--------|---------------|------------------|
| Generated rules do | Join request+user → insert decision | Match dual-element status → apply result status |
| Meta-meta-rule purpose | Audit trail (passive observation) | **Chain reaction fusion** (active rule synthesis) |
| Condition introspection | Detect policy conflicts (overlap) | **Discover dependency chains** (A's output = B's input) |
| Cross-fact joins | Meta-facts only | **Meta-facts + domain facts** on same entity |
| Truth maintenance | Audit survives enforcement removal | Chain rules survive individual combo removal |

The chain reaction detector is the key novelty.  It joins `::o/conditions`
(meta-fact) with `:combo-rule/produces` (domain fact inserted by the combo
materializer) on the same entity id to discover that combo A's result
status appears in combo B's trigger conditions.  This is a
**cross-domain/meta-fact join** — the first example to do this.

---

## Cross-domain/meta-fact join pattern

The combo materializer inserts both a deferred rule (`add-rule!`) and an
immediate domain fact (`insert!`) on the same entity id:

```clojure
;; Inside combo-materializer's :then block:
(o/insert! rule-name :combo-rule/produces result)   ;; immediate → RETE
(o/add-rule! (o/->rule rule-name {...}))             ;; deferred → drain
```

The domain fact reaches the alpha node immediately (via `*mutable-session*`).
The meta-facts (`::o/rule`, `::o/conditions`) arrive later when the deferred
queue is drained and `add-rule` inserts them.  The chain detector's join
nodes accumulate partial matches from the domain fact, then complete the
join when the meta-facts arrive.  This works because O'Doyle's RETE join
handles facts arriving at different times — partial matches in memory nodes
wait for their counterparts in alpha nodes.

---

## Chain trigger extraction via condition introspection

The chain detector extracts trigger elements from structured conditions:

```clojure
(defn- extract-status-attrs [conditions]
  (into #{}
        (comp
          (map #(get-in % [:attr :value]))
          (filter #(and (keyword? %)
                        (= "status" (namespace %)))))
        conditions))
```

For vaporize's conditions `[entity :status/fire true] [entity :status/water true]`,
this returns `#{:status/fire :status/water}`.  The chain rule's `:what` block
is built by taking the union of A's triggers and B's triggers minus the
intermediate (the link status that A produces and B consumes):

```
A triggers:    #{:status/fire :status/water}
B triggers:    #{:status/steam :status/ice}
Link:          :status/steam
Chain triggers: #{:status/fire :status/water :status/ice}
```

---

## Non-determinism note

When all three base elements (fire+water+ice) are applied simultaneously,
multiple rules fire in the same `fire-rules` cycle: individual element
rules, the vaporize combo, and the chain rule.  If these rules all modified
the same fact (e.g., `:entity/hp`), the result would depend on execution
order (last writer wins).

The example avoids this by having rules write to separate channels: element
rules log to `*element-log`, combo rules log to `*combo-log` and insert
result statuses, chain rules log to `*chain-log` and insert result statuses.
Status insertions are idempotent (same entity+attr+value), so concurrent
writes are safe.

---

## Test coverage (10 tests, 42 assertions)

| Test | Feature exercised |
|------|-------------------|
| `basic-element-materialization` | Data → generated rules, logging |
| `combo-materialization` | Dual-element trigger, result status |
| `combo-does-not-fire-with-single-element` | Join correctness |
| `chain-reaction-auto-detection` | Structural introspection, no spurious chains |
| `chain-reaction-execution` | Triple-element → chain fires + result status |
| `truth-maintenance-cascading-removal` | Materializer removal cascades; chain survives |
| `truth-maintenance-single-combo-removal` | Siblings unaffected |
| `retroactive-initialization` | Pre-existing statuses matched after combo added |
| `dynamic-combo-triggers-chain-detection` | Runtime combo addition triggers chain detection |
| `condition-introspection-of-generated-rules` | Structured conditions accurate |
| `multiple-entities-independent-combos` | Per-entity combo independence |

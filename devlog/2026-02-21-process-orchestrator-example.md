# Process orchestrator example: self-healing workflows

**Date:** 2026-02-21
**Branch:** `meta-rules`

## Summary

Built a third example program — a self-healing process orchestrator — to
showcase `remove-rule!` as a core mechanic and self-correcting/self-healing
behavior.  A workflow engine where transitions between order states are
declared as data, and the system monitors its own rule graph for structural
violations and repairs itself.

File: `test/odoyle/examples/process_orchestrator_test.clj`

---

## Architecture

```
Transition data                Meta-rules layer
  [::t1 :transition/from       ┌──────────────────────────┐
        :pending]         ───> │  transition-materializer   │ ──> :transition/pending->validated
  [::t1 :transition/to         │  (watches transition/*)    │     (via add-rule!)
        :validated]             │  + inserts domain metadata │
                                │  :transition-rule/from/to  │
                                └──────────────────────────┘
                                          │
              ┌───────────────────────────┼───────────────────────────┐
              v                           v                           v
┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐
│  state-validator      │  │  topology-monitor     │  │  rejection-rule-     │
│  (introspects         │  │  (joins domain meta-  │  │  generator           │
│   ::o/conditions →    │  │   data to build       │  │  (introspects        │
│   valid-state set →   │  │   adjacency graph →   │  │   conditions →       │
│   correction rule)    │  │   egress rules)       │  │   valid-pair set →   │
│                       │  │                       │  │   rejection rule)    │
│  remove-rule! + add-  │  │  add-rule! for dead   │  │                      │
│  rule! on change      │  │  ends; remove-rule!   │  │  remove-rule! + add- │
│                       │  │  when resolved        │  │  rule! on change     │
└──────────────────────┘  └──────────────────────┘  └──────────────────────┘
```

---

## What's novel vs. previous examples

| Aspect | Access Control | Elemental Combos | Process Orchestrator |
|--------|---------------|------------------|---------------------|
| `remove-rule!` usage | Not used | Not used | **Core mechanic** — regeneration and cleanup |
| Self-healing | No | No | **Yes** — dead-end detection + emergency egress |
| Self-correcting | No | No | **Yes** — invalid state → `:error` |
| Rule regeneration | One-shot generation | One-shot generation | **remove-rule! + add-rule! cycle** |
| Change detection | N/A | N/A | **Atom-based** loop prevention |
| Domain metadata | Policy provenance | `:combo-rule/produces` | `:transition-rule/from` / `:transition-rule/to` |

The orchestrator is the first example to use `remove-rule!` as a routine
operation (not just truth maintenance cascade), and the first to demonstrate
self-correcting and self-healing behavior.

---

## The regeneration loop problem and atom-based change detection

The state-validator and rejection-rule-generator both use `:then-finally` and
need to regenerate their output rules when the set of transitions changes.
The natural pattern is:

```clojure
:then-finally
(do
  (when (o/contains-rule? session :validator/correction)
    (o/remove-rule! :validator/correction))
  (o/add-rule! (o/->rule :validator/correction {...})))
```

This causes an **infinite loop**:

1. Transition rule added → meta-rule `:then-finally` fires
2. `remove-rule!` + `add-rule!` queued in deferred queue
3. Deferred queue drained → new rule added → `fire-rules` called
4. The new rule's metadata triggers the meta-rule's `:then-finally` again
5. Goto 2 → recursion limit hit

The fix: use external atoms to track the previously computed state and only
regenerate when it actually changes:

```clojure
(let [*prev-valid-states (atom nil)]
  ;; ... in :then-finally:
  (let [valid-states (compute-from-rule-meta-store ...)]
    (when (not= valid-states @*prev-valid-states)
      (reset! *prev-valid-states valid-states)
      (o/remove-rule! ...)
      (o/add-rule! ...))))
```

This is a general pattern for any `:then-finally` block that does
`remove-rule!` + `add-rule!`.  The atom lives outside the session
(in the `make-session` closure) because sessions are immutable values —
there's no place inside the session to stash mutable "last seen" state.

---

## Concurrent rule firing and retract safety

When order facts are inserted in the same batch as transition declarations,
both the rejection rule and the transition rule can match the same request in
the same `fire-rules` cycle.  The transition rule retracts `:order/request`
via `retract!`, then the rejection rule also tries to retract it → exception.

The fix is a defensive try-catch in the rejection rule's `:then` block:

```clojure
(try
  (o/retract! order :order/request)
  (catch Exception _))
```

This is a pragmatic solution.  The underlying issue is that O'Doyle's
`:then` blocks execute against a snapshot of the session, but `retract!`
mutates the volatile.  Two rules in the same cycle can both "see" the fact
in the snapshot but the second retract fails because the first already
applied.  A more robust approach would be to check the mutable session
before retracting, but `*mutable-session*` is private.

---

## Topology monitor: add + remove in one `:then-finally`

The topology monitor demonstrates using both `add-rule!` and `remove-rule!`
in a single `:then-finally` block:

```clojure
;; Add egress rules for new dead ends
(doseq [dead-state dead-ends]
  (when-not (o/contains-rule? session egress-name)
    (o/add-rule! ...)))

;; Remove egress rules for resolved dead ends
(doseq [[rn meta] (:rule-meta-store session)
        :when (= "emergency-egress" (namespace rn))]
  (when (not (contains? dead-ends egress-state))
    (o/remove-rule! rn)))
```

The `contains-rule?` / `contains?` guards prevent duplicate additions or
redundant removals.  The deferred queue processes these in FIFO order:
all adds from the first `doseq`, then all removes from the second.

The topology monitor avoids the regeneration loop problem because it only
adds rules for states that DON'T already have egress rules, and only
removes rules for states that ARE no longer dead ends — a natural idempotency
that doesn't need atom-based tracking.

---

## Test coverage (8 tests, 28 assertions)

| Test | Feature exercised |
|------|-------------------|
| `basic-transitions` | Full lifecycle: pending→validated→paid→shipped→delivered |
| `invalid-request-rejected` | Rejection rule catches pending→paid skip |
| `invalid-state-corrected` | State validator corrects `:bogus` → `:error` |
| `dead-end-healing` | Remove transition → dead end → emergency egress fires |
| `healing-resolved` | Restore transition → egress rule removed |
| `truth-maintenance-cascade` | Removing materializer cascades all derived rules |
| `retroactive-initialization` | Pre-existing orders matched when transitions declared |
| `dynamic-workflow-modification` | Runtime transition addition immediately usable |

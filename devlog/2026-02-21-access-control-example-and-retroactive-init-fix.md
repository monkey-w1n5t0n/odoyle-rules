# Cross-entity retroactive init bug & access control example

**Date:** 2026-02-21
**Branch:** `meta-rules`
**Commit:** `cc45d55`

## Summary

Built a non-trivial example program (self-configuring access control) to
showcase meta-rules.  In the process, discovered and fixed a real bug in
`initialize-rule-against-session` that broke retroactive initialization for
any dynamically created rule with cross-entity joins and literal value
conditions.

---

## The bug: alpha node depth mismatch

### Setup

O'Doyle's alpha node trie grows deeper as conditions become more specific.
A keeper rule like `[uid :user/role role]` creates an alpha node at depth 1
matching `:attr = :user/role`.  A dynamically generated rule like
`[user-id :user/role :editor]` needs a depth-2 node matching
`:attr = :user/role` AND `:value = :editor`.

When the generated rule is added via `add-rule!`, `add-condition` correctly
reuses the existing depth-1 node and creates a new depth-2 child beneath it.
But the existing fact `[::alice :user/role :editor]` was inserted when only
the depth-1 node existed — it lives in the depth-1 node's `:facts` map and
was never copied down to depth-2.

### Consequence

`initialize-rule-against-session` looked at the join node's alpha node
(depth-2), found zero facts, and silently produced zero matches.  The
generated rule appeared to exist but never fired.  This affected **any**
`add-rule!`-generated rule whose `:what` block contained literal values
when a keeper rule for the same attribute used only bindings — exactly the
common pattern for meta-rule-generated rules.

Single-entity rules (like the schema-driven getter in the Phase 5 tests)
were unaffected because their conditions used only binding symbols, so
they shared the same depth-1 alpha nodes as their keepers.

### The fix (two parts)

1. **Root-to-leaf replay order.**  The original code processed join nodes in
   leaf-to-root order (an artifact of walking the beta tree upward and
   appending to a vector).  This meant child join nodes ran before their
   parent memory nodes had matches.  Fixed with `(rseq join-node-ids)`.

2. **Ancestor alpha node scanning.**  When a join node's alpha node has no
   facts (the depth mismatch case), the code now walks up the alpha node
   path to collect all test criteria (field/value pairs), then scans
   `id-attr-nodes` for facts matching those criteria.  This is the slow
   path — the fast path (facts already present) is unchanged.

```clojure
;; fast path: facts at this alpha node
(if (seq (:facts alpha-node))
  (mapcat vals (vals (:facts alpha-node)))
  ;; slow path: scan session facts, filter by alpha node ancestry tests
  (let [tests (loop [path alpha-node-path, ts []]
                (let [node (get-in session path)]
                  (if (:test-field node)
                    (recur (subvec path 0 (- (count path) 2))
                           (conj ts [(:test-field node) (:test-value node)]))
                    ts)))]
    (for [[[id attr] node-paths] (:id-attr-nodes session)
          :let [fact (get-in (get-in session (first node-paths))
                             [:facts id attr])]
          :when fact
          :when (every? (fn [[field test-val]]
                          (case field
                            :id    (= (:id fact) test-val)
                            :attr  (= (:attr fact) test-val)
                            :value (= (:value fact) test-val)))
                        tests)]
      fact)))
```

### Design alternative considered and rejected

We could have made `add-rule` / `add-condition` copy facts from ancestor
alpha nodes into newly created deeper children at wiring time.  This would
keep `initialize-rule-against-session` simple but complicates `add-rule`
(which is already complex) and raises questions about whether those facts
should be tracked in `id-attr-nodes` at both depths.  The scanning approach
is isolated to retroactive init and doesn't touch the normal insertion path.

---

## The example: self-configuring access control

### Architecture

```
Policy facts                    Meta-rules layer
  [::pol-1 :policy/resource     ┌──────────────────────┐
           :documents]    ───>  │  policy-materializer  │ ──> enforcement rules
  [::pol-1 :policy/action       │  (watches policy/*)   │     (via add-rule!)
           :write]              └──────────────────────┘
  [::pol-1 :policy/role                                        │
           :editor]                                            v  (::o/conditions)
                                ┌──────────────────────┐
                                │  audit-generator      │ ──> audit rules
                                │  (watches ::o/rule +  │     (via add-rule! {:root? true})
                                │   ::o/conditions of   │
                                │   enforcement rules)  │
                                └──────────────────────┘

                                ┌──────────────────────┐
                                │  conflict-detector    │ ──> reports overlapping
                                │  (joins ::o/conditions│     policies to atom
                                │   of rule pairs)      │
                                └──────────────────────┘
```

### Features exercised

| Meta-rules feature            | Where it appears                              |
|-------------------------------|-----------------------------------------------|
| `add-rule!`                   | policy-materializer, audit-generator           |
| `::o/conditions` introspection| audit-generator (extracts resource+action),    |
|                               | conflict-detector (compares condition pairs)   |
| Truth maintenance cascading   | removing materializer cascades to enforcement  |
| `{:root? true}`               | audit rules survive enforcement rule removal   |
| Retroactive initialization    | facts inserted before policies still matched   |
| Multi-level meta              | audit-generator watches rules created by rules |
| Cross-entity joins            | enforcement rules join req↔user-id             |

### Gotcha: audit rule duplication

First attempt: every audit rule watched `[req :access/decision :granted]`
with no further scoping.  With N enforcement rules generating N audit rules,
each grant triggered all N audit rules (N^2 total audit entries).

Fix: the audit-generator extracts literal `:access/resource` AND
`:access/action` values from the enforcement rule's `::o/conditions` and
bakes them into the audit rule's `:what` block.  Each audit rule fires only
for decisions matching its specific (resource, action) pair.

This is actually a nice showcase of condition introspection — the
audit-generator doesn't just detect enforcement rules, it reads their
internal structure to parameterize its own output.

### Keeper rules are necessary

O'Doyle silently discards facts matching no rule.  The access control
example needs keeper rules for every fact type (`::keep-user-role`,
`::keep-access-resource`, etc.) so that domain facts survive until
enforcement rules are generated.  This is the same pattern as the
schema-driven getter test and is documented in the SPEC's "Prototype
Results" section.  A future "fact retention store" would eliminate this
requirement but adds memory cost.

---

## Namespace gotcha in test files

`::user/name` in a Clojure file resolves relative to the current namespace's
aliases.  Without `(:require [user ...])` in the ns form, `::user/name` is
an invalid token.  Use bare qualified keywords (`:user/name`) for domain
attributes in test files that don't own those namespaces.

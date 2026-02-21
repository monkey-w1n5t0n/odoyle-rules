# Meta-Rules: Rules About Rules

**Branch:** `meta-rules`
**Status:** Research / Prototype
**Scope:** Exploratory — not committed to merge; design may change freely

---

## Summary

Make the O'Doyle rule network itself queryable as first-class facts within the session.
When a rule is added via `add-rule`, facts describing that rule are automatically inserted
into the session. This allows writing rules whose `:what` blocks match on other rules —
"meta-rules" — which can in turn call `add-rule!` to programmatically introduce new rules.

---

## Goals

1. **Rules as data** — Every rule in the session is observable as a set of EAV tuples.
2. **Meta-rule authoring** — Users can write rules that match on rule-metadata and react.
3. **Dynamic rule generation** — Meta-rules can call `add-rule!` from inside `:then` blocks.
4. **Composable with existing API** — Transparent to the macro path; same behaviour for
   `ruleset`-defined rules and `->rule`-defined rules.
5. **Safe serialization** — Rule-metadata facts are excluded from `query-all` by default.
6. **Truth maintenance** — Derived rules are automatically removed when their source rule
   is removed, unless explicitly declared as root rules.

---

## Non-Goals (for this prototype)

- Cross-session joins (meta-facts always live in the same session as domain facts)
- Automatic cycle detection for meta-rule chains (the existing recursion limit covers this)
- Full ClojureScript parity before the research phase concludes

---

## API Changes

### 1. `add-rule` (existing, modified)

```clojure
(o/add-rule session rule)
;; => session with rule added AND rule-metadata facts inserted
```

After wiring the RETE network (as today), `add-rule`:

1. Unconditionally stores rule metadata in a new `rule-meta-store` field on Session (a plain
   map, bypassing the RETE network). This ensures metadata survives even if no meta-rule
   has been added yet.
2. If the newly added rule watches `::o/rule`, `::o/conditions`, or other `::o/` attributes
   in its `:what` block, it is retroactively initialized against all entries in
   `rule-meta-store` (i.e., all previously added rules' metadata is replayed through it).
3. Inserts EAV facts into the RETE network for any already-present meta-rules to react to.

**Why `rule-meta-store` is necessary**: O'Doyle discards facts that match no rule at
insertion time (documented behaviour). If a user calls `(reduce o/add-rule session [domain-rule meta-rule])`,
the metadata facts for `domain-rule` would be inserted before `meta-rule` exists, and
silently dropped. `rule-meta-store` is the source of truth; the RETE network is a secondary
index populated on-demand.

**No behaviour change for callers** — the return type is still a `Session`.

---

### 2. `add-rule!` (new)

```clojure
;; Inside a :then or :then-finally block:
(o/add-rule! (o/->rule ::my-generated-rule {...}))

;; With root? option (persists even if the source rule is removed):
(o/add-rule! (o/->rule ::my-generated-rule {...}) {:root? true})
```

- Queues the rule addition to be applied **after** the current `fire-rules` cycle completes.
- After deferred rules are applied, `fire-rules` is re-run automatically so the new rules
  have a chance to fire against existing facts without requiring the user to call it again.
- New rules are **retroactively initialized** against all existing facts in the session.
- Throws if called outside a `:then`/`:then-finally` block (like `insert!` today).

**Truth maintenance:** By default, a rule added via `add-rule!` is considered *derived*
from the firing rule. If the source rule is later removed via `remove-rule`, derived rules
are automatically removed too. Passing `{:root? true}` breaks this link — the generated
rule persists independently.

---

### 3. `remove-rule` (existing, modified)

```clojure
(o/remove-rule session rule-name)
```

In addition to existing behaviour:
- Retracts the rule-metadata facts for `rule-name`.
- Recursively removes any derived rules (rules whose provenance chain traces back to
  `rule-name`) unless they were added with `{:root? true}`.

---

### 4. `remove-rule!` (new, symmetric with add-rule!)

```clojure
;; Inside a :then or :then-finally block:
(o/remove-rule! ::some-rule)
```

Deferred version of `remove-rule` for use inside rule bodies.

---

### 5. `query-all` (existing, unchanged) and `query-all-meta` (new)

```clojure
;; Unchanged — returns domain facts only (safe for serialization):
(o/query-all session)
(o/query-all session ::my-rule)

;; New — returns all rule-metadata facts from rule-meta-store:
(o/query-all-meta session)
(o/query-all-meta session ::my-rule)  ;; metadata facts for a specific rule
```

`query-all` is **not modified** — adding an opts map would create an ambiguous 2-arg arity
collision with `(query-all session rule-name)` (both take session + one argument; the only
difference would be type). Instead, `query-all-meta` is a new function for explicit
metadata access.

---

## Rule-Metadata Facts Schema

When rule `::my-ns/my-rule` is added, the following facts are inserted:

```
id                   attr                              value
──────────────────────────────────────────────────────────────────────
::my-ns/my-rule      ::o/rule                          true
::my-ns/my-rule      ::o/conditions-raw                [[id ::x x] [id ::y y]]
::my-ns/my-rule      ::o/conditions                    [{:id   {:kind :binding :sym id}
                                                          :attr  {:kind :value   :value ::x}
                                                          :value {:kind :binding :sym x}
                                                          :opts  {}}
                                                         ...]
::my-ns/my-rule      ::o/has-when?                     true/false
::my-ns/my-rule      ::o/has-then?                     true/false
::my-ns/my-rule      ::o/has-then-finally?             true/false
```

**Notes:**
- `::o/rule-name` was considered but dropped — it's redundant. Any meta-rule matching
  `[rule-name ::o/rule true]` already has the rule name in the `rule-name` binding.
- `::o/conditions-raw` stores the raw what-tuples with binding symbols. Not EDN-safe
  (contains symbols), but useful for source-level introspection.
- `::o/conditions` stores the structured, serialization-friendly form: each element is a
  map with `:id`, `:attr`, `:value` keys, each being `{:kind :binding/:value, :sym/:value ...}`.
- The id column uses the rule keyword itself — meta-rules can join across attributes.
- All `::o/` attributes are spec-registered by the library at load time. If spec is
  instrumented, `insert` will not throw on metadata attribute insertion.

---

## Structured Condition Map

Each condition in `::o/conditions` is a map:

```clojure
{:id    {:kind :binding, :sym 'id}          ; or {:kind :value, :value ::specific-id}
 :attr  {:kind :value,   :value ::player/x}  ; or {:kind :binding, :sym 'attr}
 :value {:kind :binding, :sym 'x}            ; or {:kind :value, :value 42}
 :opts  {:then false}}                       ; the opts map as-is
```

This lets meta-rules use `:when` blocks to filter on specific attribute values a rule
watches:

```clojure
::rules-watching-player-x
[:what
 [rule-name ::o/rule           true]
 [rule-name ::o/conditions     conditions]
 :when
 (some #(= (get-in % [:attr :value]) ::player/x) conditions)
 :then
 (println "Rule" rule-name "watches ::player/x")]
```

---

## Deferred Execution Model

During `fire-rules`, rule bodies may call `add-rule!` and `remove-rule!`.
These are queued in a *deferred-rules-queue* on the mutable session volatile.

After the current `fire-rules` cycle completes (all `:then` and `:then-finally` blocks
executed), the engine drains the deferred queue in **FIFO interleaved order** (the order
calls were made, not grouped by type):

1. For each queued operation in order:
   - `add-rule`: wire RETE nodes, then retroactively initialize against existing facts.
   - `remove-rule`: remove RETE nodes, cascade to derived rules (skip if already removed).
2. Call `fire-rules` again on the resulting session.
3. Repeat until the deferred queue is empty.

**Ordering note**: FIFO interleaved means if a `:then` block calls `add-rule! ::foo` then
`remove-rule! ::foo`, `::foo` is added and then immediately removed (net result: not added).
Grouping all adds before removes could produce different results and is harder to reason
about. FIFO is more predictable.

**`remove-rule` and missing rules**: Cascading removal may attempt to remove a rule that
was already manually removed. `remove-rule` must handle this gracefully (log and skip
rather than throw) when called from within a cascade or drain context.

The existing recursion limit applies across the entire drain process to prevent infinite
rule-generation loops.

---

## Truth Maintenance & Provenance

The "source rule" for provenance purposes is **the rule whose `:then` or `:then-finally`
block called `add-rule!`**, period. It is always the directly enclosing rule, regardless
of join complexity or how many rules contributed to triggering the match.

Each deferred `add-rule!` call implicitly records provenance:

```
id                       attr                   value
────────────────────────────────────────────────────────────────
::generated-rule-name    ::o/derived-from       ::source-rule-name
```

When `remove-rule` is called for `::source-rule-name`:
1. `::source-rule-name`'s metadata facts are retracted.
2. All rules with `::o/derived-from ::source-rule-name` are found.
3. Each is removed (recursively, depth-first) unless it has `::o/root? true`.

`{:root? true}` passed to `add-rule!` inserts `[rule-name ::o/root? true]` as a
metadata fact, which `remove-rule` checks before cascading.

---

## Serialization

`query-all` is unchanged — it returns only domain facts (as today). Rule-metadata facts
live in `rule-meta-store` and are not part of `query-all`'s output.

To inspect or dump rule metadata:
```clojure
(o/query-all-meta session)           ;; all rule metadata facts
(o/query-all-meta session ::my-rule) ;; metadata for one rule
```

When loading serialized domain facts back into a fresh session, the standard workflow
(re-add rules, then re-insert domain facts) remains unchanged. Rule-metadata is
re-populated automatically by `add-rule`.

---

## Implementation Plan

### Phase 1: `rule-meta-store` + metadata insertion in `add-rule`

- Add `rule-meta-store` field to the `Session` record: `{rule-name -> {attr -> value}}`.
- Implement the structured condition map builder (`condition->structured-map`).
- After RETE wiring in `add-rule`, populate `rule-meta-store` with all metadata for the rule:
  `::o/rule`, `::o/conditions-raw`, `::o/conditions` (structured), `::o/has-when?`,
  `::o/has-then?`, `::o/has-then-finally?`.
- Register clojure.spec specs for all `::o/` attributes at library load time (required
  so spec instrumentation doesn't throw when metadata is inserted into the RETE).
- If the newly added rule has `::o/rule` etc. in its `:what` block (it is a meta-rule),
  replay all `rule-meta-store` entries through it (retroactive initialization for `add-rule`).
- Also call `insert` for metadata facts into the RETE for any already-present meta-rules.
- Add `query-all-meta` function.
- Write tests:
  - Adding rules in any order: meta-rule added last still sees all prior rules' metadata.
  - Adding meta-rule first also works.
  - `query-all` is unchanged; `query-all-meta` returns metadata.
  - Spec instrumentation does not throw on metadata insertion.

### Phase 2: `add-rule!` and deferred queue

- Add `deferred-rules-queue` field to `Session` (vector of `[:add rule opts]` / `[:remove rule-name]`).
- Implement `add-rule!` using `*mutable-session*` volatile (mirrors `insert!`).
- Implement `remove-rule!` (deferred version of `remove-rule`).
- After `fire-rules` main loop, drain the deferred queue in FIFO order.
- `remove-rule` in cascade context must skip-not-throw if a rule no longer exists.
- Write tests:
  - `add-rule!` in `:then` block produces a working rule after firing.
  - `remove-rule!` in `:then` block removes rule after firing.
  - Interleaved add/remove of same rule in one cycle: net result is removed.
  - `remove-rule` cascade doesn't throw on already-removed derived rules.

### Phase 3: Retroactive initialization

- Implement `initialize-rule-against-session` that replays all existing session facts
  (from `id-attr-nodes`) through a freshly added rule's alpha/beta nodes.
- Integrate into the deferred queue drain step (for `add-rule!` path) AND into regular
  `add-rule` (for meta-rules catching up on prior rule metadata).
- Write tests:
  - Meta-rule creates a getter rule; getter rule immediately has matches for pre-existing facts.
  - No double-firing when a fact is both pre-existing and matches the new rule.

### Phase 4: `remove-rule!` and truth maintenance

- Extend `remove-rule` to remove the rule's entry from `rule-meta-store`.
- Implement derived-rule cascading: on remove, find all rules with `::o/derived-from =
  rule-name` in `rule-meta-store`; remove each unless `::o/root? true`; recurse.
- Write tests:
  - Removing a rule cascades to derived rules.
  - `{:root? true}` rules survive source rule removal.
  - Deep cascade chains work.

### Phase 5: Integration tests and prototype evaluation

- Schema-driven rule generation example (from spec below) as a runnable test.
- Plugin/extension system: insert rule-spec facts → meta-rule materializes them as rules.
- Rule analysis: meta-rule detects two rules watching the same attribute.
- Performance: add 100+ rules at startup, measure `rule-meta-store` + RETE overhead.
- Document findings in SPEC.md under a new 'Prototype Results' section.

---

## Open Questions (to resolve during prototype)

1. **ClojureScript compatibility**: `add-rule!` deferred semantics rely on the same
   `*mutable-session*` volatile used by `insert!`. ClojureScript should work the same,
   but needs explicit testing.

2. **Recursion limit interaction**: The deferred-drain loop runs `fire-rules` again.
   Does each drain iteration count against the recursion limit, or does it reset?
   Current thinking: it resets (it's a new top-level fire-rules call, not a recursive
   trigger from within `fire-rules`).

3. **Performance**: Every `add-rule` call now populates `rule-meta-store` and inserts
   ~6 facts into the RETE (if meta-rules exist). For sessions with many rules added at
   startup, profile the overhead. Consider lazy metadata insertion (only insert into RETE
   when a meta-rule is actually present).

4. **`wrap-rule` interaction**: `wrap-rule` modifies a rule's fns before `add-rule` is
   called. Should `::o/has-then?` reflect the original rule structure or the wrapped one?
   Decision needed: almost certainly the original (structural metadata, not runtime fns).

5. **`conditions-raw` reconstruction**: The parsed `Condition` records store bindings as
   `(list 'quote sym)` internally. Reconstructing the original `[id attr value]` tuples
   for `::o/conditions-raw` requires a `condition->raw-tuple` fn that reverses this.
   Verify this round-trips correctly for all binding/value combinations including opts.

---

## Example: Schema-Driven Rule Generation

```clojure
(def schema-rules
  (o/ruleset
    {;; meta-rule: when an entity schema is declared, generate a getter rule
     ::schema->getter-rule
     [:what
      [entity-kw ::schema/attributes attrs]
      :then
      (let [rule-name (keyword (namespace entity-kw) (str (name entity-kw) "-getter"))]
        (o/add-rule!
          (o/->rule rule-name
            {:what (mapv (fn [attr] [entity-kw attr (symbol (name attr))]) attrs)})
          {:root? false}))]}))

;; Inserting a schema fact automatically generates a getter rule:
(-> (reduce o/add-rule (o/->session) schema-rules)
    (o/insert ::player ::schema/attributes [::x ::y ::health])
    o/fire-rules)
;; => ::player-getter rule now exists and matches all ::player entities
```

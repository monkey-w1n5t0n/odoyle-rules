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
- Rule ordering guarantees for deferred rules (order is implementation-defined)
- Full ClojureScript parity before the research phase concludes

---

## API Changes

### 1. `add-rule` (existing, modified)

```clojure
(o/add-rule session rule)
;; => session with rule added AND rule-metadata facts inserted
```

After wiring the RETE network (as today), `add-rule` inserts EAV facts describing the rule
into the session. These facts are tagged as `:meta? true` internally.

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

### 5. `query-all` (existing, modified)

```clojure
;; Default — rule-metadata facts excluded:
(o/query-all session)

;; Opt-in to include rule-metadata facts:
(o/query-all session {:include-meta? true})
```

The no-arg arity now accepts an optional options map. By default, facts tagged as
`:meta? true` are excluded (safe for serialization). Passing `{:include-meta? true}`
includes them.

---

## Rule-Metadata Facts Schema

When rule `::my-ns/my-rule` is added, the following facts are inserted:

```
id                   attr                              value
──────────────────────────────────────────────────────────────────────
::my-ns/my-rule      ::o/rule                          true
::my-ns/my-rule      ::o/rule-name                     ::my-ns/my-rule
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
- `::o/conditions-raw` stores the raw quoted what-tuples as a vector. Not EDN-safe by
  default (contains symbols), but useful for source-level introspection.
- `::o/conditions` stores the structured, serialization-friendly form: each element is a
  map with `:id`, `:attr`, `:value` keys, each being `{:kind :binding/:value, :sym/:value ...}`.
- The id column uses the rule keyword itself — this means meta-rules can use joins on
  the rule name across multiple attributes.

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
executed), the engine:

1. Applies all deferred `add-rule` calls (in queue order), inserting RETE nodes.
2. For each newly added rule, replays all existing facts through its alpha/beta network
   (retroactive initialization), populating `matches` and queuing `:then` firings.
3. Applies all deferred `remove-rule` calls, including cascading derived-rule removal.
4. Calls `fire-rules` again on the resulting session.
5. Repeats until the deferred queue is empty.

The existing recursion limit applies across this entire process to prevent infinite
rule-generation loops.

---

## Truth Maintenance & Provenance

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

`query-all` without args excludes facts where the internal `:meta?` flag is true.

For users who want to inspect or store rule-metadata:

```clojure
;; include meta facts
(o/query-all session {:include-meta? true})
```

When loading serialized domain facts back into a fresh session, the standard workflow
(re-add rules, then re-insert domain facts) remains unchanged. Rule-metadata facts are
re-inserted automatically by `add-rule`.

---

## Implementation Plan

### Phase 1: Rule-metadata insertion in `add-rule`

- Modify `add-rule` to call `insert` for each metadata fact after building the RETE network.
- Tag inserted facts with `:meta? true` in a new `meta-fact-ids` set on `Session`.
- Modify `query-all` no-arg arity to accept opts and filter out meta facts by default.
- Write tests: adding a rule causes queryable rule-facts; query-all excludes them by default.

### Phase 2: `add-rule!` and deferred queue

- Add `deferred-rules-queue` field to `Session`.
- Implement `add-rule!` using `*mutable-session*` volatile (mirrors `insert!`).
- After `fire-rules` main loop, drain the deferred queue.
- Write tests: calling `add-rule!` in a `:then` block produces a working rule after firing.

### Phase 3: Retroactive initialization

- Implement `initialize-rule-against-session` that replays all existing facts through a
  freshly added rule's alpha/beta nodes.
- Integrate into the deferred queue drain step.
- Write tests: meta-rule creates a getter rule; the getter rule's matches are populated
  with pre-existing facts without re-inserting them.

### Phase 4: `remove-rule!` and truth maintenance

- Implement `remove-rule!` (deferred version).
- Extend `remove-rule` to retract rule-metadata facts.
- Implement derived-rule cascading on remove.
- Write tests: removing a rule cascades; root rules survive.

### Phase 5: Structured condition maps

- Implement the structured condition map builder.
- Insert `::o/conditions` (structured) and `::o/conditions-raw` (raw) on `add-rule`.
- Write tests: meta-rules can filter on specific watched attributes via `:when`.

---

## Open Questions (to resolve during prototype)

1. **ClojureScript compatibility**: `add-rule!` deferred semantics rely on the same
   `*mutable-session*` volatile used by `insert!`. ClojureScript should work the same,
   but needs explicit testing.

2. **Ordering of deferred rules**: If multiple `add-rule!` calls happen in the same
   fire-rules cycle, what order are they applied? FIFO queue seems natural. Does order
   matter for correctness?

3. **Recursion limit interaction**: The deferred-drain loop runs `fire-rules` again.
   Does each drain iteration count against the recursion limit, or does it reset?
   Current thinking: it resets (it's a new top-level fire-rules call).

4. **`ruleset` macro and metadata**: The `ruleset` macro calls `->rule` then the user
   calls `add-rule`. Since `add-rule` inserts metadata, the macro path is automatically
   covered. No macro changes needed.

5. **Spec integration for meta-attributes**: Should `::o/rule`, `::o/conditions`, etc.
   have registered specs? If spec is instrumented, inserting without specs would throw.
   Probably yes — register them in the library namespace.

6. **Performance**: Every `add-rule` call will now trigger `insert` for ~7 facts. For
   sessions with hundreds of rules added at startup, this adds overhead. Profile and
   consider batching inserts.

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

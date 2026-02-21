# O'Doyle Rules — Agent Instructions

## What this project is
A Clojure/ClojureScript rules engine (RETE-based) using EAV (entity-attribute-value) tuples.
Single source file: `src/odoyle/rules.cljc`. Tests: `test/odoyle/rules_test.cljc`.

## Active branch: `meta-rules`
This branch implements meta-rules: rules about rules. Read `SPEC.md` before touching anything.
It is the authoritative design document for all work on this branch.

## Commands

```bash
clj -M:test          # run tests (do this before and after every change)
clj -M:dev           # REPL with dev extras
```

Tests use `clojure.test` and live in `test/odoyle/rules_test.cljc`. Add new tests to that file.

## Issue tracker
```bash
bd ready             # what's unblocked and ready to work on
bd show <id>         # full issue description
bd update <id> --status in_progress   # claim an issue
bd close <id>        # mark done
```

All work is tracked in Beads. Phase 1 (`w1n5t0n-682`) is the only unblocked issue to start.
Phases unlock sequentially: 1 → 2 → 3 → 4 → 5.

## Architecture notes (read before editing rules.cljc)

- **Session** record holds: `alpha-node` (filter trie), `beta-nodes` (map of int→MemoryNode/JoinNode),
  `rule-name->node-id`, `id-attr-nodes`, `then-queue`, `then-finally-queue`.
- Facts are `[id attr value]` EAV tuples (`Fact` records internally).
- `insert` **silently discards facts that match no rule** — this is intentional and documented.
- `fire-rules` drains `then-queue` + `then-finally-queue`. Inside rule bodies, `*mutable-session*`
  is a volatile holding the current session; `insert!`, `retract!`, `reset!` all write to it.
- `add-rule` does NOT currently initialize new rules against existing facts — new rules only
  see future inserts. The meta-rules work changes this.

## Key invariant to preserve
All existing tests must pass after every phase. Do not break the public API.
`query-all`, `insert`, `retract`, `fire-rules`, `add-rule`, `remove-rule`, `ruleset` —
behaviour must be identical to pre-branch for any session that doesn't use `::o/` attributes.

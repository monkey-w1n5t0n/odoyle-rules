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

All work is tracked in Beads. Three epics organize the remaining work:
- **src-cxp** — Meta-rules v2: hardening and near-term capabilities (8 children)
- **src-8z0** — Fact retention store / eliminate keeper rules (6 children, under src-cxp)
- **src-hps** — Future research directions (5 children, P4 items)

Use `bd epic status` for completion tracking. Use `bd children <epic-id>` for sub-issues.

### Session-start orientation
```bash
bd ready                    # find available work
bd graph --all --compact    # visualize full dependency DAG
bd epic status              # epic completion percentages
```

### Session-close checklist
```bash
bd lint                     # verify all issues have required sections
bd orphans                  # catch issues referenced in commits but still open
bd sync                     # sync beads with git
```

### Labels
Issues are labeled for cross-cutting concerns:
- `design-decision` — needs design discussion before code
- `fact-store` — part of the unified fact store work
- `breaking-change` — changes public API behavior

Query by label: `bd query "label=design-decision"`

### Comments
Use `bd comments add <id> "..."` to record design decisions, session notes, or
"why we chose X over Y" on issues. Especially useful when closing with non-obvious resolutions.

## Architecture notes (read before editing rules.cljc)

- **Session** record holds: `alpha-node` (filter trie), `beta-nodes` (map of int→MemoryNode/JoinNode),
  `rule-name->node-id`, `id-attr-nodes`, `then-queue`, `then-finally-queue`.
- Facts are `[id attr value]` EAV tuples (`Fact` records internally).
- `insert` **silently discards facts that match no rule** — this is intentional and documented.
- `fire-rules` drains `then-queue` + `then-finally-queue`. Inside rule bodies, `*mutable-session*`
  is a volatile holding the current session; `insert!`, `retract!`, `reset!` all write to it.
- `add-rule` retroactively initializes new rules against existing facts via
  `initialize-rule-against-session`. This was added as part of the meta-rules work.

## Key invariant to preserve
All existing tests must pass after every phase. Do not break the public API.
`query-all`, `insert`, `retract`, `fire-rules`, `add-rule`, `remove-rule`, `ruleset` —
behaviour must be identical to pre-branch for any session that doesn't use `::o/` attributes.

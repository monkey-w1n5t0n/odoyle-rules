# Agent Instructions

O'Doyle Rules — a Clojure/ClojureScript RETE-based rules engine using EAV tuples.
Active branch: `meta-rules` (rules about rules). Read `SPEC.md` before touching anything.

## Project layout

- `src/odoyle/rules.cljc` — single source file (~1250 lines)
- `test/odoyle/rules_test.cljc` — tests (`clojure.test`)
- `test/odoyle/examples/` — non-trivial example programs
- `SPEC.md` — meta-rules specification (implemented)
- `SPEC-fact-store.md` — unified fact store design (pre-implementation)
- `devlog/` — session devlogs with architectural insights

## Commands

```bash
clj -M:test          # run tests (before and after every change)
clj -M:dev           # REPL with dev extras
```

## Issue tracker (beads)

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --status in_progress  # Claim work
bd close <id>         # Complete work
bd sync               # Sync with git
```

### Epics

Three epics organize the work:
- **src-cxp** — Meta-rules v2: hardening and near-term capabilities
- **src-8z0** — Fact retention store / eliminate keeper rules (child of src-cxp)
- **src-hps** — Future research directions (P4 items)

```bash
bd epic status              # completion percentages
bd children <epic-id>       # list sub-issues
bd epic close-eligible      # auto-detect closeable epics
```

### Labels

- `design-decision` — needs design discussion before code
- `fact-store` — part of the unified fact store work
- `breaking-change` — changes public API behavior

Query: `bd query "label=design-decision"`

### Comments

Use `bd comments add <id> "..."` to record design decisions, session notes, or
"why we chose X over Y" on issues.

## Session-start orientation

```bash
bd ready                    # find available work
bd graph --all --compact    # visualize full dependency DAG
bd epic status              # epic completion percentages
```

## Session-close checklist

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

1. **File issues** for remaining work
2. **Run quality gates** (if code changed): `clj -M:test`
3. **Check issue hygiene**:
   ```bash
   bd lint                 # verify required sections on all issues
   bd orphans              # catch issues referenced in commits but still open
   ```
4. **Update issue status** — close finished work, update in-progress items
5. **PUSH TO REMOTE** — this is MANDATORY:
   ```bash
   git pull --rebase
   bd sync
   git push
   git status  # MUST show "up to date with origin"
   ```
6. **Verify** — all changes committed AND pushed
7. **Hand off** — provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing — that leaves work stranded locally
- NEVER say "ready to push when you are" — YOU must push
- If push fails, resolve and retry until it succeeds

## Key invariant

All existing tests must pass after every change. Do not break the public API.
`query-all`, `insert`, `retract`, `fire-rules`, `add-rule`, `remove-rule`, `ruleset` —
behaviour must be identical to pre-branch for any session that doesn't use `::o/` attributes.


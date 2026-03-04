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


<!-- BEGIN BEADS INTEGRATION -->
## Issue Tracking with bd (beads)

**IMPORTANT**: This project uses **bd (beads)** for ALL issue tracking. Do NOT use markdown TODOs, task lists, or other tracking methods.

### Why bd?

- Dependency-aware: Track blockers and relationships between issues
- Git-friendly: Auto-syncs to JSONL for version control
- Agent-optimized: JSON output, ready work detection, discovered-from links
- Prevents duplicate tracking systems and confusion

### Quick Start

**Check for ready work:**

```bash
bd ready --json
```

**Create new issues:**

```bash
bd create "Issue title" --description="Detailed context" -t bug|feature|task -p 0-4 --json
bd create "Issue title" --description="What this issue is about" -p 1 --deps discovered-from:bd-123 --json
```

**Claim and update:**

```bash
bd update bd-42 --status in_progress --json
bd update bd-42 --priority 1 --json
```

**Complete work:**

```bash
bd close bd-42 --reason "Completed" --json
```

### Issue Types

- `bug` - Something broken
- `feature` - New functionality
- `task` - Work item (tests, docs, refactoring)
- `epic` - Large feature with subtasks
- `chore` - Maintenance (dependencies, tooling)

### Priorities

- `0` - Critical (security, data loss, broken builds)
- `1` - High (major features, important bugs)
- `2` - Medium (default, nice-to-have)
- `3` - Low (polish, optimization)
- `4` - Backlog (future ideas)

### Workflow for AI Agents

1. **Check ready work**: `bd ready` shows unblocked issues
2. **Claim your task**: `bd update <id> --status in_progress`
3. **Work on it**: Implement, test, document
4. **Discover new work?** Create linked issue:
   - `bd create "Found bug" --description="Details about what was found" -p 1 --deps discovered-from:<parent-id>`
5. **Complete**: `bd close <id> --reason "Done"`

### Auto-Sync

bd automatically syncs with git:

- Exports to `.beads/issues.jsonl` after changes (5s debounce)
- Imports from JSONL when newer (e.g., after `git pull`)
- No manual export/import needed!

### Important Rules

- ✅ Use bd for ALL task tracking
- ✅ Always use `--json` flag for programmatic use
- ✅ Link discovered work with `discovered-from` dependencies
- ✅ Check `bd ready` before asking "what should I work on?"
- ❌ Do NOT create markdown TODO lists
- ❌ Do NOT use external issue trackers
- ❌ Do NOT duplicate tracking systems

For more details, see README.md and docs/QUICKSTART.md.

<!-- END BEADS INTEGRATION -->

## Landing the Plane (Session Completion)

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd sync
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds

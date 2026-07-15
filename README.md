# cloud-itonami-isic-2396: Cutting, shaping and finishing of stone

Open Business Blueprint for **ISIC Rev.5 2396**: cutting, shaping and finishing of stone — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **dimension-stone plant operations**: production-batch data logging (product-type/weight/slab-thickness-tolerance/polish-gloss-level test results), sawing-line/polishing-line-equipment maintenance scheduling, safety-concern flagging, and outbound dimension-stone shipment coordination.

This repository designs a forkable OSS business for dimension-stone
plant operations: run by a qualified operator so a granite/marble/
limestone/sandstone fabrication plant keeps its own operating records
instead of renting a closed SaaS.

## Scope: dimension-stone slab/monument/countertop fabrication, downstream of quarrying itself

ISIC 2396 covers the **dimension-stone fabrication plant** that takes
in quarried blocks and saws (gang-saw/bridge-saw/wire-saw), polishes,
edge-profiles/CNC-shapes and inspects them — producing granite slabs,
marble slabs, limestone slabs, sandstone slabs, monuments/headstones,
countertops, dimension-stone tile and cut-stone veneer for
construction, monumental and decorative use. This is a **downstream
fabrication process from stone quarrying/extraction itself** (ISIC
0810, out of scope — this actor never proposes anything about a
quarry face, blast plan or block extraction; it starts at the
quarried-block intake). This actor's own hazard profile is centered on
the sawing/polishing line and airborne particulate: crystalline-
silica-dust hazard (respirable crystalline silica exposure at
sawing/polishing — silicosis is a well-documented, serious
occupational-health concern in dimension-stone fabrication, most acute
for silica-bearing stones such as granite and engineered/quartz-bearing
composites), saw-blade/wire-saw kickback and pinch-point hazard (gang
saw, bridge saw, wire saw), heavy-slab handling/crush hazard
(dimension-stone slabs are heavy and can crush a worker or tip during
handling), and polishing-line hazard (polish head pinch points, slurry
exposure).

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — product-type/weight/slab-thickness-tolerance/polish-gloss-level data logging (administrative, not an operational decision)
- `:schedule-maintenance` — sawing-line/polishing-line-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a sawing-line/polishing-line-equipment safety or crystalline-silica-dust-exposure concern (always escalates)
- `:coordinate-shipment` — outbound dimension-stone (slab/monument/countertop) shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(sawing/wire-saw kickback and pinch-point hazard, crystalline-silica-
dust exposure, heavy-slab handling/crush hazard, polishing-line
hazard):

- Does NOT control the sawing line or polishing line equipment directly
- Does NOT make plant-safety decisions (that's the plant supervisor's exclusive human authority)
- Does NOT actuate the sawing line or polishing line (human plant supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`stonemfg.operation/build`, a langgraph-clj StateGraph):
1. **`stonemfg.advisor`** (sealed intelligence node, `StoneAdvisor`): proposes decisions only, never commits
2. **`stonemfg.governor`** (independent, `Dimension Stone Plant Operations Governor`): validates against domain rules, re-derived from `stonemfg.registry`'s pure functions and `stonemfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct sawing-line/polishing-line-equipment control)
     - Directly actuating the sawing line or polishing line (`:actuate-sawing-polishing-line? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped weight past its own logged production weight (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch
     - No physically implausible `:slab-thickness-tolerance-mm` value on a production-batch patch
     - No physically implausible `:polish-gloss-level` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`stonemfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`stonemfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later

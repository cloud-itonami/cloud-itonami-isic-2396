# ADR-0001: StoneAdvisor ⊣ Dimension Stone Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2396` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2396` publishes an OSS blueprint for dimension-
stone (granite/marble/limestone/sandstone slab, monument, countertop)
plant **operations coordination** (production-batch product-type/
weight/slab-thickness-tolerance/polish-gloss-level data logging,
sawing-line/polishing-line-equipment maintenance scheduling, safety-
concern flagging, and outbound dimension-stone shipment coordination).
Like every actor in this fleet, the blueprint alone is not an
implementation: this ADR records the governed-actor architecture that
promotes it to real, tested code, following the same langgraph
StateGraph + independent Governor + Phase 0->3 rollout pattern
established across the cloud-itonami fleet.

The closest architectural analog is `cloud-itonami-isic-2393`
(Manufacture of other porcelain and ceramic products): both are
back-office coordination actors for a fixed processing PLANT with
heavy cutting/finishing-line equipment and a real physical safety
dimension, and both share the same four-op shape (`:log-production-
batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-
shipment`) and the same two-entity verified/registered gate structure
(equipment for maintenance scheduling, batch for shipment
coordination). The two verticals are, however, distinct plants with
distinct hazard profiles and distinct product/quality vocabularies:
2393's central physical hazard is the bisque/glost kiln-firing line
plus glaze-material-safety hazard (lead/cadmium leaching risk from
non-compliant glazes) and forming-line-equipment pinch-point hazard,
while 2396's is the sawing/polishing line plus crystalline-silica-dust
hazard (respirable crystalline silica exposure at sawing/polishing --
silicosis is a well-documented, serious occupational-health concern in
dimension-stone fabrication) and heavy-slab handling/crush hazard.
This build mirrors 2393's architecture closely but adapts the hazard
profile and equipment/product vocabulary to the dimension-stone
fabrication plant: 2396's permanent equipment-actuation block guards a
sawing line/polishing line (`:actuate-sawing-polishing-line?`) rather
than a forming line/kiln line (`:actuate-forming-kiln-line?`); and
2396's production-batch record declares a `:product-type` (spanning
granite, marble, limestone, sandstone slabs plus monument, countertop,
dimension-stone-tile and cut-stone-veneer families, per ISIC 2396's
own combined scope), a `:slab-thickness-tolerance-mm` reading
(deviation from nominal slab thickness -- a dimension-stone-specific
quality data point with no direct 2393 analog, gauged after sawing/
CNC-profiling), and a `:polish-gloss-level` reading (a 60-degree
glossmeter reading in gloss units relevant to polished slab/countertop
surface-finish quality), rather than 2393's `:glaze-defect-rate-
percent`/`:chip-resistance-newtons` pair.

`cloud-itonami-isic-2396` is also distinct from stone *quarrying/
extraction* itself (ISIC 0810) -- this actor is strictly a downstream
FABRICATION-plant coordinator starting at quarried-block intake; it
never proposes anything about a quarry face, blast plan or block
extraction, which this build does not depend on or wrap.

This vertical has NO pre-existing `kotoba-lang/stonemfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`stonemfg.registry` (equipment/batch verification, shipment-weight
recompute, product-type validation, slab-thickness-tolerance
plausibility validation, polish-gloss-level plausibility validation)
are re-verified independently by the governor, the same "ground truth,
not self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2393`'s `porcelainmfg.registry`, itself
modeled on `cloud-itonami-isic-2391`'s `refractorymfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:dimension-stone-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "dimension-stone-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created); the
`stonemfg` namespace prefix is likewise grep-verified UNIQUE
fleet-wide (`gh search code "stonemfg" --owner cloud-itonami`, zero
hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external dimension-stone-fabrication capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
dimension-stone vertical has NO pre-existing capability library to
wrap. The equipment/batch-verification / shipment-weight / product-
type / slab-thickness-tolerance / polish-gloss-level validation
functions live as pure functions in `stonemfg.registry` and are
re-verified independently by `stonemfg.governor` -- the same "ground
truth, not self-report" discipline established across prior actors
(most directly `cloud-itonami-isic-2393`'s `porcelainmfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of dimension-stone
plant operations. It does NOT:
- Control the sawing line or polishing line equipment directly
- Make plant-safety decisions (exclusive to the human plant supervisor)
- Actuate the sawing line or polishing line

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority --
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: dimension-stone cutting/shaping/
finishing is a safety-critical domain (saw-blade/wire-saw kickback and
pinch-point hazard, crystalline-silica-dust exposure, heavy-slab
handling/crush hazard, polishing-line hazard). Safety-concern flagging
NEVER auto-commits. All safety concerns escalate immediately to human
review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (sawing-line/polishing-line-equipment safety
concern, crystalline-silica-dust exposure, saw-blade/wire-saw kickback
hazard, heavy-slab handling/crush hazard, crew fatigue) ALWAYS
escalates, never auto-commits. This is not a "low-stakes proposal" --
it is a circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2393`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "plant/batch record must be independently verified/
registered before any action" HARD invariant applied to the two
distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether a
batch's own recorded shipped-to-date weight plus the proposal's own
claimed weight would exceed the batch's own recorded production
weight -- never taken on the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into eleven concrete checks
in `stonemfg.governor`, matching `cloud-itonami-isic-2393`'s own
eleven -- this vertical's `:slab-thickness-tolerance-mm` plausibility
check replaces 2393's `:glaze-defect-rate-percent` check, and its
`:polish-gloss-level` plausibility check replaces 2393's `:chip-
resistance-newtons` check, per Decision 1's own field-vocabulary
decision above) block proposals and cannot be overridden by human
approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's weight must independently recompute within the batch's own logged production weight
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct sawing-line/polishing-line-equipment control or sawing/polishing-line actuation is permanently blocked
4. The op allowlist is closed -- `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Dimension-stone plant operations back-office now has a documented,
governed, auditable coordination layer that funnels all decisions
through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into eleven concrete governor checks) protect against scope creep into
unauthorized equipment operation or sawing/polishing-line actuation.
Safety concerns are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and sawing/polishing-line
operation remain human-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch) -- this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-2396`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-weight-exceeded, sawing-polishing-line-
  actuate-blocked, already-scheduled, invalid-product-type, invalid-
  slab-thickness-tolerance, invalid-polish-gloss-level).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.

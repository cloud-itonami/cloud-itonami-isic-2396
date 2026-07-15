(ns stonemfg.registry
  "Pure-function domain logic for the dimension-stone (granite/marble/
  limestone/sandstone slab, monument, countertop) plant-operations
  coordination actor -- equipment/batch verification, shipment-weight
  recompute, product-type validation, slab-thickness-tolerance
  plausibility validation, polish-gloss-level plausibility validation,
  and draft maintenance-schedule/shipment-coordination record
  construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/stonemfg`-style capability library to wrap
  (verified: no such repo exists). The domain logic therefore lives
  here as pure functions, re-verified INDEPENDENTLY by
  `stonemfg.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes (e.g.
  `porcelainmfg.registry/shipment-weight-exceeded?` from
  `cloud-itonami-isic-2393`, the closest architectural sibling): never
  trust a proposal's own self-reported weight/status when the inputs
  needed to recompute it independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real plant-operations system. It builds the DRAFT record
  a plant coordinator would keep (a scheduled maintenance window, a
  coordinated shipment), not the act of actuating sawing-line or
  polishing-line equipment, or dispatching a real freight carrier
  (this actor NEVER does either -- see README `What this actor does
  NOT do`).

  SCOPE NOTE: ISIC 2396 (this actor) covers CUTTING, SHAPING AND
  FINISHING OF STONE -- dimension-stone fabrication from quarried
  blocks: quarried-block intake -> sawing (gang-saw/bridge-saw/
  wire-saw) -> polishing -> edge-profiling/CNC shaping -> inspection
  production lines that produce granite slabs, marble slabs, limestone
  slabs, sandstone slabs, monuments/headstones, countertops, dimension-
  stone tile and cut-stone veneer for construction, monumental and
  decorative use. This is a downstream FABRICATION process from stone
  quarrying/extraction itself (ISIC 0810, out of this actor's scope --
  this actor never proposes anything about a quarry face, blast plan
  or block extraction). This actor's own hazard profile is centered on
  the sawing/polishing line and airborne particulate: crystalline-
  silica-dust hazard (respirable crystalline silica exposure at
  sawing/polishing -- silicosis is a well-documented, serious
  occupational-health concern in dimension-stone fabrication, most
  acute for silica-bearing stones such as granite and engineered/
  quartz-bearing composites), saw-blade/wire-saw kickback and
  pinch-point hazard (gang saw, bridge saw, wire saw), heavy-slab
  handling/crush hazard (dimension-stone slabs are heavy and can crush
  a worker or tip during handling), and polishing-line hazard (polish
  head pinch points, slurry exposure)."
  )

;; ----------------------------- constants -----------------------------

(def valid-product-types
  "The closed set of product-type values a production batch (a sawn/
  polished lot of dimension stone) record may declare -- the standard
  dimension-stone product families this actor's plant may produce.
  Anything else is a fabricated/unrecognized product type -- the
  governor HARD-holds rather than let an invented product type
  through."
  #{:granite-slab :marble-slab :limestone-slab :sandstone-slab
    :monument :countertop :dimension-stone-tile :cut-stone-veneer})

(def slab-thickness-tolerance-min-mm
  "Physical floor for a batch's own slab-thickness-tolerance reading
  (deviation from nominal slab thickness, in millimeters -- negative
  means thinner than nominal, positive means thicker than nominal).
  No known gang-saw/bridge-saw/CNC-calibration process on a real
  fabrication line produces a deviation more extreme than this."
  -20.0)

(def slab-thickness-tolerance-max-mm
  "Physical ceiling for a batch's own slab-thickness-tolerance
  reading -- a reading beyond this is implausible inspection/gauge
  data, not a real batch."
  20.0)

(def polish-gloss-level-min-gu
  "Physical floor for a batch's own polish-gloss-level reading (60-
  degree glossmeter reading in gloss units -- zero gloss is the
  worst/unpolished extreme, never negative)."
  0.0)

(def polish-gloss-level-max-gu
  "Physical ceiling for a batch's own polish-gloss-level reading -- no
  known dimension-stone polishing line exceeds this glossmeter
  reading. A reading above this is implausible sensor/QC data, not a
  real batch."
  100.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the plant's
  equipment registry)? Scheduling maintenance against equipment that
  is not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it. Two independent facts on the equipment's own permanent record,
  neither inferred from the advisor's own rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its product-type/weight/slab-thickness-tolerance/polish-gloss-
  level claims have actually been QC-inspected, not merely logged from
  an unverified intake patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the plant's
  production ledger? Coordinating a shipment against a batch that is
  not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-weight-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal:
  would `shipped-to-date-kg` + `new-weight-kg` exceed `batch`'s own
  recorded `:weight-kg` (the batch's own logged production weight)?
  Needs no proposal inspection or stored-verdict lookup -- its inputs
  are permanent fields already on the batch's own record, the same
  shape every sibling actor's own cost/total-matching check uses."
  [batch new-weight-kg]
  (let [capacity (:weight-kg batch)
        so-far (:shipped-weight-kg batch 0.0)]
    (and (number? capacity)
         (number? new-weight-kg)
         (> (+ (double so-far) (double new-weight-kg)) (double capacity)))))

(defn product-type-valid?
  "Is `product-type` one of the closed, known dimension-stone product
  values? nil/blank is treated as invalid (a production-batch patch
  must declare a real product type, not omit it silently)."
  [product-type]
  (contains? valid-product-types product-type))

(defn slab-thickness-tolerance-valid?
  "Is `mm` a physically plausible batch slab-thickness-tolerance
  reading (deviation from nominal slab thickness, in millimeters,
  negative or positive)? Rejects nil, non-numbers, and values beyond
  the `slab-thickness-tolerance-min-mm`/`slab-thickness-tolerance-max-
  mm` band -- a fabricated or gauge-error reading, never let through
  as a real batch fact."
  [mm]
  (and (number? mm)
       (>= (double mm) slab-thickness-tolerance-min-mm)
       (<= (double mm) slab-thickness-tolerance-max-mm)))

(defn polish-gloss-level-valid?
  "Is `gu` a physically plausible batch polish-gloss-level reading, in
  gloss units (60-degree glossmeter reading)? Rejects nil, non-
  numbers, negative values, and values beyond `polish-gloss-level-max-
  gu` -- a fabricated or sensor-error reading, never let through as a
  real batch fact."
  [gu]
  (and (number? gu)
       (>= (double gu) polish-gloss-level-min-gu)
       (<= (double gu) polish-gloss-level-max-gu)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human plant supervisor's/shipping approver's act, not this
  actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  sawing-line/polishing-line-equipment maintenance window against a
  verified, registered piece of equipment. Pure function -- does not
  actuate the sawing-line or polishing-line equipment or execute any
  maintenance; it builds the RECORD a plant coordinator would keep.
  `stonemfg.governor` independently re-verifies the equipment's own
  verified/registered ground truth, and permanently blocks any
  attempt to directly actuate the sawing-line/polishing-line equipment
  (see README `Actuation`), before this is ever allowed to commit."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound dimension-stone (slab/monument/countertop) shipment against
  a verified, registered production batch. Pure function -- does not
  dispatch any real freight carrier; it builds the RECORD a plant
  coordinator would keep. `stonemfg.governor` independently
  re-verifies the shipment's own claimed weight against
  `shipment-weight-exceeded?`, before this is ever allowed to commit."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))

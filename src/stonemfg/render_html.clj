(ns stonemfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO demo page and no generator at all.

  This namespace drives the REAL actor stack
  (`stonemfg.advisor` -> `stonemfg.operation` graph ->
  `stonemfg.governor` -> `stonemfg.phase` -> `stonemfg.store`) through a
  scenario derived from this repo's own `stonemfg.sim` demo driver
  (`clojure -M:dev:run`, confirmed BEFORE writing this file to produce a
  sensible ledger against the real seeded ids `batch-001`..`batch-003` /
  `saw-001` / `polish-002`), extended with the cases `sim` does not
  cover (an approval the human REJECTS, a phase-gate hold at phase 1, an
  un-checkable shipment headroom, and a deliberately compromised advisor
  proposing a direct polishing-line actuation).

  EVERY row on the rendered page is derived from that run:

    - batches / equipment / maintenance drafts / shipment drafts /
      safety concerns come from `stonemfg.store` registers after the run
    - HARD holds, their rule keys and their `:detail` prose come from
      `stonemfg.governor`'s own emitted violations, read back off the
      store's append-only ledger
    - the rollout phase table comes from `stonemfg.phase/phases`
    - the closed sets / plausibility bands come from
      `stonemfg.governor` and `stonemfg.registry` vars
    - which ops are high-stakes, and which can never auto-commit at any
      phase, are DERIVED (`high-stakes-ops` off the run's own
      `:approval-requested` reasons, `never-auto-ops` off
      `stonemfg.phase/phases`) rather than asserted in prose. A sentence
      claiming a rule can never fire becomes a lie the moment someone
      changes the rule; a derived one leaves the page by itself.
    - the approval-attribution disclosure is MEASURED at render time
      (`approver-retained?`) by looking for the approver key in the
      store's own registers -- it is not a hardcoded claim, so it stops
      saying \"dropped\" the moment `stonemfg.store/commit-record!`
      starts reading `:payload`.

  Nothing on the page is hand-typed domain data, and nothing carries a
  wall-clock timestamp -- two runs against the same seed are
  byte-identical (verified by diffing two runs into scratch files).

  BUILD-TIME INVARIANT: `-main` THROWS unless every rule in
  `expected-hard-rules` actually fired during the run, and unless the
  ledger contains at least one `:governor-hold`. A console that quietly
  renders zero holds is a console that proves nothing.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [stonemfg.advisor :as advisor]
            [stonemfg.governor :as governor]
            [stonemfg.operation :as op]
            [stonemfg.phase :as phase]
            [stonemfg.registry :as registry]
            [stonemfg.store :as store]))

;; ----------------------------- invariant -----------------------------

(def expected-hard-rules
  "Every HARD rule `stonemfg.governor/check` can emit. The scenario below
  must actually fire all of them -- `-main` throws otherwise. This is the
  build-time invariant, not a comment: a demo that stops exercising a
  governor rule fails the build instead of silently shipping a thinner
  page."
  #{:not-propose-effect
    :unknown-op
    :sawing-polishing-line-control-blocked
    :sawing-polishing-line-actuate-blocked
    :equipment-not-verified
    :already-scheduled
    :batch-not-verified
    :shipment-weight-exceeded
    :invalid-product-type
    :invalid-slab-thickness-tolerance
    :invalid-polish-gloss-level})

;; ----------------------------- scenario -----------------------------

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(def ^:private phase-1-coordinator
  "The SAME coordinator earlier in the staged rollout -- used to show the
  phase gate holding a write that phase 3 would allow."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 1})

(defn- log!
  "Record (or replace) a thread's audit trail. A resumed run's `:audit`
  channel is cumulative (`:reducer into` + checkpoint restore, measured),
  so the latest run for a thread supersedes the earlier one."
  [run-log tid result]
  (swap! run-log
         (fn [v]
           (let [entry {:thread tid :audit (vec (:audit (:state result)))}
                 i (first (keep-indexed (fn [i e] (when (= tid (:thread e)) i)) v))]
             (if i (assoc v i entry) (conj v entry)))))
  result)

(defn- exec! [run-log actor tid request context]
  (log! run-log tid (g/run* actor {:request request :context context} {:thread-id tid})))

(defn- resume! [run-log actor tid approval]
  (log! run-log tid (g/run* actor {:approval approval} {:thread-id tid :resume? true})))

(defn- direct-control-advisor
  "A DELIBERATELY compromised advisor: it returns a proposal whose own
  `:effect` is a direct polishing-line actuation, i.e. outside
  `stonemfg.governor/allowed-proposal-effects`. Used to exercise the
  closed proposal-effect allowlist on an op that IS allowed -- the
  `:unknown-op` path reaches the same rule only incidentally (its
  fallback proposal is `:noop`). Nothing here invents domain facts; it
  only restates the caller's own subject/value."
  []
  (reify advisor/Advisor
    (-advise [_ _st req]
      {:summary   (str (:subject req) " 向け提案 (研磨ライン直接操作 effect)")
       :rationale "compromised advisor fixture -- proposal :effect が許可リスト外"
       :cites     []
       :effect    :polishing-line/actuate
       :value     (:value req)
       :stake     nil
       :confidence 0.95})))

(defn run-demo!
  "Runs a fresh seeded store through the full disposition space of this
  actor and returns `{:db .. :audit ..}`.

  Approved / auto lifecycle:
    t01 `:log-production-batch` on `batch-001` with a clean patch --
        the ONLY op eligible to auto-commit, and only at phase 3.
    t02 `:schedule-maintenance` `mnt-1` on `saw-001` (verified +
        registered bridge saw) -- ALWAYS escalates (never in any
        phase's `:auto` set), approved by a human plant supervisor.
    t03 `:flag-safety-concern` `concern-1` on `saw-001` -- ALWAYS
        escalates (`:coordination/safety-concern` is permanently
        high-stakes), approved.
    t04 `:coordinate-shipment` `ship-1` on `batch-001` (5000 kg against
        20000 kg logged / 4000 kg already shipped) -- escalates,
        approved by the shipping approver.
    t05 `:flag-safety-concern` `concern-2` on `polish-002`, an
        UNVERIFIED/unregistered unit -- deliberately NOT blocked: safety
        reporting is never gated on an administrative technicality.
    t06 `:schedule-maintenance` `mnt-4` on `saw-001` -- escalates and the
        human REJECTS it (`:approval-rejected`, no SSoT mutation).

  Phase gate (no governor violation at all -- the rollout stage alone
  holds the write):
    t07 `:coordinate-shipment` `ship-5` run as a PHASE-1 coordinator.

  HARD holds -- none of these ever reaches a human:
    t08 `:not-propose-effect`                     (caller declares :direct-write)
    t09 `:unknown-op` + `:sawing-polishing-line-control-blocked`
    t10 `:sawing-polishing-line-control-blocked`  (compromised advisor, allowed op)
    t11 `:equipment-not-verified`                 (`mnt-2` on `polish-002`)
    t12 `:batch-not-verified`                     (`ship-2` on `batch-003`)
    t13 `:shipment-weight-exceeded`               (`ship-3`: 5600+1000 > 6000 on `batch-002`)
    t14 `:shipment-weight-exceeded`               (`ship-4`: headroom un-checkable, no stated weight)
    t15 `:sawing-polishing-line-actuate-blocked`  (`mnt-3`, PERMANENT, no override)
    t16 `:already-scheduled`                      (`mnt-1` a second time)
    t17 `:invalid-product-type`
    t18 `:invalid-slab-thickness-tolerance`
    t19 `:invalid-polish-gloss-level`"
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        compromised (op/build db {:advisor (direct-control-advisor)})
        run-log (atom [])]

    (exec! run-log actor "t01"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :granite-slab :last-assessed "2026-07-14"}}
           coordinator)

    (exec! run-log actor "t02"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "saw-001" :maintenance-type :blade-inspection
                    :scheduled-date "2026-08-01" :actuate-sawing-polishing-line? false}}
           coordinator)
    (resume! run-log actor "t02" {:status :approved :by "supervisor-1"})

    (exec! run-log actor "t03"
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "saw-001" :severity :moderate
                    :description "ブリッジソー切断部付近の結晶質シリカ粉塵濃度上昇、集塵機点検要"}}
           coordinator)
    (resume! run-log actor "t03" {:status :approved :by "supervisor-1"})

    (exec! run-log actor "t04"
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :weight-kg 5000.0
                    :destination "buyer-showroom-north"}}
           coordinator)
    (resume! run-log actor "t04" {:status :approved :by "shipping-approver-1"})

    (exec! run-log actor "t05"
           {:op :flag-safety-concern :effect :propose :subject "concern-2"
            :value {:equipment-id "polish-002" :severity :high
                    :description "研磨ラインのポリッシュヘッド周辺で挟まれ危険、防護カバー未確認"}}
           coordinator)
    (resume! run-log actor "t05" {:status :approved :by "supervisor-1"})

    (exec! run-log actor "t06"
           {:op :schedule-maintenance :effect :propose :subject "mnt-4"
            :value {:equipment-id "saw-001" :maintenance-type :spindle-overhaul
                    :scheduled-date "2026-08-15" :actuate-sawing-polishing-line? false}}
           coordinator)
    (resume! run-log actor "t06" {:status :rejected :by "supervisor-1"})

    (exec! run-log actor "t07"
           {:op :coordinate-shipment :effect :propose :subject "ship-5"
            :value {:batch-id "batch-001" :weight-kg 1000.0
                    :destination "buyer-showroom-west"}}
           phase-1-coordinator)

    (exec! run-log actor "t08"
           {:op :log-production-batch :effect :direct-write :subject "batch-001"
            :patch {:product-type :granite-slab}}
           coordinator)

    (exec! run-log actor "t09"
           {:op :actuate-sawing-line :effect :propose :subject "batch-001"}
           coordinator)

    (exec! run-log compromised "t10"
           {:op :schedule-maintenance :effect :propose :subject "mnt-5"
            :value {:equipment-id "saw-001" :maintenance-type :pad-change
                    :scheduled-date "2026-08-20"}}
           coordinator)

    (exec! run-log actor "t11"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "polish-002" :maintenance-type :pad-inspection
                    :scheduled-date "2026-08-01" :actuate-sawing-polishing-line? false}}
           coordinator)

    (exec! run-log actor "t12"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-003" :weight-kg 1000.0
                    :destination "buyer-showroom-south"}}
           coordinator)

    (exec! run-log actor "t13"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :weight-kg 1000.0
                    :destination "buyer-showroom-east"}}
           coordinator)

    (exec! run-log actor "t14"
           {:op :coordinate-shipment :effect :propose :subject "ship-4"
            :value {:batch-id "batch-001" :destination "buyer-showroom-north"}}
           coordinator)

    (exec! run-log actor "t15"
           {:op :schedule-maintenance :effect :propose :subject "mnt-3"
            :value {:equipment-id "saw-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-01" :actuate-sawing-polishing-line? true}}
           coordinator)

    (exec! run-log actor "t16"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "saw-001" :maintenance-type :blade-inspection
                    :scheduled-date "2026-08-01" :actuate-sawing-polishing-line? false}}
           coordinator)

    (exec! run-log actor "t17"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :unobtainium-stone}}
           coordinator)

    (exec! run-log actor "t18"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:slab-thickness-tolerance-mm 250.0}}
           coordinator)

    (exec! run-log actor "t19"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:polish-gloss-level 9999.0}}
           coordinator)

    {:db db
     :audit (vec (mapcat :audit @run-log))}))

;; ----------------------------- derived views -----------------------------

(defn hard-holds
  "The `:governor-hold` ledger facts that carry at least one real
  governor violation (as opposed to a phase-gate hold, which carries
  none)."
  [db]
  (filterv #(and (= :governor-hold (:t %)) (seq (:violations %))) (store/ledger db)))

(defn phase-holds
  "The `:governor-hold` ledger facts produced by the rollout phase gate
  alone -- the governor itself found nothing."
  [db]
  (filterv #(and (= :governor-hold (:t %)) (empty? (:violations %))) (store/ledger db)))

(defn fired-rules
  "rule -> {:count n :detail <the governor's own first emitted detail>}"
  [db]
  (reduce (fn [m v]
            (update m (:rule v)
                    (fn [e] {:count (inc (:count e 0))
                             :detail (or (:detail e) (:detail v))})))
          {}
          (mapcat :violations (hard-holds db))))

(defn approver-retained?
  "MEASURED, not asserted: after the run, does the store's own register
  for `subject` actually carry the approver identity the graph recorded?

  `stonemfg.operation` puts `:approved-by` on the commit record's
  `:payload`; `stonemfg.store/commit-record!` destructures `:value` and
  never reads `:payload`. Whether that means the approver is lost is a
  property of the store, so it is measured here rather than asserted --
  this whole disclosure flips to `retained` on its own the day the store
  starts reading `:payload`."
  [db subject]
  (boolean
   (some #(contains? % :approved-by)
         (filter map?
                 [(store/maintenance db subject)
                  (store/shipment db subject)
                  (store/batch db subject)
                  (first (filter #(= subject (:id %)) (store/safety-concerns db)))
                  (get (store/get-records db) subject)]))))

(defn approval-grants
  "Every `:approval-granted` graph-audit fact, joined against the store
  to say whether the approver survived the commit."
  [db audit]
  (mapv (fn [{:keys [op subject by]}]
          {:op op :subject subject :by by
           :retained? (approver-retained? db subject)})
        (filter #(= :approval-granted (:t %)) audit)))

(defn escalation-reasons
  "op -> {reason -> times}, read off the run's own `:approval-requested`
  facts.

  `stonemfg.operation` labels an escalation `:requires-supervisor-approval`
  when the GOVERNOR escalated on stake/confidence, and `:phase-approval`
  when the governor was clean and only the rollout phase withheld
  auto-commit. Both are observations of this run, so the page never has to
  restate which ops are high-stakes -- if the advisor stops assigning a
  high-stakes stake, or `stonemfg.governor/high-stakes` changes, this map
  changes with it."
  [audit]
  (reduce (fn [m {:keys [op reason]}] (update-in m [op reason] (fnil inc 0)))
          {}
          (filter #(= :approval-requested (:t %)) audit)))

(defn high-stakes-ops
  "Ops this run actually saw escalate because the GOVERNOR found the
  proposal high-stakes -- derived, never asserted."
  [audit]
  (into #{}
        (keep (fn [[op reasons]]
                (when (pos? (get reasons :requires-supervisor-approval 0)) op)))
        (escalation-reasons audit)))

(defn never-auto-ops
  "Write ops that appear in NO phase's `:auto` set -- computed from
  `stonemfg.phase/phases` itself.

  The page used to assert in prose that `:schedule-maintenance` can never
  auto-commit at any phase. That sentence would have gone on being printed
  the day someone added it to a phase's `:auto` set, directly above a table
  showing the opposite. Deriving the set instead means the claim leaves the
  page on its own."
  []
  (let [auto-anywhere (into #{} (mapcat :auto) (vals phase/phases))]
    (into (sorted-set) (remove auto-anywhere phase/write-ops))))

;; ----------------------------- html -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- kws [coll] (str/join ", " (sort (map kw coll))))

(defn- yn [b] (if b "<span class=\"ok\">yes</span>" "<span class=\"warn\">no</span>"))

(defn- tr [& cells] (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lede "</p>\n"
       body
       "  </section>\n"))

(defn- last-fact-for [ledger subject]
  (last (filter #(= subject (:subject %)) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"warn\">approval rejected</span>"
      (and (= :governor-hold (:t f)) (seq (:violations f)))
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (kw (:rule (first (:violations f))))) "</span>")
      (= :governor-hold (:t f))
      (str "<span class=\"warn\">phase hold &middot; " (esc (kw (:phase-reason f))) "</span>")
      :else "<span class=\"muted\">in progress</span>")))

;; --- sections -------------------------------------------------------------

(defn- batches-section [db]
  (let [ledger (store/ledger db)]
    (section
     "生産バッチ / Production batches"
     (str "Seeded dimension-stone lots after the run. "
          "<code>shipped</code> is the batch's own cumulative-shipped ground truth, "
          "advanced only by a committed <code>:shipment/propose</code>; "
          "headroom is what <code>stonemfg.registry/shipment-weight-exceeded?</code> recomputes against.")
     (table ["Batch" "Material" "Product type" "Logged kg" "Shipped kg" "Headroom kg"
             "厚み公差 mm" "光沢 GU" "Verified" "Registered" "Last op"]
            (for [{:keys [id material product-type weight-kg shipped-weight-kg
                          slab-thickness-tolerance-mm polish-gloss-level
                          verified? registered?]} (store/all-batches db)]
              (tr (str "<code>" (esc id) "</code>")
                  (esc material)
                  (str "<code>" (esc (kw product-type)) "</code>")
                  (esc weight-kg)
                  (esc shipped-weight-kg)
                  (esc (- (double weight-kg) (double (or shipped-weight-kg 0.0))))
                  (esc slab-thickness-tolerance-mm)
                  (esc polish-gloss-level)
                  (yn verified?) (yn registered?)
                  (status-cell ledger id)))))))

(defn- equipment-section [db]
  (let [ledger (store/ledger db)]
    (section
     "切断・研磨ライン設備 / Sawing &amp; polishing-line equipment"
     (str "Maintenance may only be scheduled against a unit that is independently "
          "<code>:verified?</code> AND <code>:registered?</code> "
          "(<code>stonemfg.registry/equipment-ready?</code>) -- never on the advisor's own report.")
     (table ["Equipment" "Kind" "Verified" "Registered" "Last maintenance" "Last scheduled"]
            (for [{:keys [id kind verified? registered?
                          last-maintenance-date last-scheduled-maintenance-date]} (store/all-equipment db)]
              (tr (str "<code>" (esc id) "</code>")
                  (str "<code>" (esc (kw kind)) "</code>")
                  (yn verified?) (yn registered?)
                  (if last-maintenance-date (esc last-maintenance-date)
                      "<span class=\"muted\">never</span>")
                  (if last-scheduled-maintenance-date (esc last-scheduled-maintenance-date)
                      "<span class=\"muted\">--</span>")))
            )))
  )

(defn- phase-section [ledger]
  (section
   "ロールアウト段階ゲート / Rollout phase gate"
   (str "Straight out of <code>stonemfg.phase/phases</code>. "
        "This console ran at phase " phase/default-phase " unless a row below says otherwise. "
        (let [never (never-auto-ops)]
          (if (seq never)
            (str "Computed from that same table (<code>stonemfg.render-html/never-auto-ops</code>): "
                 "<code>" (esc (kws never)) "</code> "
                 (if (= 1 (count never)) "is a member" "are members")
                 " of <code>write-ops</code> but of NO phase's <code>:auto</code> set at any phase, "
                 "so however clean the governor is, "
                 (if (= 1 (count never)) "it" "they")
                 " always reach a human. This sentence is derived, not asserted -- an op that gains "
                 "auto-eligibility drops out of it.")
            "Every write op is auto-eligible at some phase.")))
   (str
    (table ["Phase" "Label" "May write" "May auto-commit when governor-clean"]
           (for [[p {:keys [label writes auto]}] (sort-by key phase/phases)]
             (tr (esc p)
                 (esc label)
                 (if (seq writes) (str "<code>" (esc (kws writes)) "</code>")
                     "<span class=\"muted\">none (read-only)</span>")
                 (if (seq auto) (str "<code>" (esc (kws auto)) "</code>")
                     "<span class=\"muted\">none -- every write needs a human</span>"))))
    (if-let [ph (seq (filter #(and (= :governor-hold (:t %)) (empty? (:violations %))) ledger))]
      (str "    <p class=\"muted\">Phase-gate holds observed in this run (governor itself clean):</p>\n"
           (table ["Op" "Subject" "Phase" "Reason"]
                  (for [f ph]
                    (tr (str "<code>" (esc (kw (:op f))) "</code>")
                        (str "<code>" (esc (:subject f)) "</code>")
                        (esc (:phase f))
                        (str "<span class=\"warn\">" (esc (kw (:phase-reason f))) "</span>")))))
      ""))))

(defn- gate-section [db audit]
  (let [ledger (store/ledger db)
        holds-by-op (group-by :op (hard-holds db))
        committed-by-op (group-by :op (filter #(= :committed (:t %)) ledger))
        reasons (escalation-reasons audit)]
    (section
     "アクションゲート / Action gate (Dimension Stone Plant Operations Governor)"
     (str "The four ops this actor may ever route (<code>stonemfg.governor/allowed-ops</code>), "
          "with what this run actually did with each. Every column is counted from the run or "
          "read off <code>stonemfg.phase/phases</code> -- none of it is a restatement. "
          "<code>requires-supervisor-approval</code> means the GOVERNOR escalated on stake or "
          "confidence (<code>stonemfg.governor/high-stakes</code> = <code>"
          (esc (kws governor/high-stakes)) "</code>); <code>phase-approval</code> means the "
          "governor was clean and the rollout phase alone withheld auto-commit.")
     (table ["Op" "Auto-eligible at phase 3" "Escalated this run as" "Committed this run" "HARD-held this run"]
            (for [o (sort-by kw governor/allowed-ops)]
              (tr (str "<code>" (esc (kw o)) "</code>")
                  (if (contains? (get-in phase/phases [3 :auto]) o)
                    "<span class=\"ok\">yes -- when governor-clean</span>"
                    "<span class=\"warn\">no -- a human decides</span>")
                  (if-let [rs (seq (sort-by (comp kw key) (get reasons o)))]
                    (str/join "<br>"
                              (for [[r n] rs]
                                (str (if (= :requires-supervisor-approval r)
                                       "<span class=\"warn\">" "<span class=\"muted\">")
                                     "<code>" (esc (kw r)) "</code> &times;" (esc n) "</span>")))
                    "<span class=\"muted\">never escalated</span>")
                  (esc (count (get committed-by-op o)))
                  (let [n (count (get holds-by-op o))]
                    (if (pos? n) (str "<span class=\"critical\">" n "</span>") "0"))))))))

(defn- hard-rule-section [db]
  (let [fired (fired-rules db)]
    (section
     "HARD ホールド規則 / HARD hold rules exercised by this run"
     (str "Every rule below is a build-time invariant of this generator "
          "(<code>stonemfg.render-html/expected-hard-rules</code>): if any of them stops firing, "
          "<code>-main</code> throws and no console is written. "
          "The <em>detail</em> column is the governor's own emitted prose, read back off the ledger -- "
          "it is not restated here.")
     (table ["Rule" "Times fired" "Governor's own detail"]
            (for [r (sort-by kw expected-hard-rules)
                  :let [{:keys [count detail]} (get fired r)]]
              (tr (str "<code>" (esc (kw r)) "</code>")
                  (str "<span class=\"critical\">" (esc (or count 0)) "</span>")
                  (esc detail)))))))

(defn- holds-section [db]
  (section
   "監査: HARD ホールド一覧 / HARD holds, in ledger order"
   "Each of these was refused before any SSoT mutation and never reached a human."
   (table ["Op" "Subject" "Rules" "Advisor confidence" "Detail"]
          (for [{:keys [op subject violations confidence]} (hard-holds db)]
            (tr (str "<code>" (esc (kw op)) "</code>")
                (str "<code>" (esc subject) "</code>")
                (str "<span class=\"critical\">" (esc (kws (map :rule violations))) "</span>")
                (esc confidence)
                (esc (str/join " / " (map :detail violations))))))))

(defn- maintenance-section [db]
  (let [history (store/maintenance-history db)
        by-num (into {} (map (juxt :maintenance-number identity) (store/all-maintenance db)))]
    (section
     "保守作業予定ドラフト / Maintenance schedule drafts"
     (str "Committed <code>:maintenance/schedule</code> records. These are DRAFTS -- "
          "<code>stonemfg.registry/register-maintenance</code> builds the record a coordinator keeps; "
          "the certificate it issues is <code>draft-unsigned</code>, because signing is the human "
          "supervisor's act, not this actor's. Nothing here actuates a saw or a polishing head.")
     (if (seq history)
       (table ["Record" "Kind" "Maintenance" "Equipment" "Type" "Scheduled date" "Immutable"]
              (for [r history
                    :let [m (get by-num (get r "record_id"))]]
                (tr (str "<code>" (esc (get r "record_id")) "</code>")
                    (esc (get r "kind"))
                    (str "<code>" (esc (get r "maintenance_id")) "</code>")
                    (str "<code>" (esc (get r "equipment_id")) "</code>")
                    (str "<code>" (esc (kw (:maintenance-type m))) "</code>")
                    (esc (:scheduled-date m))
                    (yn (get r "immutable")))))
       "    <p class=\"muted\">none committed</p>\n"))))

(defn- shipment-section [db]
  (let [history (store/shipment-history db)]
    (section
     "出荷調整ドラフト / Shipment coordination drafts"
     (str "Committed <code>:shipment/propose</code> records. Also drafts -- no freight carrier is "
          "ever dispatched. Each one's weight was independently recomputed against the batch's own "
          "logged production weight before it was allowed through.")
     (if (seq history)
       (table ["Record" "Kind" "Shipment" "Batch" "Weight kg" "Destination" "Immutable"]
              (for [r history
                    :let [s (store/shipment db (get r "shipment_id"))]]
                (tr (str "<code>" (esc (get r "record_id")) "</code>")
                    (esc (get r "kind"))
                    (str "<code>" (esc (get r "shipment_id")) "</code>")
                    (str "<code>" (esc (:batch-id s)) "</code>")
                    (esc (:weight-kg s))
                    (esc (:destination s))
                    (yn (get r "immutable")))))
       "    <p class=\"muted\">none committed</p>\n"))))

(defn- safety-section [db audit]
  (let [concerns (store/safety-concerns db)
        n-escalated (get-in (escalation-reasons audit)
                            [:flag-safety-concern :requires-supervisor-approval] 0)]
    (section
     "安全懸念ログ / Safety-concern log"
     (str (if (pos? n-escalated)
            (str "Observed in this run: <strong>" (esc n-escalated) " of " (esc (count concerns))
                 "</strong> <code>:flag-safety-concern</code> request"
                 (when (not= 1 n-escalated) "s")
                 " escalated to a human with reason "
                 "<code>requires-supervisor-approval</code> -- the governor's own high-stakes gate "
                 "fired, not the rollout phase. ")
            (str "This run recorded no governor-driven escalation for "
                 "<code>:flag-safety-concern</code>. "))
          "The <code>Equipment verified?</code> column below is the second half of the story: this op "
          "is deliberately NOT gated on the referenced unit being verified/registered -- "
          "safety reporting is never blocked on an administrative technicality.")
     (if (seq concerns)
       (table ["Concern" "Equipment" "Equipment verified?" "Severity" "Description"]
              (for [{:keys [id equipment-id severity description]} concerns
                    :let [eq (store/equipment-unit db equipment-id)]]
                (tr (str "<code>" (esc id) "</code>")
                    (str "<code>" (esc equipment-id) "</code>")
                    (yn (registry/equipment-ready? eq))
                    (str (if (= :high severity) "<span class=\"critical\">" "<span class=\"warn\">")
                         (esc (kw severity)) "</span>")
                    (esc description))))
       "    <p class=\"muted\">none flagged</p>\n"))))

(defn- approval-section [db audit]
  (let [grants (approval-grants db audit)
        rejects (filter #(= :approval-rejected (:t %)) (store/ledger db))
        any-retained? (boolean (some :retained? grants))]
    (section
     "承認の帰属 / Approval attribution"
     (str "Measured, not asserted. <code>stonemfg.operation</code> puts <code>:approved-by</code> on "
          "the commit record's <code>:payload</code>; this section then looks for that key in the "
          "store's own register for each subject "
          "(<code>stonemfg.render-html/approver-retained?</code>). "
          (if any-retained?
            (str "<strong>Observed in this run: the approver IS retained in the committed record.</strong>")
            (str "<strong>Observed in this run: the approver is NOT retained in any committed record</strong> -- "
                 "<code>stonemfg.store/commit-record!</code> destructures <code>:value</code> and never "
                 "reads <code>:payload</code>, so the identity below survives only in the graph audit. "
                 "This sentence is derived from the live store, so it will stop appearing on its own "
                 "once the store reads <code>:payload</code>."))
          )
     (str
      (table ["Op" "Subject" "Approver" "Retained in committed record?"]
             (for [{:keys [op subject by retained?]} grants]
               (tr (str "<code>" (esc (kw op)) "</code>")
                   (str "<code>" (esc subject) "</code>")
                   (str "<code>" (esc by) "</code>")
                   (if retained?
                     "<span class=\"ok\">yes -- readable from the store</span>"
                     "<span class=\"warn\">no &middot; audit only -- not retained in record</span>"))))
      (if (seq rejects)
        (str "    <p class=\"muted\">Approvals the human REJECTED (no SSoT mutation):</p>\n"
             (table ["Op" "Subject" "Rule"]
                    (for [f rejects]
                      (tr (str "<code>" (esc (kw (:op f))) "</code>")
                          (str "<code>" (esc (:subject f)) "</code>")
                          (str "<span class=\"warn\">"
                               (esc (kws (map :rule (:violations f)))) "</span>")))))
        "")))))

(defn- closed-sets-section []
  (section
   "閉じた集合と妥当性帯域 / Closed sets and plausibility bands"
   (str "Read straight off <code>stonemfg.governor</code> and <code>stonemfg.registry</code> vars. "
        "Anything outside these is refused rather than normalised.")
   (table ["Constraint" "Value"]
          [(tr "<code>governor/allowed-ops</code>"
               (str "<code>" (esc (kws governor/allowed-ops)) "</code>"))
           (tr "<code>governor/allowed-proposal-effects</code>"
               (str "<code>" (esc (kws governor/allowed-proposal-effects)) "</code>"))
           (tr "<code>governor/high-stakes</code>"
               (str "<code>" (esc (kws governor/high-stakes)) "</code>"))
           (tr "<code>governor/confidence-floor</code>"
               (str "<code>" (esc governor/confidence-floor) "</code>"))
           (tr "<code>registry/valid-product-types</code>"
               (str "<code>" (esc (kws registry/valid-product-types)) "</code>"))
           (tr "スラブ厚み公差 / slab-thickness-tolerance band"
               (str "<code>" (esc registry/slab-thickness-tolerance-min-mm) " .. "
                    (esc registry/slab-thickness-tolerance-max-mm) " mm</code>"))
           (tr "研磨光沢度 / polish-gloss-level band"
               (str "<code>" (esc registry/polish-gloss-level-min-gu) " .. "
                    (esc registry/polish-gloss-level-max-gu) " GU</code>"))])))

(defn- ledger-section [db]
  (section
   "監査台帳 / Append-only audit ledger (this run)"
   "Every decision fact the store recorded, in order. No SSoT mutation happens off this log."
   (table ["#" "Fact" "Op" "Subject" "Disposition" "Basis"]
          (map-indexed
           (fn [i {:keys [t op subject disposition basis phase-reason]}]
             (tr (esc (inc i))
                 (str "<code>" (esc (kw t)) "</code>")
                 (str "<code>" (esc (kw op)) "</code>")
                 (str "<code>" (esc subject) "</code>")
                 (case disposition
                   :commit "<span class=\"ok\">commit</span>"
                   :hold "<span class=\"critical\">hold</span>"
                   (esc (kw disposition)))
                 (cond
                   (seq basis) (str "<code>" (esc (kws basis)) "</code>")
                   phase-reason (str "<code>" (esc (kw phase-reason)) "</code>")
                   :else "<span class=\"muted\">--</span>")))
           (store/ledger db)))))

(defn- graph-audit-section [audit]
  (section
   "グラフ監査トレース / StateGraph audit trail (this run)"
   (str "The <code>:audit</code> channel of every <code>stonemfg.operation</code> run, including the "
        "advisor proposals the governor then censored. The advisor's <em>rationale</em> is "
        "informational only -- the governor re-derives every ground-truth fact itself and never "
        "trusts this column.")
   (table ["#" "Fact" "Op" "Subject" "Confidence" "Summary / reason"]
          (map-indexed
           (fn [i {:keys [t op subject confidence summary reason by]}]
             (tr (esc (inc i))
                 (str "<code>" (esc (kw t)) "</code>")
                 (str "<code>" (esc (kw op)) "</code>")
                 (str "<code>" (esc subject) "</code>")
                 (if (some? confidence) (esc confidence) "<span class=\"muted\">--</span>")
                 (esc (or summary
                          (when reason (kw reason))
                          (when by (str "approved by " by))
                          ""))))
           audit))))

(defn render
  "Renders the whole document from a `run-demo!` result."
  [{:keys [db audit]}]
  (str
   "<!doctype html>\n"
   "<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
   "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
   "<title>cloud-itonami-isic-2396 &middot; 石材の切断・成形・仕上げ / Operator Console</title>\n"
   "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style></head><body>\n"
   "<header class=\"bar\">\n"
   "  <h1>石材の切断・成形・仕上げ (ISIC 2396) — Operator Console</h1>\n"
   "  <p class=\"badge\">read-only sample &middot; governor-gated &middot; "
   "提案のみ (propose-only) &middot; 切断ライン・研磨ラインの直接操作は恒久的に不可</p>\n"
   "</header>\n"
   "<main>\n"
   (section
    "この頁について / About this page"
    (str "Build-time generated by <code>stonemfg.render-html</code> "
         "(<code>clojure -M:dev:render-html</code>) from ONE real run of this repo's actor: "
         "<code>stonemfg.advisor</code> &rarr; <code>stonemfg.operation</code> StateGraph &rarr; "
         "<code>stonemfg.governor</code> &rarr; <code>stonemfg.phase</code> &rarr; "
         "<code>stonemfg.store</code>. No figure below is hand-typed and no wall-clock timestamp "
         "appears anywhere, so two runs against the same seed are byte-identical.")
    (table ["Measure" "Value"]
           [(tr "ledger facts" (esc (count (store/ledger db))))
            (tr "graph audit facts" (esc (count audit)))
            (tr "committed ops"
                (esc (count (filter #(= :committed (:t %)) (store/ledger db)))))
            (tr "HARD holds"
                (str "<span class=\"critical\">" (esc (count (hard-holds db))) "</span>"))
            (tr "phase-gate holds" (esc (count (phase-holds db))))
            (tr "distinct HARD rules exercised"
                (str (esc (count (fired-rules db))) " / " (esc (count expected-hard-rules))
                     " (build-time invariant)"))
            (tr "maintenance drafts" (esc (count (store/maintenance-history db))))
            (tr "shipment drafts" (esc (count (store/shipment-history db))))
            (tr "safety concerns" (esc (count (store/safety-concerns db))))]))
   (batches-section db)
   (equipment-section db)
   (gate-section db audit)
   (phase-section (store/ledger db))
   (hard-rule-section db)
   (holds-section db)
   (maintenance-section db)
   (shipment-section db)
   (safety-section db audit)
   (approval-section db audit)
   (closed-sets-section)
   (ledger-section db)
   (graph-audit-section audit)
   "</main>\n"
   "<footer>\n"
   "  <p>cloud-itonami-isic-2396 &middot; StoneAdvisor &#8891; Dimension Stone Plant Operations Governor "
   "&middot; AGPL-3.0-or-later &middot; この actor は提案のみを行い、切断設備・研磨ラインを一切操作しない。</p>\n"
   "</footer>\n"
   "</body></html>\n"))

;; ----------------------------- entry point -----------------------------

(defn- assert-holds!
  "Build-time invariant. Refuses to write a console that does not
  actually demonstrate the governor refusing things."
  [db]
  (let [holds (hard-holds db)
        fired (set (keys (fired-rules db)))
        missing (sort-by kw (remove fired expected-hard-rules))
        unexpected (sort-by kw (remove expected-hard-rules fired))]
    (when (empty? holds)
      (throw (ex-info "REFUSING to write the console: the run produced ZERO :governor-hold facts."
                      {:ledger-facts (count (store/ledger db))})))
    (when (seq missing)
      (throw (ex-info (str "REFUSING to write the console: HARD rules never fired: "
                           (str/join ", " (map kw missing)))
                      {:missing (vec missing) :fired (vec (sort-by kw fired))})))
    (when (seq unexpected)
      (throw (ex-info (str "REFUSING to write the console: the governor emitted rules this "
                           "generator does not know about: " (str/join ", " (map kw unexpected)))
                      {:unexpected (vec unexpected)})))
    holds))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db audit] :as result} (run-demo!)
        holds (assert-holds! db)
        html (render result)]
    (spit out html)
    (println "wrote" out
             (str "(" (count html) " bytes, "
                  (count (store/ledger db)) " ledger facts, "
                  (count audit) " graph audit facts, "
                  (count holds) " HARD holds across "
                  (count (fired-rules db)) "/" (count expected-hard-rules) " rules, "
                  (count (store/maintenance-history db)) " maintenance drafts, "
                  (count (store/shipment-history db)) " shipment drafts, "
                  (count (store/safety-concerns db)) " safety concerns)"))))

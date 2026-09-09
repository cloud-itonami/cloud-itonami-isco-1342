(ns healthsvc.sim
  "Deterministic governed-scenario harness for the ISCO-08 1342 health
  services managers actor: run a table of requests through the real
  StateGraph and report which ones the governor refused.

  Runtime: `run` and `report` are portable `.cljc`. `-main` is `:clj`-only,
  because process exit codes are a host concern; the `:cljs` branch throws
  rather than pretending to exit.

  Why this namespace exists, and why it fails loudly. A governed actor's
  claim is not that it acts — it is that there exist actions it refuses. A
  harness that ran only clean scenarios would print green while demonstrating
  nothing, which is the shape this workspace has repeatedly caught: a check
  that could not fail returning the same value as a check that passed.

  So `run` counts refusals, and `-main` exits non-zero when the count is zero.
  A scenario table that has stopped exercising the governor is a defect in the
  table, and it is reported as one rather than as a pass.

  The three questions this harness answers that a unit test does not:
    * does the *wired graph* refuse, or only the pure `check` function
    * does an escalated request actually interrupt rather than write
    * does the ledger it leaves behind verify, and does it record who
      approved each write"
  (:require [healthsvc.actor :as actor]
            [healthsvc.advisor :as advisor]
            [healthsvc.ledger :as led]
            [healthsvc.phase :as phase]
            [healthsvc.store :as store]))

(def registered-client
  {:client-id "sim-client-1" :name "Sakura Community Health"})

(def other-client
  {:client-id "sim-client-2" :name "Another Trust"})

(def registered-unit
  {:unit-id "U-1" :client-id "sim-client-1" :name "ward-3"
   :max-patients-per-staff 4
   :license-issued-day 100 :license-expiry-day 400})

(def ^:private incompletely-registered-unit
  "A unit someone registered without the ceiling the hard invariant is
  compared against. On the pre-change tree this made the governor throw."
  {:unit-id "U-noceiling" :client-id "sim-client-1" :name "annexe"})

(defn- tweaking-advisor
  "Wrap the mock advisor so a scenario can post-process the proposal it
  emits. Used only where the defect under test is a property of the proposal
  the mock cannot produce (an out-of-range confidence, a low one). The graph,
  governor and store under test are the real ones."
  [f]
  (reify advisor/Advisor
    (-advise [_ store request]
      (f (advisor/-advise (advisor/mock-advisor) store request)))))

(def scenarios
  "Each scenario names the phase it must reach. `:expect` is asserted, not
  merely printed — a scenario whose actual phase differs is a mismatch and
  fails the run, so this table is a specification and not a log.

  The nine scenarios marked (regression) reach `:hold` only because of the
  change that added this namespace. On the tree before it they reached
  `:commit`, escalated with an empty violation list, or threw."
  [{:name :clean-approve-staffing
    :request {:client-id "sim-client-1" :op :approve-staffing-plan :unit-id "U-1"
              :patient-count 12 :staff-count 4 :as-of-day 200}
    :expect :commit
    :why "declared op, registered unit, ratio 3 within ceiling 4, day inside licence"}

   {:name :clean-draft-plan
    :request {:client-id "sim-client-1" :op :draft-staffing-plan}
    :expect :commit
    :why "declared op that does not bind to a care unit"}

   {:name :unregistered-client
    :request {:client-id "nobody" :op :approve-staffing-plan :unit-id "U-1"
              :patient-count 12 :staff-count 4 :as-of-day 200}
    :expect :hold
    :why "provenance: the client was never registered"}

   {:name :request-without-client
    :request {:op :approve-staffing-plan :unit-id "U-1"
              :patient-count 12 :staff-count 4 :as-of-day 200}
    :expect :hold
    :why "provenance: the request names no client (regression)"}

   {:name :undeclared-operation
    :request {:client-id "sim-client-1" :op :discharge-all-patients :unit-id "U-1"
              :patient-count 999 :staff-count 1 :as-of-day 200}
    :expect :hold
    :why "vocabulary: op is not in healthsvc.operation/supported (regression)"}

   {:name :reserved-prescribe-treatment
    :request {:client-id "sim-client-1" :op :prescribe-treatment}
    :expect :hold
    :why "authority: a treatment order is the prescriber's (regression)"}

   {:name :reserved-discharge-patient
    :request {:client-id "sim-client-1" :op :discharge-patient}
    :expect :hold
    :why "authority: a discharge decision is the attending clinician's"}

   {:name :reserved-certify-death
    :request {:client-id "sim-client-1" :op :certify-death}
    :expect :hold
    :why "authority: certification of death is statutory and the clinician's"}

   {:name :reserved-alter-clinical-record
    :request {:client-id "sim-client-1" :op :alter-clinical-record}
    :expect :hold
    :why "authority: amending a clinical record belongs to its author"}

   {:name :ratio-exceeds-ceiling
    :request {:client-id "sim-client-1" :op :approve-staffing-plan :unit-id "U-1"
              :patient-count 20 :staff-count 4 :as-of-day 200}
    :expect :hold
    :why "patient-safety arithmetic: ratio 5 exceeds the registered ceiling 4"}

   {:name :unstaffed-unit
    :request {:client-id "sim-client-1" :op :approve-staffing-plan :unit-id "U-1"
              :patient-count 40 :staff-count 0 :as-of-day 200}
    :expect :hold
    :why "40 patients and nobody rostered — the unbounded case (regression)"}

   {:name :staffing-plan-without-counts
    :request {:client-id "sim-client-1" :op :approve-staffing-plan :unit-id "U-1"
              :as-of-day 200}
    :expect :hold
    :why "an absent census is not a reason to skip the ratio ceiling (regression)"}

   {:name :staffing-plan-without-day
    :request {:client-id "sim-client-1" :op :approve-staffing-plan :unit-id "U-1"
              :patient-count 12 :staff-count 4}
    :expect :hold
    :why "an absent day is not a reason to skip the licence window (regression)"}

   {:name :before-license-window
    :request {:client-id "sim-client-1" :op :approve-staffing-plan :unit-id "U-1"
              :patient-count 12 :staff-count 4 :as-of-day 50}
    :expect :hold
    :why "licence window: day 50 precedes the registered issue day 100"}

   {:name :after-license-window
    :request {:client-id "sim-client-1" :op :approve-staffing-plan :unit-id "U-1"
              :patient-count 12 :staff-count 4 :as-of-day 500}
    :expect :hold
    :why "licence window: day 500 follows the registered expiry day 400"}

   {:name :unknown-unit
    :request {:client-id "sim-client-1" :op :approve-staffing-plan :unit-id "U-ghost"
              :patient-count 12 :staff-count 4 :as-of-day 200}
    :expect :hold
    :why "unit basis: the unit was never registered"}

   {:name :foreign-unit
    :request {:client-id "sim-client-2" :op :approve-staffing-plan :unit-id "U-1"
              :patient-count 12 :staff-count 4 :as-of-day 200}
    :expect :hold
    :why "unit basis: the unit belongs to another client"}

   {:name :unit-registered-without-ceiling
    :request {:client-id "sim-client-1" :op :approve-staffing-plan :unit-id "U-noceiling"
              :patient-count 12 :staff-count 4 :as-of-day 200}
    :expect :hold
    :why "a unit with no registered ceiling is refused, not compared against nil (regression)"}

   {:name :surge-unregistered-unit
    :request {:client-id "sim-client-1" :op :approve-emergency-surge :unit-id "U-ghost"
              :patient-count 12 :staff-count 4 :as-of-day 200}
    :expect :hold
    :why "unit basis binds the op that waives the ratio (regression)"}

   {:name :surge-exceeds-ceiling
    :request {:client-id "sim-client-1" :op :approve-emergency-surge :unit-id "U-1"
              :patient-count 50 :staff-count 1 :as-of-day 200}
    :expect :hold
    :why "a surge is a waiver request, not a waiver — 50:1 still exceeds 4 (regression)"}

   {:name :surge-outside-license
    :request {:client-id "sim-client-1" :op :approve-emergency-surge :unit-id "U-1"
              :patient-count 12 :staff-count 4 :as-of-day 9999}
    :expect :hold
    :why "licence window binds the op that waives the ratio (regression)"}

   {:name :confidence-out-of-range
    :request {:client-id "sim-client-1" :op :approve-staffing-plan :unit-id "U-1"
              :patient-count 12 :staff-count 4 :as-of-day 200}
    :tweak #(assoc % :confidence 99.0)
    :expect :hold
    :why "a confidence outside [0,1] is unusable, not high (regression)"}

   {:name :clean-surge-escalates
    :request {:client-id "sim-client-1" :op :approve-emergency-surge :unit-id "U-1"
              :patient-count 12 :staff-count 4 :as-of-day 200}
    :expect :request-approval
    :why "a well-formed surge within the registered ratio still needs human sign-off"}

   {:name :flag-capacity-risk-escalates
    :request {:client-id "sim-client-1" :op :flag-capacity-risk}
    :expect :request-approval
    :why "the operation itself always requires human sign-off"}

   {:name :low-confidence-escalates
    :request {:client-id "sim-client-1" :op :approve-staffing-plan :unit-id "U-1"
              :patient-count 12 :staff-count 4 :as-of-day 200}
    :tweak #(assoc % :confidence 0.2)
    :expect :request-approval
    :why "confidence below the floor is a question for a human"}])

(defn- run-one [scenario]
  (let [st (store/mem-store)
        _ (store/register-client! st registered-client)
        _ (store/register-client! st other-client)
        _ (store/register-unit! st registered-unit)
        _ (store/register-unit! st incompletely-registered-unit)
        graph (actor/build-graph
               (cond-> {:store st}
                 (:tweak scenario) (assoc :advisor (tweaking-advisor (:tweak scenario)))))
        thread (str "sim-" (name (:name scenario)))
        result (actor/run-request! graph (:request scenario) {} thread)
        state (:state result)
        actual (or (:disposition state)
                   ;; A run that never reached :decide produced no phase at
                   ;; all; report that rather than defaulting it to a phase,
                   ;; which would make an unrun scenario look like a verdict.
                   :no-phase)]
    {:name (:name scenario)
     :expect (:expect scenario)
     :actual actual
     :why (:why scenario)
     :status (:status result)
     :match? (= actual (:expect scenario))
     :refusal? (and (not= actual :no-phase) (phase/refusal? actual))
     :wrote? (pos? (count (store/records-of st (:client-id (:request scenario)))))
     :ledger-verify (led/verify (store/ledger st))}))

(defn run
  "Run every scenario. Returns
  `{:results [..] :refusals n :mismatches [..] :ledger-breaks [..] :ok? bool}`.

  `:ok?` requires all four: every scenario reached its expected phase, no
  refusal wrote a record anyway, every ledger left behind verifies, and at
  least one refusal was demonstrated."
  []
  (let [results (mapv run-one scenarios)
        refusals (count (filter :refusal? results))
        mismatches (filterv (complement :match?) results)
        ;; A refusal that still wrote a record is the worst outcome available
        ;; and would otherwise hide inside a matching phase.
        wrote-anyway (filterv #(and (:refusal? %) (:wrote? %)) results)
        ledger-breaks (filterv #(not (:ok? (:ledger-verify %))) results)]
    {:results results
     :refusals refusals
     :mismatches mismatches
     :wrote-anyway wrote-anyway
     :ledger-breaks ledger-breaks
     :ok? (and (empty? mismatches)
               (empty? wrote-anyway)
               (empty? ledger-breaks)
               (pos? refusals))}))

(defn report
  "Human-readable run report. Pure: takes the result of `run`."
  [{:keys [results refusals mismatches wrote-anyway ledger-breaks ok?]}]
  (str
   "healthsvc.sim — governed scenario run\n"
   (apply str
          (for [r results]
            (str "  " (if (:match? r) "ok  " "BAD ")
                 (name (:name r))
                 " expect=" (name (:expect r))
                 " actual=" (name (:actual r))
                 (when (:refusal? r) " [refused]")
                 "\n")))
   "  scenarios=" (count results)
   " refusals=" refusals
   " mismatches=" (count mismatches)
   " wrote-anyway=" (count wrote-anyway)
   " ledger-breaks=" (count ledger-breaks)
   "\n"
   (cond
     (zero? refusals)
     "  REFUSING TO REPORT A PASS: the scenario table demonstrated no refusal.\n"
     ok? "  PASS\n"
     :else "  FAIL\n")))

#?(:clj
   (defn -main [& _]
     (let [r (run)]
       (print (report r))
       (flush)
       (System/exit (if (:ok? r) 0 1))))
   :cljs
   (defn -main [& _]
     (throw (ex-info "healthsvc.sim/-main is :clj-only (process exit codes are a host concern); call `run` and inspect the result instead" {}))))

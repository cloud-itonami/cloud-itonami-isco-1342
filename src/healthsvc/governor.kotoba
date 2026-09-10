(ns healthsvc.governor
  "HealthServicesManagersGovernor — the independent safety/traceability layer
  for the ISCO-08 1342 community health services managers actor (itonami actor
  pattern, ADR-2607011000 / CLAUDE.md Actors section). Care-unit twist: a
  proposed staffing plan's patient-per-staff ratio is arithmetic division
  checked against the registered ceiling, and the plan's as-of day must fall
  inside the unit's registered operating-licence window — patient safety
  arithmetic and licence validity are neither a judgement call nor negotiable.

  Runtime: portable `.cljc`.

  This namespace decides; it does not define the vocabulary it decides over,
  nor the well-formedness of the values it compares. Those live in
  `healthsvc.operation` and `healthsvc.facts`, each of which records the
  measurement that produced it. What changed here, and why:

  * The operation set is now an allowlist (`healthsvc.operation`). It used to
    be a denylist that bound `:approve-staffing-plan`, so any undeclared op
    committed.
  * Every unit-bound invariant is now gated on `operation/unit-op?` rather
    than on the single literal `:approve-staffing-plan`, which is what let
    `:approve-emergency-surge` — a ratio waiver — bypass the ratio, the
    licence window and the registered-unit basis at once.
  * A missing or zero head-count is a violation rather than a reason to skip
    the ratio, and a missing day is a violation rather than a reason to skip
    the window (`healthsvc.facts`).
  * The registered unit record is itself checked before it is compared
    against, so an incompletely registered unit is refused instead of
    throwing.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. vocabulary      — the op must be declared in `healthsvc.operation`.
    2. clinical authority — a reserved op is a clinician's, permanently.
    3. client provenance  — the request must name a client, and the
                         registered record must identify one.
    4. no-actuation    — proposal :effect must be :propose.
    5. envelope        — the proposal must be well-formed and its
                         :confidence, if present, usable.
    6. unit basis      — a unit-bound op must cite a REGISTERED unit
                         belonging to this client, and must supply the day
                         and the two head-counts.
    7. unit registration — the registered unit must carry a usable ceiling
                         and licence window.
    8. staffing-ratio ceiling — patient-count / staff-count must not exceed
                         the unit's registered :max-patients-per-staff.
                         Patients with zero staff is the unbounded case and
                         is named separately, because a ratio is undefined
                         there rather than merely large.
    9. licence window  — issued-day <= as-of-day <= expiry-day.
  ESCALATION invariants (:escalate? true, human sign-off):
   10. the operation declares :escalates? (e.g. :approve-emergency-surge).
   11. low confidence (< `confidence-floor`)."
  (:require [healthsvc.facts :as facts]
            [healthsvc.operation :as op]
            [healthsvc.store :as store]))

(def confidence-floor 0.6)

(defn- ratio-violations
  "The patient-safety arithmetic, evaluated only once `facts` has established
  that both head-counts and the registered ceiling are usable numbers. Split
  from the well-formedness checks so that `nil` never reaches a comparison."
  [{:keys [patient-count staff-count]} u]
  (cond
    ;; A ward with patients and nobody rostered has no finite ratio. Reporting
    ;; it as 'exceeds the ceiling' would understate it and invite a waiver;
    ;; it is an unstaffed unit, which is a different fact.
    (and (pos? patient-count) (zero? staff-count))
    [{:rule :unit-unstaffed
      :detail (str patient-count " 名の患者に対し職員 0 名 —— 比は定義されない"
                   "（上限超過ではなく無配置）")}]

    (and (pos? staff-count)
         (> (/ patient-count staff-count) (:max-patients-per-staff u)))
    [{:rule :ratio-exceeds-ceiling
      :detail (str "患者対職員比 " (double (/ patient-count staff-count))
                   " > 登録済み上限 " (:max-patients-per-staff u)
                   "（患者安全算術は判断ではない）")}]

    :else []))

(defn- license-violations
  [{:keys [as-of-day]} u]
  (if (or (< as-of-day (:license-issued-day u))
          (> as-of-day (:license-expiry-day u)))
    [{:rule :outside-license-window
      :detail (str "day " as-of-day " が運営免許窓 [" (:license-issued-day u) ", "
                   (:license-expiry-day u) "] の外（免許有効性は交渉不可）")}]
    []))

(defn- hard-violations
  [request proposal client-record u]
  (let [vocab    (facts/vocabulary-violations proposal)
        envelope (into (facts/request-violations request)
                       (facts/proposal-violations proposal))
        ;; Provenance asks whether the store's record identifies a client, not
        ;; merely whether the store returned something.
        provenance (if (nil? client-record)
                     [{:rule :no-client :detail "未登録 client"}]
                     (facts/client-record-violations client-record))
        actuation (when (not= :propose (:effect proposal))
                    [{:rule :no-actuation
                      :detail "effect は :propose のみ許可（直接書込禁止）"}])
        unit? (op/unit-op? (:op proposal))
        binding (facts/unit-binding-violations proposal)
        base (vec (concat vocab envelope provenance actuation binding))]
    (cond
      ;; A malformed or undeclared proposal is refused before anything is
      ;; compared against the store: the comparisons below are only meaningful
      ;; once the values they read are known usable.
      (seq base) base

      (not unit?) []

      (nil? u)
      [{:rule :unknown-unit :detail "未登録 unit への人員配置承認は不可"}]

      (not= (:client-id u) (:client-id request))
      [{:rule :unit-wrong-client :detail "unit が別 client のもの"}]

      :else
      (let [reg (facts/unit-record-violations u)]
        (if (seq reg)
          reg
          (vec (concat (ratio-violations proposal u)
                       (license-violations proposal u))))))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a `store`
  implementing `healthsvc.store/Store`. Pure — never mutates the store."
  [request context proposal store]
  (let [client-record (when-let [cid (:client-id request)]
                        (store/client store cid))
        u (when (string? (:unit-id proposal)) (store/unit store (:unit-id proposal)))
        hard (hard-violations request proposal client-record u)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (or (not (number? conf)) (< conf confidence-floor))
        risky-op? (op/escalates? (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))

(ns healthsvc.governor
  "HealthServicesManagersGovernor — the independent safety/
  traceability layer for the ISCO-08 1342 community health services
  managers actor (itonami actor pattern, ADR-2607011000 / CLAUDE.md
  Actors section). Modeled on cloud-itonami-isco-4311's
  bookkeeping.governor. Care-unit twist: a proposed staffing plan's
  patient-per-staff ratio is arithmetic division checked against the
  registered ceiling, and the plan's as-of day must fall inside the
  unit's registered operating-license window — patient safety
  arithmetic and license validity are neither a judgement call nor
  negotiable.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the organization must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. unit basis         — a staffing approval must cite a
                           REGISTERED unit belonging to this client.
    4. staffing-ratio ceiling — patient-count / staff-count must not
                           exceed the unit's registered
                           :max-patients-per-staff (arithmetic, not a
                           judgement call).
    5. license window     — the proposed as-of day must satisfy
                           license-issued-day <= as-of-day <=
                           license-expiry-day (interval containment
                           against the registered operating license).
  ESCALATION invariants (:escalate? true, human sign-off):
    6. :op :approve-emergency-surge (temporary ratio waiver request).
    7. low confidence (< `confidence-floor`)."
  (:require [healthsvc.store :as store]))

(def confidence-floor 0.6)

(defn- hard-violations [{:keys [request proposal]} client-record u]
  (let [{:keys [op patient-count staff-count as-of-day]} proposal
        staff? (= :approve-staffing-plan op)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and staff? (nil? u))
      (conj {:rule :unknown-unit :detail "未登録 unit への人員配置承認は不可"})

      (and staff? u (not= (:client-id u) (:client-id request)))
      (conj {:rule :unit-wrong-client :detail "unit が別 client のもの"})

      (and staff? u (number? patient-count) (number? staff-count) (pos? staff-count)
           (> (/ patient-count staff-count) (:max-patients-per-staff u)))
      (conj {:rule :ratio-exceeds-ceiling
             :detail (str "患者対職員比 " (double (/ patient-count staff-count))
                          " > 登録済み上限 " (:max-patients-per-staff u)
                          "（患者安全算術は判断ではない）")})

      (and staff? u (integer? as-of-day)
           (or (< as-of-day (:license-issued-day u)) (> as-of-day (:license-expiry-day u))))
      (conj {:rule :outside-license-window
             :detail (str "day " as-of-day " が運営免許窓 [" (:license-issued-day u) ", "
                          (:license-expiry-day u) "] の外（免許有効性は交渉不可）")}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `healthsvc.store/Store`. Pure — never mutates
  the store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        u (some->> (:unit-id proposal) (store/unit store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record u)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (= :approve-emergency-surge (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))

(ns healthsvc.facts
  "Well-formedness of the values the ISCO-08 1342 health services managers
  actor governs: the client record, the registered unit record, the request,
  and the proposal envelope.

  Runtime: portable `.cljc` (pure predicates, no host interop). Deliberately
  no `clojure.string` dependency — `blank?` is spelled out below so this
  namespace adds no coordinate to `deps.edn`.

  Why this namespace exists — four measurements on the pre-change tree.

  1. Client provenance was written as `(nil? client-record)`. That asks
     whether the store returned something, not whether that something
     identifies a client. Registering the empty map put a record under the key
     `nil`, after which a request carrying no `:client-id` resolved to it:

         (register-client! s {})
         (governor/check {} {} <clean staffing plan> s)
         => {:ok? true :violations []}

     `nil?` is a fact about the store's return value. Provenance is a fact
     about the record. Those are different questions, and the second one needs
     a place to live.

  2. The staffing-ratio ceiling was guarded by `(and (number? patient-count)
     (number? staff-count) (pos? staff-count))`, so the two worst inputs
     available skipped the comparison entirely:

         <approve staffing plan, 40 patients, 0 staff>   => {:ok? true}
         <approve staffing plan, 40 patients, no staff-count> => {:ok? true}

     A ward holding forty patients with nobody rostered is the arithmetic
     the ceiling exists to catch, and it was the one input that passed. A
     HARD invariant bypassed by a zero or a missing field is not a ceiling.

  3. The licence window was guarded by `(integer? as-of-day)`, so a proposal
     that simply omitted the day skipped the interval check:

         <approve staffing plan, no :as-of-day> => {:ok? true :violations []}

     For a unit-bound operation the day is required, and its absence is the
     violation rather than the reason not to check.

  4. The *registered* record was trusted as blindly as the proposal. A unit
     registered without a `:max-patients-per-staff` made the governor throw
     rather than refuse:

         (register-unit! s {:unit-id \"U1\" :client-id \"C1\"})
         (governor/check ...) => java.lang.NullPointerException

     A crash is not a refusal. It fails the request, but it fails it in a
     shape no caller can audit, and on the `:cljs` runtime the same comparison
     would silently answer `false` instead — admitting the plan. A registered
     record that cannot be compared against is a defect in the registration,
     and is reported as one.

  Separately, `:confidence` is compared against `confidence-floor` to decide
  escalation, but nothing constrained it, so `:confidence 99.0` passed as high
  confidence. A missing confidence already reads as 0.0 in the governor and
  thus escalates, which is correct and left alone; a *present but unusable*
  one is the defect.

  Every function here returns a vector of `{:rule .. :detail ..}` maps —
  empty means well-formed — so violations compose with the Governor's own
  rules without a second shape."
  (:require [healthsvc.operation :as op]))

(defn- blank?
  "True for nil, non-strings, and strings that are empty or all whitespace.
  Identifiers that are not strings are as unusable as absent ones."
  [v]
  (or (nil? v)
      (not (string? v))
      (every? #(contains? #{\space \tab \newline \return \formfeed} %) v)))

(defn- countable?
  "True for a value usable as a head-count: a non-negative integer. Fractional
  people are not a thing, and a negative census is not a smaller one."
  [v]
  (and (integer? v) (not (neg? v))))

(defn client-record-violations
  "A registered client must identify itself. A record without a usable
  `:client-id` cannot establish provenance for anything committed against it,
  so the governor must not treat its mere existence as provenance."
  [client-record]
  (cond-> []
    (not (map? client-record))
    (conj {:rule :client-not-a-record
           :detail "client record must be a map"})

    (and (map? client-record) (blank? (:client-id client-record)))
    (conj {:rule :client-without-id
           :detail "registered client record has no usable :client-id"})))

(defn request-violations
  "A request must name the client it is about."
  [request]
  (cond-> []
    (not (map? request))
    (conj {:rule :request-not-a-map :detail "request must be a map"})

    (and (map? request) (blank? (:client-id request)))
    (conj {:rule :request-without-client-id
           :detail "request has no usable :client-id"})))

(defn proposal-violations
  "The proposal envelope. Payload contents are the domain's business; the
  envelope is the governor's, because routing decisions are read off it."
  [proposal]
  (cond-> []
    (not (map? proposal))
    (conj {:rule :proposal-not-a-map :detail "proposal must be a map"})

    (and (map? proposal) (not (keyword? (:op proposal))))
    (conj {:rule :proposal-without-op :detail "proposal :op must be a keyword"})

    (and (map? proposal)
         (contains? proposal :confidence)
         (not (and (number? (:confidence proposal))
                   (<= 0 (:confidence proposal) 1))))
    (conj {:rule :confidence-out-of-range
           :detail "proposal :confidence must be a number in [0.0, 1.0]"})))

(defn unit-record-violations
  "The REGISTERED unit record must carry the facts the hard invariants are
  compared against. This checks the store's own data, not the proposal: a unit
  registered without a ceiling or without a coherent licence window cannot
  support a decision, and must be refused rather than compared against `nil`."
  [u]
  (cond-> []
    (not (map? u))
    (conj {:rule :unit-not-a-record :detail "unit record must be a map"})

    (and (map? u) (not (and (number? (:max-patients-per-staff u))
                            (pos? (:max-patients-per-staff u)))))
    (conj {:rule :unit-without-ceiling
           :detail "registered unit has no usable positive :max-patients-per-staff"})

    (and (map? u) (not (and (integer? (:license-issued-day u))
                            (integer? (:license-expiry-day u)))))
    (conj {:rule :unit-without-license-window
           :detail "registered unit has no usable integer licence window"})

    (and (map? u) (integer? (:license-issued-day u)) (integer? (:license-expiry-day u))
         (> (:license-issued-day u) (:license-expiry-day u)))
    (conj {:rule :unit-license-window-inverted
           :detail "registered unit licence window ends before it begins"})))

(defn unit-binding-violations
  "For an operation that binds to a registered care unit, the proposal must
  actually name the unit, the day it proposes to act on, and the two
  head-counts the ratio is computed from.

  These are required rather than optional because each hard invariant is a
  comparison against them: without a usable value there is no comparison to
  make, and the pre-change tree resolved that by not comparing — which
  admitted the proposal. Absence is the violation."
  [proposal]
  (let [o (:op proposal)]
    (if-not (op/unit-op? o)
      []
      (cond-> []
        (blank? (:unit-id proposal))
        (conj {:rule :unit-not-named
               :detail (str "operation " o " binds to a registered care unit but names none")})

        (not (integer? (:as-of-day proposal)))
        (conj {:rule :as-of-day-missing
               :detail (str "operation " o " needs an integer :as-of-day to compare against the "
                            "licence window; an absent day is not a reason to skip the window")})

        (not (countable? (:patient-count proposal)))
        (conj {:rule :patient-count-unusable
               :detail (str "operation " o " needs a non-negative integer :patient-count; "
                            "an absent census is not a reason to skip the ratio ceiling")})

        (not (countable? (:staff-count proposal)))
        (conj {:rule :staff-count-unusable
               :detail (str "operation " o " needs a non-negative integer :staff-count; "
                            "an absent roster is not a reason to skip the ratio ceiling")})))))

(defn vocabulary-violations
  "The operation must be one this repo declared. Undeclared and reserved are
  reported as different rules on purpose — see `healthsvc.operation`."
  [proposal]
  (let [o (:op proposal)]
    (cond
      (op/reserved? o)
      [{:rule :no-clinical-authority :detail (op/reserved-reason o)}]

      (and (keyword? o) (not (op/supported? o)))
      [{:rule :undeclared-operation
        :detail (str "operation " o " is not in healthsvc.operation/supported")}]

      :else [])))

(ns healthsvc.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [healthsvc.facts :as facts]))

(defn- rules [vs] (set (map :rule vs)))

(deftest a-well-formed-client-record-passes
  (is (empty? (facts/client-record-violations {:client-id "c1" :name "Clinic"}))))

(deftest a-client-record-must-identify-a-client
  (testing "provenance is a fact about the record, not about whether the store
  returned something"
    (is (contains? (rules (facts/client-record-violations {})) :client-without-id))
    (is (contains? (rules (facts/client-record-violations {:client-id "   "}))
                   :client-without-id))
    (is (contains? (rules (facts/client-record-violations {:client-id 7}))
                   :client-without-id))
    (is (contains? (rules (facts/client-record-violations "not-a-map"))
                   :client-not-a-record))))

(deftest a-request-must-name-its-client
  (is (empty? (facts/request-violations {:client-id "c1"})))
  (is (contains? (rules (facts/request-violations {})) :request-without-client-id))
  (is (contains? (rules (facts/request-violations nil)) :request-not-a-map)))

(deftest the-proposal-envelope-is-checked
  (is (empty? (facts/proposal-violations {:op :approve-staffing-plan :confidence 0.9})))
  (is (contains? (rules (facts/proposal-violations {:op "staffing"})) :proposal-without-op))
  (is (contains? (rules (facts/proposal-violations nil)) :proposal-not-a-map)))

(deftest an-out-of-range-confidence-is-unusable-not-high
  (testing "regression: :confidence 99.0 was compared against the floor and
  passed as high confidence"
    (is (contains? (rules (facts/proposal-violations {:op :x :confidence 99.0}))
                   :confidence-out-of-range))
    (is (contains? (rules (facts/proposal-violations {:op :x :confidence -1}))
                   :confidence-out-of-range))
    (is (contains? (rules (facts/proposal-violations {:op :x :confidence "high"}))
                   :confidence-out-of-range))))

(deftest an-absent-confidence-is-left-alone
  (testing "a missing confidence already reads as 0.0 in the governor and thus
  escalates, which is correct; only a present-but-unusable one is the defect"
    (is (empty? (facts/proposal-violations {:op :approve-staffing-plan})))))

(deftest confidence-bounds-are-inclusive
  (is (empty? (facts/proposal-violations {:op :x :confidence 0})))
  (is (empty? (facts/proposal-violations {:op :x :confidence 1}))))

(deftest a-registered-unit-must-carry-what-it-is-compared-against
  (testing "regression: a unit registered without a ceiling made the governor
  throw rather than refuse"
    (let [bare {:unit-id "U-1" :client-id "c1"}]
      (is (contains? (rules (facts/unit-record-violations bare)) :unit-without-ceiling))
      (is (contains? (rules (facts/unit-record-violations bare))
                     :unit-without-license-window))))
  (is (empty? (facts/unit-record-violations
               {:unit-id "U-1" :max-patients-per-staff 4
                :license-issued-day 100 :license-expiry-day 400}))))

(deftest a-ceiling-must-be-positive
  (testing "a ceiling of zero would refuse every staffed plan and admit only
  the unstaffed one; it is a registration error, not a strict policy"
    (is (contains? (rules (facts/unit-record-violations
                           {:max-patients-per-staff 0
                            :license-issued-day 100 :license-expiry-day 400}))
                   :unit-without-ceiling))))

(deftest an-inverted-license-window-is-refused
  (is (contains? (rules (facts/unit-record-violations
                         {:max-patients-per-staff 4
                          :license-issued-day 400 :license-expiry-day 100}))
                 :unit-license-window-inverted)))

(deftest unit-bound-ops-must-supply-the-values-the-invariants-compare
  (testing "regression: absence was treated as a reason not to compare, which
  admitted the proposal"
    (let [r (rules (facts/unit-binding-violations {:op :approve-staffing-plan}))]
      (is (contains? r :unit-not-named))
      (is (contains? r :as-of-day-missing))
      (is (contains? r :patient-count-unusable))
      (is (contains? r :staff-count-unusable)))))

(deftest a-surge-is-bound-by-the-same-requirements
  (testing "the op whose purpose is to waive the ratio is a unit-bound op"
    (let [r (rules (facts/unit-binding-violations {:op :approve-emergency-surge}))]
      (is (contains? r :unit-not-named))
      (is (contains? r :as-of-day-missing))
      (is (contains? r :patient-count-unusable)))))

(deftest a-well-formed-unit-binding-passes
  (is (empty? (facts/unit-binding-violations
               {:op :approve-staffing-plan :unit-id "U-1"
                :patient-count 12 :staff-count 4 :as-of-day 200}))))

(deftest zero-is-a-usable-head-count-and-reaches-the-governor
  (testing "zero staff is well-formed but not admissible; the ARITHMETIC
  invariant in the governor rejects it, not the well-formedness check. Keeping
  these separate is what lets the refusal say 'unstaffed' rather than
  'malformed'."
    (is (empty? (facts/unit-binding-violations
                 {:op :approve-staffing-plan :unit-id "U-1"
                  :patient-count 40 :staff-count 0 :as-of-day 200})))))

(deftest head-counts-must-be-non-negative-integers
  (let [base {:op :approve-staffing-plan :unit-id "U-1" :as-of-day 200 :staff-count 4}]
    (is (contains? (rules (facts/unit-binding-violations (assoc base :patient-count -1)))
                   :patient-count-unusable))
    (is (contains? (rules (facts/unit-binding-violations (assoc base :patient-count 1.5)))
                   :patient-count-unusable))
    (is (contains? (rules (facts/unit-binding-violations (assoc base :patient-count "12")))
                   :patient-count-unusable))))

(deftest ops-that-bind-nothing-need-no-unit
  (is (empty? (facts/unit-binding-violations {:op :draft-staffing-plan})))
  (is (empty? (facts/unit-binding-violations {:op :flag-capacity-risk}))))

(deftest undeclared-and-reserved-are-different-rules
  (testing "conflating them would let a future edit supported-list a clinical
  operation by accident"
    (is (contains? (rules (facts/vocabulary-violations {:op :discharge-all-patients}))
                   :undeclared-operation))
    (is (contains? (rules (facts/vocabulary-violations {:op :prescribe-treatment}))
                   :no-clinical-authority))
    (is (empty? (facts/vocabulary-violations {:op :approve-staffing-plan})))))

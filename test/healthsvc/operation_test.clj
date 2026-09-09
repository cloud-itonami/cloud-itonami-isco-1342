(ns healthsvc.operation-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [healthsvc.operation :as op]))

(deftest supported-and-reserved-are-disjoint
  (testing "an op that is both supported and reserved would make the refusal
  depend on check order"
    (is (empty? (set/intersection
                 (set (keys op/supported)) (set (keys op/reserved)))))))

(deftest the-vocabulary-is-closed
  (testing "an undeclared op is neither supported nor reserved -- this is the
  property the governor relies on to refuse it"
    (is (not (op/declared? :discharge-all-patients)))
    (is (not (op/supported? :discharge-all-patients)))
    (is (not (op/reserved? :discharge-all-patients)))))

(deftest every-supported-op-declares-both-properties
  (testing "a missing :unit-op? would read as false and silently exempt the op
  from every unit-bound invariant -- exactly the defect this namespace closes"
    (doseq [[o m] op/supported]
      (is (contains? m :escalates?) (str o " must declare :escalates?"))
      (is (contains? m :unit-op?) (str o " must declare :unit-op?"))
      (is (boolean? (:escalates? m)))
      (is (boolean? (:unit-op? m))))))

(deftest emergency-surge-is-unit-bound
  (testing "the regression this repo shipped with: the op whose purpose is to
  waive a staffing ratio must still be bound by the registered ratio, the
  licence window and the registered-unit basis"
    (is (op/unit-op? :approve-emergency-surge))
    (is (op/escalates? :approve-emergency-surge))))

(deftest approving-a-staffing-plan-is-unit-bound
  (is (op/unit-op? :approve-staffing-plan))
  (is (not (op/escalates? :approve-staffing-plan))))

(deftest drafting-binds-nothing
  (testing "a draft is not an approval, so it does not bind to a registered unit"
    (is (not (op/unit-op? :draft-staffing-plan)))))

(deftest reserved-ops-carry-a-reason
  (testing "the refusal has to be able to say why the authority is not the
  actor's; a bare absence could not"
    (doseq [o (keys op/reserved)]
      (is (string? (op/reserved-reason o)))
      (is (seq (op/reserved-reason o))))))

(deftest undeclared-ops-are-not-silently-escalated-or-bound
  (testing "these predicates must answer false rather than throw for an op the
  governor is about to hard-block"
    (is (not (op/escalates? :discharge-all-patients)))
    (is (not (op/unit-op? :discharge-all-patients)))
    (is (nil? (op/reserved-reason :discharge-all-patients)))))

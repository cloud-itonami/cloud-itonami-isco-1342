(ns healthsvc.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [healthsvc.phase :as phase]))

(deftest hard-outranks-escalation
  (testing "a proposal that is both hard-blocked and low-confidence must hold.
  Escalating it would put a question to a human they have no authority to
  answer yes to -- and for this actor the two conditions coincide on exactly
  the request most likely to be waved through, an emergency surge."
    (is (= :hold (phase/of-verdict {:hard? true :escalate? true})))
    (is (= :hold (phase/of-verdict {:hard? true})))))

(deftest escalation-routes-to-approval
  (is (= :request-approval (phase/of-verdict {:hard? false :escalate? true}))))

(deftest clean-routes-to-commit
  (is (= :commit (phase/of-verdict {:hard? false :escalate? false}))))

(deftest only-commit-writes
  (testing "the safety claim is that two of the three phases cannot write"
    (is (phase/writes? :commit))
    (is (not (phase/writes? :hold)))
    (is (not (phase/writes? :request-approval)))))

(deftest both-non-writing-phases-are-refusals
  (testing ":request-approval is a refusal to act without a human, not an
  approval-in-waiting; healthsvc.sim counts it as one"
    (is (phase/refusal? :hold))
    (is (phase/refusal? :request-approval))
    (is (not (phase/refusal? :commit)))))

(deftest approval-provenance-is-read-off-the-disposition
  (is (phase/approved-commit? :request-approval))
  (is (not (phase/approved-commit? :commit)))
  (is (not (phase/approved-commit? :hold))))

(deftest only-approval-requires-a-human
  (is (phase/human-required? :request-approval))
  (is (not (phase/human-required? :commit)))
  (is (not (phase/human-required? :hold))))

(deftest every-phase-of-verdict-returns-is-declared
  (testing "a phase the map does not describe would answer false to writes?
  and thus read as a refusal that nevertheless routed somewhere"
    (doseq [v [{:hard? true} {:escalate? true} {}]]
      (is (contains? phase/phases (phase/of-verdict v))))))

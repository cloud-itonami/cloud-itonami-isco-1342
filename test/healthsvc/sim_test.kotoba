(ns healthsvc.sim-test
  (:require [clojure.test :refer [deftest is testing]]
            [healthsvc.phase :as phase]
            [healthsvc.sim :as sim]))

(deftest the-scenario-table-passes
  (let [r (sim/run)]
    (is (empty? (:mismatches r))
        (str "scenarios reached the wrong phase: "
             (pr-str (mapv (juxt :name :expect :actual) (:mismatches r)))))
    (is (empty? (:wrote-anyway r))
        (str "a refusal wrote a record anyway: "
             (pr-str (mapv :name (:wrote-anyway r)))))
    (is (empty? (:ledger-breaks r)))
    (is (:ok? r))))

(deftest the-run-demonstrates-refusals
  (testing "a governed actor's claim is that there exist actions it refuses; a
  table that stopped exercising the governor is a defect in the table"
    (is (pos? (:refusals (sim/run))))))

(deftest the-table-covers-both-directions
  (testing "a table of only refusals would pass a governor that refuses
  everything, which is as useless as one that refuses nothing"
    (let [phases (set (map :expect sim/scenarios))]
      (is (contains? phases :commit))
      (is (contains? phases :hold))
      (is (contains? phases :request-approval)))))

(deftest every-scenario-declares-an-expected-phase-and-a-reason
  (doseq [s sim/scenarios]
    (is (contains? phase/phases (:expect s)) (str (:name s) " expects an unknown phase"))
    (is (string? (:why s)) (str (:name s) " has no stated reason"))
    (is (seq (:why s)))))

(deftest scenario-names-are-unique
  (testing "duplicate names share a checkpoint thread id and would silently
  resume each other's run"
    (is (= (count sim/scenarios) (count (set (map :name sim/scenarios)))))))

(deftest zero-refusals-refuses-to-report-a-pass
  (testing "the report must not print PASS for a table that demonstrated
  nothing -- this is the gate, so it is pinned rather than left to inspection"
    (let [empty-run {:results [] :refusals 0 :mismatches [] :wrote-anyway []
                     :ledger-breaks [] :ok? false}
          out (sim/report empty-run)]
      (is (re-find #"REFUSING TO REPORT A PASS" out))
      (is (not (re-find #"PASS\n$" out))))))

(deftest a-mismatch-is-reported-as-a-failure
  (let [out (sim/report {:results [{:name :x :expect :commit :actual :hold
                                    :match? false :refusal? true}]
                         :refusals 1 :mismatches [{:name :x}] :wrote-anyway []
                         :ledger-breaks [] :ok? false})]
    (is (re-find #"BAD x" out))
    (is (re-find #"FAIL" out))))

(ns healthsvc.ledger-test
  (:require [clojure.test :refer [deftest is testing]]
            [healthsvc.ledger :as led]))

(deftest an-intact-chain-verifies
  (let [l (-> [] (led/append {:disposition :commit}) (led/append {:disposition :hold}))]
    (is (:ok? (led/verify l)))
    (is (= 2 (:length (led/verify l))))))

(deftest an-empty-ledger-verifies
  (is (:ok? (led/verify []))))

(deftest a-mutated-entry-breaks-the-chain
  (testing "the point of the hash: editing a committed record in place is
  detectable, which a bare vector could not do"
    (let [l (-> [] (led/append {:disposition :commit :record {:patient-count 12}})
                (led/append {:disposition :commit :record {:patient-count 12}}))
          tampered (assoc-in l [0 :record :patient-count] 999)
          v (led/verify tampered)]
      (is (not (:ok? v)))
      (is (= 0 (:broken-at v)))
      (is (= :hash-mismatch (:reason v))))))

(deftest a-reordered-ledger-breaks-the-chain
  (let [l (-> [] (led/append {:disposition :commit}) (led/append {:disposition :hold}))
        v (led/verify (vec (reverse l)))]
    (is (not (:ok? v)))
    (is (= :seq-mismatch (:reason v)))))

(deftest a-dropped-middle-entry-breaks-the-chain
  (let [l (-> [] (led/append {:n 0}) (led/append {:n 1}) (led/append {:n 2}))
        v (led/verify [(nth l 0) (nth l 2)])]
    (is (not (:ok? v)))
    (is (= 1 (:broken-at v)))))

(deftest truncation-is-a-stated-limit-not-a-silent-pass
  (testing "a chain cannot detect entries it never saw. The docstring claims
  only what verify can show; this test pins that claim so a future reader does
  not mistake it for tamper-evidence against truncation."
    (let [l (-> [] (led/append {:n 0}) (led/append {:n 1}) (led/append {:n 2}))]
      (is (:ok? (led/verify (subvec l 0 2))))
      (is (= 2 (:length (led/verify (subvec l 0 2))))))))

(deftest the-hash-is-deterministic
  (testing "the same content must hash the same way on every run and every
  runtime, or verification is not portable"
    (is (= (led/chain-hash 0 {:a 1 :b "x"})
           (led/chain-hash 0 {:a 1 :b "x"})))
    (is (not= (led/chain-hash 0 {:a 1}) (led/chain-hash 1 {:a 1})))
    (is (not= (led/chain-hash 0 {:a 1}) (led/chain-hash 0 {:a 2})))))

(deftest the-hash-stays-in-the-exactly-representable-range
  (testing "the portability claim: intermediate values must stay inside the
  range a double represents exactly, so Clojure and ClojureScript agree"
    (let [content (apply str (repeat 500 "patient-safety-arithmetic"))]
      (doseq [prev [0 2147483646]]
        (let [h (led/chain-hash prev content)]
          (is (integer? h))
          (is (<= 0 h 2147483646)))))))

(deftest commit-entries-record-who-approved
  (testing "recording :actor explicitly keeps both cases the same shape, so an
  absent field cannot be mistaken for an unaudited one"
    (is (= :human (:approved-by (led/commit-entry {:x 1} :human))))
    (is (= :actor (:approved-by (led/commit-entry {:x 1} :actor))))
    (is (= :none (:approved-by (led/hold-entry {:hard? true}))))))

(deftest hold-entries-carry-the-violations
  (testing "the ledger must explain a refusal without needing the run that
  produced it"
    (let [v {:hard? true :violations [{:rule :ratio-exceeds-ceiling}]}]
      (is (= v (:verdict (led/hold-entry v)))))))

(deftest append-is-the-only-way-the-chain-extends
  (testing "an entry conj'd on directly does not verify"
    (let [l (led/append [] {:n 0})
          smuggled (conj l {:disposition :commit})]
      (is (not (:ok? (led/verify smuggled)))))))

(ns healthsvc.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [healthsvc.store :as store]
            [healthsvc.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-unit! st {:unit-id "U-1" :client-id "client-1"
                              :name "ward-3"
                              :max-patients-per-staff 4
                              :license-issued-day 100 :license-expiry-day 400})
    st))

(defn- staff [patients staffcount day]
  {:op :approve-staffing-plan :effect :propose :unit-id "U-1"
   :patient-count patients :staff-count staffcount :as-of-day day
   :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-within-ratio-and-license-window
  (let [st (fresh-store)
        v (governor/check req {} (staff 12 4 200) st)]
    (is (:ok? v))))

(deftest ok-at-exact-ratio-and-window-edges
  (testing "the ratio ceiling and license window boundaries are inclusive"
    (let [st (fresh-store)]
      (is (:ok? (governor/check req {} (staff 16 4 100) st)))
      (is (:ok? (governor/check req {} (staff 16 4 400) st))))))

(deftest hard-on-ratio-exceeds-ceiling
  (testing "patient-safety arithmetic is not a judgement call"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (staff 20 4 200) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :ratio-exceeds-ceiling (:rule %)) (:violations v))))))

(deftest hard-on-before-license-window
  (testing "license validity is not negotiable"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (staff 12 4 50) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :outside-license-window (:rule %)) (:violations v))))))

(deftest hard-on-after-license-window
  (let [st (fresh-store)
        v (governor/check req {} (assoc (staff 12 4 500) :confidence 0.99) st)]
    (is (:hard? v))
    (is (some #(= :outside-license-window (:rule %)) (:violations v)))))

(deftest hard-on-unknown-unit
  (let [st (fresh-store)
        v (governor/check req {} (assoc (staff 12 4 200) :unit-id "U-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-unit (:rule %)) (:violations v)))))

(deftest hard-on-foreign-unit
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (let [v (governor/check {:client-id "client-2"} {} (staff 12 4 200) st)]
      (is (:hard? v))
      (is (some #(= :unit-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (staff 12 4 200) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (staff 12 4 200) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest escalates-emergency-surge
  (let [st (fresh-store)
        v (governor/check req {} {:op :approve-emergency-surge :effect :propose
                                  :unit-id "U-1" :confidence 0.9 :stake :high} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (staff 12 4 200) :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

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
  (testing "a well-formed surge escalates rather than committing. The fixture
  carries the unit binding data on purpose -- this assertion used to be made
  against a surge that supplied none of it, and passed only because the surge
  was exempt from every unit-bound invariant."
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-emergency-surge :effect :propose
                                    :unit-id "U-1" :patient-count 12 :staff-count 4
                                    :as-of-day 200 :confidence 0.9 :stake :high} st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest hard-on-surge-that-breaches-the-ceiling
  (testing "regression: :approve-emergency-surge is a waiver REQUEST, not a
  waiver. It was gated out of the ratio check by `(= :approve-staffing-plan op)`,
  so a 50:1 surge against a registered ceiling of 4 escalated with an empty
  violation list."
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-emergency-surge :effect :propose
                                    :unit-id "U-1" :patient-count 50 :staff-count 1
                                    :as-of-day 200 :confidence 0.9 :stake :high} st)]
      (is (:hard? v))
      (is (some #(= :ratio-exceeds-ceiling (:rule %)) (:violations v))))))

(deftest hard-on-surge-outside-the-license-window
  (let [st (fresh-store)
        v (governor/check req {} {:op :approve-emergency-surge :effect :propose
                                  :unit-id "U-1" :patient-count 12 :staff-count 4
                                  :as-of-day 9999 :confidence 0.9 :stake :high} st)]
    (is (:hard? v))
    (is (some #(= :outside-license-window (:rule %)) (:violations v)))))

(deftest hard-on-surge-for-an-unregistered-unit
  (let [st (fresh-store)
        v (governor/check req {} {:op :approve-emergency-surge :effect :propose
                                  :unit-id "U-ghost" :patient-count 12 :staff-count 4
                                  :as-of-day 200 :confidence 0.9 :stake :high} st)]
    (is (:hard? v))
    (is (some #(= :unknown-unit (:rule %)) (:violations v)))))

(deftest hard-on-undeclared-operation
  (testing "regression: the governor bound one op by name and admitted every
  other. Measured on the pre-change tree, :discharge-all-patients with 999
  patients to 1 staff returned {:ok? true :violations []}."
    (let [st (fresh-store)
          v (governor/check req {} {:op :discharge-all-patients :effect :propose
                                    :unit-id "U-1" :patient-count 999 :staff-count 1
                                    :as-of-day 200 :confidence 0.95} st)]
      (is (:hard? v))
      (is (some #(= :undeclared-operation (:rule %)) (:violations v))))))

(deftest hard-on-reserved-clinical-operation
  (testing "a reserved op is a permanent block, never an escalation: no human
  can delegate a clinical decision to a service-management actor."
    (let [st (fresh-store)
          v (governor/check req {} {:op :prescribe-treatment :effect :propose
                                    :confidence 0.95} st)]
      (is (:hard? v))
      (is (not (:escalate? v)))
      (is (some #(= :no-clinical-authority (:rule %)) (:violations v))))))

(deftest hard-on-unstaffed-unit
  (testing "regression: `(pos? staff-count)` guarded the ratio comparison, so
  40 patients with nobody rostered -- the unbounded case -- was the one input
  that passed."
    (let [st (fresh-store)
          v (governor/check req {} (staff 40 0 200) st)]
      (is (:hard? v))
      (is (some #(= :unit-unstaffed (:rule %)) (:violations v))))))

(deftest hard-on-absent-head-counts
  (testing "regression: an absent census skipped the ratio ceiling entirely."
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-staffing-plan :effect :propose
                                    :unit-id "U-1" :as-of-day 200 :confidence 0.9} st)]
      (is (:hard? v))
      (is (some #(= :patient-count-unusable (:rule %)) (:violations v)))
      (is (some #(= :staff-count-unusable (:rule %)) (:violations v))))))

(deftest hard-on-absent-as-of-day
  (testing "regression: `(integer? as-of-day)` guarded the licence window, so
  omitting the day skipped the interval check."
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-staffing-plan :effect :propose
                                    :unit-id "U-1" :patient-count 12 :staff-count 4
                                    :confidence 0.9} st)]
      (is (:hard? v))
      (is (some #(= :as-of-day-missing (:rule %)) (:violations v))))))

(deftest hard-on-unit-registered-without-a-ceiling
  (testing "regression: comparing against an absent ceiling threw a
  NullPointerException on the JVM -- a crash is not a refusal, and the same
  comparison answers false (admitting the plan) under ClojureScript."
    (let [st (fresh-store)]
      (store/register-unit! st {:unit-id "U-bare" :client-id "client-1" :name "annexe"})
      (let [v (governor/check req {} (assoc (staff 12 4 200) :unit-id "U-bare") st)]
        (is (:hard? v))
        (is (some #(= :unit-without-ceiling (:rule %)) (:violations v)))))))

(deftest hard-on-out-of-range-confidence
  (testing "regression: :confidence 99.0 was compared against the floor and
  passed as high confidence."
    (let [st (fresh-store)
          v (governor/check req {} (assoc (staff 12 4 200) :confidence 99.0) st)]
      (is (:hard? v))
      (is (some #(= :confidence-out-of-range (:rule %)) (:violations v))))))

(deftest hard-on-request-without-a-client-id
  (testing "regression: provenance was `(nil? client-record)`, a fact about the
  store's return value rather than about the record. Registering the empty map
  put a record under the key nil, after which a request naming no client
  resolved to it and committed."
    (let [st (store/mem-store)]
      (store/register-client! st {})
      (store/register-unit! st {:unit-id "U-1" :client-id nil :name "ward-3"
                                :max-patients-per-staff 4
                                :license-issued-day 100 :license-expiry-day 400})
      (let [v (governor/check {} {} (staff 12 4 200) st)
            rules (set (map :rule (:violations v)))]
        (is (:hard? v))
        (is (contains? rules :request-without-client-id))
        ;; Pin the REASON, not just the refusal. The verdict is hard either
        ;; way -- the envelope check above fires on its own -- so asserting
        ;; only :hard? counts a run that refused for an unrelated reason as a
        ;; demonstration. :no-client is the store having found nothing;
        ;; :client-without-id would mean the lookup resolved to the phantom
        ;; record filed under the nil key, which is the defect itself.
        (is (contains? rules :no-client)
            "a request naming no client must not resolve to any registered record")
        (is (not (contains? rules :client-without-id))
            "reporting :client-without-id means the nil-key record was consulted")))))

(deftest confidence-floor-is-exclusive-at-the-boundary
  (testing "the floor is `< confidence-floor`, so a proposal sitting exactly on
  it is admitted. Pinned because a comparison with no input on the line cannot
  tell < from <=: flipping the operator would leave every other assertion here
  green."
    (let [st (fresh-store)]
      (is (not (:escalate? (governor/check req {} (assoc (staff 12 4 200)
                                                         :confidence governor/confidence-floor)
                                           st))))
      (is (:escalate? (governor/check req {} (assoc (staff 12 4 200)
                                                    :confidence 0.5999)
                                      st))))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (staff 12 4 200) :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

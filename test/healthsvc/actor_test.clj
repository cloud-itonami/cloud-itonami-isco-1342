(ns healthsvc.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [healthsvc.actor :as actor]
            [healthsvc.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-unit! st {:unit-id "U-1" :client-id "client-1"
                              :name "ward-3"
                              :max-patients-per-staff 4
                              :license-issued-day 100 :license-expiry-day 400})
    st))

(deftest commits-an-in-ratio-in-window-plan
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-staffing-plan :stake :low
                 :unit-id "U-1" :patient-count 12 :staff-count 4 :as-of-day 200}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-an-over-ratio-plan
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-staffing-plan :stake :low
                 :unit-id "U-1" :patient-count 30 :staff-count 4 :as-of-day 200}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-approves-surge-on-human-approval
  (testing "a WELL-FORMED surge interrupts, and a human resume commits it.
  The fixture carries the unit binding data on purpose: this test used to pass
  a surge with no :patient-count, :staff-count or :as-of-day, which reached
  human sign-off only because the surge was exempt from every unit-bound
  invariant. See surge-without-unit-binding-now-holds below."
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})
          request {:client-id "client-1" :op :approve-emergency-surge :stake :high
                   :unit-id "U-1" :patient-count 12 :staff-count 4 :as-of-day 200}
          interrupted (actor/run-request! graph request {} "thread-3")]
      (is (= :interrupted (:status interrupted)))
      (is (empty? (store/records-of st "client-1")))
      (let [resumed (actor/approve! graph "thread-3")]
        (is (= :done (:status resumed)))
        (is (= 1 (count (store/records-of st "client-1"))))))))

(deftest surge-without-unit-binding-now-holds
  (testing "regression: a surge naming a unit but supplying none of the values
  the ratio and licence checks compare against is refused, not escalated.
  Measured on the pre-change tree this reached :request-approval with an empty
  violation list and committed on resume."
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})
          request {:client-id "client-1" :op :approve-emergency-surge :stake :high
                   :unit-id "U-1"}
          result (actor/run-request! graph request {} "thread-4")]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/records-of st "client-1"))))))

(deftest surge-naming-a-ghost-unit-holds
  (testing "regression: the op whose purpose is to waive a ratio was exempt
  from the registered-unit basis. A surge for a unit that was never registered,
  at 50 patients to 1 staff, on a day outside the licence, committed after a
  human resumed a thread that showed them no violations at all."
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})
          request {:client-id "client-1" :op :approve-emergency-surge :stake :high
                   :unit-id "U-ghost" :patient-count 50 :staff-count 1 :as-of-day 9999}
          result (actor/run-request! graph request {} "thread-5")]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/records-of st "client-1"))))))

(deftest ledger-records-who-approved-each-write
  (testing "an automatic commit and a human-approved one used to leave
  byte-identical ledger entries; the interrupt exists precisely to tell them
  apart."
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})]
      (actor/run-request! graph {:client-id "client-1" :op :approve-staffing-plan
                                 :stake :low :unit-id "U-1" :patient-count 12
                                 :staff-count 4 :as-of-day 200} {} "auto")
      (is (= :actor (:approved-by (last (store/ledger st)))))
      (actor/run-request! graph {:client-id "client-1" :op :approve-emergency-surge
                                 :stake :high :unit-id "U-1" :patient-count 12
                                 :staff-count 4 :as-of-day 200} {} "human")
      (actor/approve! graph "human")
      (is (= :human (:approved-by (last (store/ledger st))))))))

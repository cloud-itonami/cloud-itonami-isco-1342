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
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-emergency-surge :stake :high
                 :unit-id "U-1"}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))

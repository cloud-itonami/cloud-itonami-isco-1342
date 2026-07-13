(ns healthsvc.store
  "SSoT for the ISCO-08 1342 community health services managers actor
  (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors section).
  Modeled on cloud-itonami-isco-4311's bookkeeping.store.

  Domain:

    client — a registered organization (:client-id, :name)
    unit   — a registered care unit {:unit-id :client-id :name
             :max-patients-per-staff number :license-issued-day int
             :license-expiry-day int}. `:max-patients-per-staff` is
             the registered ceiling a proposed staffing ratio must not
             exceed; `:license-issued-day`/`:license-expiry-day` is
             the registered operating-license window (simple
             monotonic day clock, day 0 = epoch for this unit) a
             proposed as-of day must fall inside.
    record — a committed operating record (approved staffing plan) —
             written ONLY via commit-record!.
    ledger — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (unit [s unit-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-unit! [s u])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (unit [_ unit-id] (get-in @a [:units unit-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-unit! [s u]
    (swap! a assoc-in [:units (:unit-id u)] u) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :units {} :records [] :ledger []}
                                   seed)))))

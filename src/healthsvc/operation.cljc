(ns healthsvc.operation
  "The closed vocabulary of operations the ISCO-08 1342 health services
  managers actor may propose.

  Runtime: portable `.cljc` (pure data + pure predicates, no host interop).

  Why this namespace exists. Before it, the operation vocabulary lived in two
  places that could not disagree loudly: the README's prose list, and the
  Governor's private `(= :approve-staffing-plan op)` test plus a single named
  escalating op. That made the Governor a *denylist* — it bound one named op
  and admitted everything else. Measured on the pre-change tree, against a
  registered client:

      {:op :discharge-all-patients :unit-id \"U1\"
       :patient-count 999 :staff-count 1 :as-of-day 200}
      => {:ok? true :violations []}

  An actor whose operation set is open cannot be governed, because the
  governor is answering a question about a vocabulary nobody declared. So the
  vocabulary is declared here, once, as an allowlist, and
  `healthsvc.governor` refuses anything outside it.

  Two disjoint maps:

  * `supported` — what the actor may propose. `:escalates?` and `:unit-op?`
    are properties of the operation, not of the governor's mood, so they live
    beside it.
  * `reserved` — operations that name authority belonging exclusively to a
    licensed clinician. These are *declared* rather than merely absent so the
    refusal can say why: an undeclared op is a vocabulary error, a reserved op
    is a clinical-authority boundary. Conflating them would let a future edit
    `supported`-list one of them by accident.

  `:unit-op?` is the field that closes the gap this repo shipped with. The
  staffing-ratio ceiling, the license window and the registered-unit basis
  were all gated on `(= :approve-staffing-plan op)`, so
  `:approve-emergency-surge` — the operation whose entire purpose is to waive
  a staffing ratio — was exempt from all three. Measured on the pre-change
  tree, a surge naming an unregistered unit, at a 50:1 ratio against a
  registered ceiling of 4, on a day outside the operating licence, escalated
  with an **empty violation list** and committed once a human resumed the
  thread. The human signing it off was shown nothing to weigh.

  Binding is a property of the operation, so it is declared here and the
  governor reads it, rather than the governor naming one op and forgetting
  the more dangerous one."
  )

(def supported
  "Operations the actor may propose.

  `:escalates?` true means human sign-off is required regardless of advisor
  confidence. `:unit-op?` true means the proposal binds to a REGISTERED care
  unit, and therefore must satisfy every registered fact about it — ownership,
  the staffing-ratio ceiling, and the operating-licence window."
  {:draft-staffing-plan
   {:escalates? false
    :unit-op? false
    :summary "draft a staffing plan for the clinical lead's review (binds nothing)"}

   :approve-staffing-plan
   {:escalates? false
    :unit-op? true
    :summary "approve a staffing plan for a registered care unit"}

   :approve-emergency-surge
   {:escalates? true
    :unit-op? true
    :summary "approve a temporary staffing-ratio waiver for a registered care unit"}

   :flag-capacity-risk
   {:escalates? true
    :unit-op? false
    :summary "surface a capacity or patient-safety risk to the clinical lead"}})

(def reserved
  "Operations reserved to a licensed clinician. Naming one in a proposal is a
  permanent hard block, never an escalation: escalation would imply a human
  could approve the *actor* doing it, and no manager can delegate a clinical
  decision to a service-management actor."
  {:prescribe-treatment
   {:reason "a treatment order is the prescribing clinician's exclusive responsibility"}

   :discharge-patient
   {:reason "a discharge decision is the attending clinician's exclusive responsibility"}

   :certify-death
   {:reason "certification of death is the attending clinician's statutory responsibility"}

   :alter-clinical-record
   {:reason "amending a clinical record belongs to the clinician who authored it"}})

(defn supported? [op] (contains? supported op))
(defn reserved? [op] (contains? reserved op))

(defn declared?
  "True if `op` is named anywhere in this vocabulary. An op that is neither
  supported nor reserved is undeclared — the governor refuses it."
  [op]
  (or (supported? op) (reserved? op)))

(defn escalates?
  "True if the operation itself always requires human sign-off. Unsupported
  ops are never reached by this predicate (the governor hard-blocks first),
  so a false here is not an admission."
  [op]
  (boolean (get-in supported [op :escalates?])))

(defn unit-op?
  "True if the operation binds to a registered care unit and must therefore
  satisfy every registered fact about it. False for undeclared and reserved
  ops, which the governor hard-blocks before this is consulted."
  [op]
  (boolean (get-in supported [op :unit-op?])))

(defn reserved-reason [op] (get-in reserved [op :reason]))

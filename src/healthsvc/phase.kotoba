(ns healthsvc.phase
  "The verdict -> phase mapping for the ISCO-08 1342 health services managers
  actor, and what each phase is allowed to do.

  Runtime: portable `.cljc` (pure functions over data, no host interop).

  Why this namespace exists. The mapping used to be an inline `cond` inside
  the StateGraph's `:decide` node. Being inline, it could only be exercised by
  building and running a graph, so the routing rule that decides whether a
  staffing decision is written, held, or sent to a human had no test of its
  own and no name a ledger entry could carry.

  Three phases, and the ordering between them is the whole safety claim:

    :hold             hard violation. Never written. Not overridable.
    :request-approval escalation. Written only after a human resumes.
    :commit           clean. Written.

  `of-verdict` checks `:hard?` before `:escalate?` deliberately. A proposal
  that is both hard-blocked and low-confidence must hold, not escalate —
  escalating it would put a question to a human that they have no authority to
  answer yes to. For this actor that is not hypothetical: an emergency surge
  is an escalating operation *and* one that can breach the registered ratio,
  so the two conditions coincide on exactly the request most likely to be
  waved through."
  )

(def phases
  "Every phase this actor can route to, with what it may do."
  {:hold             {:writes? false :human-required? false :terminal? true}
   :request-approval {:writes? false :human-required? true  :terminal? false}
   :commit           {:writes? true  :human-required? false :terminal? true}})

(defn of-verdict
  "Route a governor verdict to a phase. Pure."
  [verdict]
  (cond
    (:hard? verdict)     :hold
    (:escalate? verdict) :request-approval
    :else                :commit))

(defn writes? [phase] (boolean (get-in phases [phase :writes?])))
(defn human-required? [phase] (boolean (get-in phases [phase :human-required?])))
(defn terminal? [phase] (boolean (get-in phases [phase :terminal?])))

(defn refusal?
  "True for phases that did not write. Both `:hold` and `:request-approval`
  are refusals of the proposal as submitted — the second one is a refusal to
  act without a human, not an approval-in-waiting. `healthsvc.sim` counts
  these; a run that produces none has demonstrated nothing."
  [phase]
  (not (writes? phase)))

(defn approved-commit?
  "True when a commit is being reached from an escalation, i.e. a human
  resumed the interrupted thread. The commit node records this so the audit
  ledger can distinguish a human-approved write from an automatic one —
  measured on the pre-change tree, it could not: an emergency surge waiver
  approved by a human and a routine staffing plan approved automatically left
  two `{:disposition :commit ...}` entries with no field telling them apart.
  For a ratio waiver that distinction is the entire reason the interrupt
  exists."
  [disposition]
  (= :request-approval disposition))

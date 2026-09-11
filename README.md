# cloud-itonami-isco-1342

Open Business Blueprint for **ISCO-08 1342**: Health Services Managers — an ISCO
**Wave 1 (design & governance)** occupation per ADR-2607121000. This
is the THIRD wave-1 blueprint batch: management/professional work is
cognitive, **no robotics gate** — eligible for actor implementation
now.

**Maturity: `:implemented`** — HealthServicesManagersAdvisor ⊣
HealthServicesManagersGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.

## Layout

| namespace | what it owns |
|---|---|
| `healthsvc.operation` | the **closed vocabulary** — which ops may be proposed, which escalate, which bind to a registered unit, and which are reserved to a clinician |
| `healthsvc.facts` | **well-formedness** of the client record, the registered unit record, the request and the proposal envelope |
| `healthsvc.governor` | the **decision** — composes the above and applies the arithmetic and interval invariants |
| `healthsvc.phase` | the **verdict → phase** routing (`:hold` / `:request-approval` / `:commit`) |
| `healthsvc.ledger` | the **chained append-only audit trail**, including who approved each write |
| `healthsvc.store` | SSoT for clients, units, committed records and the ledger |
| `healthsvc.actor` | the wired StateGraph |
| `healthsvc.sim` | the **governed-scenario harness** |

## The care-unit HARD invariants

Arithmetic and interval containment, neither negotiable:

1. **Staffing-ratio ceiling** — patient-count / staff-count must not
   exceed the unit's registered max-patients-per-staff ceiling
   (patient-safety arithmetic, not a judgement call). A unit holding
   patients with **zero** staff is named separately as
   `:unit-unstaffed`, because there the ratio is undefined rather than
   merely large.
2. **Licence window** — the proposed as-of day must fall inside the
   unit's registered operating-licence window (interval containment).

Also HARD: an **undeclared** operation, an operation **reserved to a
clinician**, an unregistered/foreign unit, a unit whose own
registration lacks the ceiling or window it would be compared against,
a proposal missing the head-counts or the day those invariants read, an
unusable `:confidence`, a request naming no client, and a non-`:propose`
effect.

Escalations (always human sign-off): any op declaring `:escalates?`
— currently `:approve-emergency-surge` (a temporary ratio *waiver
request*) and `:flag-capacity-risk` — and low confidence (< 0.6).

## What the last change closed

Every invariant above was gated on the literal `(= :approve-staffing-plan op)`,
which made the governor a **denylist**: it bound one operation by name and
admitted everything else. Measured on the pre-change tree, against a
registered client:

    {:op :discharge-all-patients :unit-id "U-1"
     :patient-count 999 :staff-count 1 :as-of-day 200}
    => {:ok? true :violations []}

The same gate exempted `:approve-emergency-surge` — the operation whose
entire purpose is to waive a staffing ratio — from the ratio, the licence
window and the registered-unit basis at once. A surge naming an
**unregistered** unit, at **50 patients to 1 staff** against a registered
ceiling of 4, on a day outside the licence, escalated with an **empty
violation list** and committed once a human resumed the thread. The person
signing it off was shown nothing to weigh.

Four more, each measured before it was closed:

* `(pos? staff-count)` guarded the ratio comparison, so **40 patients with
  nobody rostered** — the unbounded case — was the one input that passed.
* `(integer? as-of-day)` guarded the licence window, so omitting the day
  skipped the interval check.
* A unit registered without a ceiling made the governor **throw a
  NullPointerException** rather than refuse. A crash is not a refusal, and
  the same comparison answers `false` — admitting the plan — under
  ClojureScript.
* `:confidence 99.0` passed as high confidence.

The ledger was a plain vector, so a dropped or reordered entry was
undetectable, and a waiver a human signed left an entry byte-identical to
one the actor took itself.

## Running it

    kbb -M:test    # unit tests
    kbb -M:sim     # governed-scenario harness
    kbb -M:lint    # clj-kondo, errors fail

`kbb -M:sim` runs a table of 25 scenarios through the **real** graph and
exits non-zero unless every one reaches its declared phase, no refusal wrote
a record anyway, every ledger verifies, **and at least one refusal was
demonstrated**. A table that has stopped exercising the governor is reported
as a defect in the table rather than as a pass — a governed actor's claim is
not that it acts, but that there exist actions it refuses.

80 tests / 270 assertions green; 25 scenarios / 23 refusals.

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.

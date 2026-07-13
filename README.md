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
14 tests / 30 assertions green.

The care-unit HARD invariants — arithmetic and interval containment,
neither negotiable:

1. **Staffing-ratio ceiling** — patient-count / staff-count must not
   exceed the unit's registered max-patients-per-staff ceiling
   (patient-safety arithmetic, not a judgement call).
2. **License window** — the proposed as-of day must fall inside the
   unit's registered operating-license window (interval containment).

Also HARD: unregistered/foreign unit, unregistered organization,
non-`:propose` effect. Escalations (always human sign-off):
`:approve-emergency-surge` (temporary ratio waiver request), low
confidence (< 0.6).

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.

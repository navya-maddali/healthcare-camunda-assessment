# Use Case 2 — Healthcare Treatment Journey

Extracted from *Camunda 8.9 SaaS — Advanced Process Orchestration Implementation Assessment v2*
(§5, plus the §3 capability matrix and the §9 rubric). This is the requirement baseline this service
is built against; anything not traceable to a line here is scope we chose, not scope we were given.

## Business background

A multi-specialty hospital wants to orchestrate the inpatient journey from admission to discharge.
Clinicians currently reconcile paper history; diagnostic results arrive at different times from
different departments; specialist consultations are coordinated over phone calls — causing missed
consults and delayed discharges.

The target journey uses AI to summarize a patient's prior records at admission, runs ordered
diagnostics in parallel, lets the attending physician dynamically pull in exactly the specialist
consults a patient needs, and gates discharge on an objective readiness decision.

## Objective

Design and implement the treatment journey on the base framework, demonstrating a multi-instance
parallel diagnostics stage, an ad-hoc specialist consultation stage driven by the physician,
DMN-based triage and discharge-readiness decisions, and AI-generated clinical summaries.

## Personas

| Persona | Role in the process | Tasklist involvement |
|---|---|---|
| Patient | Admitted through ER or referral; the subject of the journey | None directly (represented by process data) |
| Registration Desk Officer | Registers the patient, captures demographics and consent | Registration & consent task |
| Attending Physician | Reviews diagnostics, defines the treatment plan, selects specialist consults, signs off discharge | Treatment plan, consult selection (ad-hoc), discharge sign-off |
| Diagnostics Technician | Performs the cardiology workup and uploads results | Cardiology workup task |
| Specialist (Neuro / Endo / Physio) | Provides consultation notes when the physician requests a consult | The assigned consult task |
| Nurse | Administers treatment per the plan and records observations | Treatment administration task |

## Business flow

1. A patient is admitted via ER or referral; the process starts with the admission event.
2. The registration desk completes registration and consent capture in Tasklist.
3. An AI step summarizes the patient's medical history from prior records and attaches it to the case.
4. A DMN decision performs triage and selects the care pathway, **including which diagnostic tests to
   order**.
5. The ordered tests execute as a **parallel multi-instance sub-process** — one instance per test —
   covering lab tests, imaging, and a cardiology workup (human task).
6. The attending physician reviews all results in Tasklist and defines the treatment plan.
7. If consults are needed, the physician activates an **ad-hoc sub-process** and selects only the
   required consultations (neurology, endocrinology, physiotherapy). Each consult is a human task for
   that specialist.
8. The treatment execution sub-process runs: the nurse administers treatment while automated vitals
   monitoring raises alerts on threshold breaches.
9. A DMN decision evaluates discharge readiness from vitals trends, completed treatment steps and
   consult outcomes. **If not ready, the flow loops back to the physician to revise the plan.**
10. When ready, an AI step drafts the discharge summary and home-care instructions; the physician
    reviews, signs off, and the patient is discharged.

## Camunda 8.9 implementation expectations

| # | Expectation |
|---|---|
| 1 | **Processes & sub-processes** — parent journey with a multi-instance parallel diagnostics sub-process (**dynamic cardinality from the triage DMN output**) and a treatment execution sub-process |
| 2 | **DMN** — two decision tables minimum: Triage & Care Pathway (which also outputs the list of tests) and Discharge Readiness |
| 3 | **AI Connector** — two AI steps: history summarization at admission and discharge summary drafting, with prompts that use process variables |
| 4 | **Ad-hoc sub-process** — specialist consultations; the physician activates individual consult tasks at runtime |
| 5 | **Looping** — the not-ready path back to plan revision must be modeled safely (no infinite-loop risk; consider an attempt counter or escalation) |
| 6 | **Tasklist** — registration, cardiology workup, treatment plan, each consult, treatment administration and discharge sign-off as forms with correct candidate groups |
| 7 | **Spring Boot 4.x workers** — lab/imaging ordering and result ingestion, vitals monitoring/alerting, and record archiving as job workers; **the vitals worker should demonstrate message correlation back into the process** |
| 8 | **Error handling** — handle a diagnostic system timeout with a BPMN error and an escalation to the physician |

## Expected deliverables

- BPMN 2.0 model(s) deployed to Camunda 8.9 SaaS, including all sub-processes and the ad-hoc sub-process
- DMN 1.3 decision tables, deployed and invoked from the process via business rule tasks
- AI Connector configuration for every AI step, with prompt design and output variable mapping documented
- Tasklist forms for every human task, assigned to the correct persona (candidate groups / assignees)
- Spring Boot 4.x job workers on the base framework for every service task, following its hexagonal layering
- Error handling: BPMN error events, retry configuration on workers, and compensation/rollback **where
  the flow indicates it**
- Testing evidence: at least one end-to-end happy path execution in Operate, plus one exception path

## Capability matrix — what UC2 is and is not graded on

From §3, "Camunda 8.9 Capability Coverage by Use Case". The Health column only:

| Capability | UC2 Health |
|---|---|
| DMN decision tables | **2** |
| Embedded / call-activity sub-processes | ✓ |
| Ad-hoc sub-process | ✓ |
| AI Connector steps | **2** |
| Parallel execution | ✓ (multi-instance) |
| Message / event-driven | ✓ (vitals) |
| Timers & escalation | **–** |
| Compensation / rollback | **–** |
| Tasklist human tasks | ✓ |
| Spring Boot 4.x job workers | ✓ |

**This table is why this service has no compensation path and no timers.** Compensation is marked "–"
for Health (it is required only for UC1 Loan and UC5 Claims), and the deliverables ask for
compensation "where the flow indicates it" — the diagnostics failure in this flow has exactly one
correct clinical outcome, escalation to the attending physician, and nothing to unwind on the way
there. An earlier revision of this service did implement order-cancellation compensation; it was
removed deliberately. See `DESIGN-NOTE.md` for the reasoning and what was learned building it.

## Ground rules that constrain the design

- Use the provided base framework; follow its hexagonal layering, error-handling and observability
  conventions. Do not scaffold a new project.
- **All human tasks must be performed through Camunda Tasklist** with proper forms and candidate
  groups — do not simulate task completion through the API in the demo. (The REST API in this service
  exists for automated regression runs, not for the walkthrough.)
- All service tasks must be Spring Boot 4.x job workers; connectors only where the use case explicitly
  calls for them (AI steps, notifications).
- DMN tables must be deployed and invoked from the process — hardcoded rule logic in workers is marked
  down.
- Where a flow says "selects only the tasks needed", an ad-hoc sub-process is expected — not a chain of
  exclusive gateways.

## Rubric weights (§9)

| Criterion | Weight |
|---|---|
| Process modeling quality | 20% |
| Spring Boot 4.x workers | 20% |
| DMN design | 15% |
| AI Connector usage | 15% |
| Ad-hoc sub-process | 10% |
| Error handling & resilience | 10% |
| Tasklist & personas | 5% |
| Testing evidence & documentation | 5% |

Process modeling quality is the joint-largest criterion and rewards "clean BPMN aligned to the
conceptual flow; correct gateway and sub-process choices; readable model with meaningful element
names" — which is the second reason unrequired constructs were removed rather than kept for show.

## Where each requirement is implemented

| Requirement | Where |
|---|---|
| MI parallel diagnostics, dynamic cardinality | `SubProcess_Diagnostics`, `inputCollection="=diagnosticTests"` from the triage DMN |
| Treatment execution sub-process | `SubProcess_Treatment` |
| Triage DMN | `dmn/triage-care-pathway.dmn` (FIRST) via `Task_Triage` |
| Discharge readiness DMN | `dmn/discharge-readiness.dmn` (UNIQUE) via `Task_DischargeReady` |
| AI history summary | `Task_HistorySummary` — AI Agent connector, `historySummary` |
| AI discharge summary | `Task_DischargeSummary` — AI Agent connector, `dischargeSummary` |
| Ad-hoc consults | `AdHoc_Consultations` — neuro / endo / physio, completion condition `=consultsComplete = true` |
| Safe loop | `dischargeAttemptCount` incremented by `Task_DischargeReady`; three-way `Gateway_Ready`; escalation to `Task_ClinDirectorReview` above 3 attempts |
| Tasklist forms | 8 `.form` files, one user task each (consults share `specialist-consult-form`) |
| Workers | `lab-test-ordering`, `lab-result-ingestion`, `vitals-monitoring`, `vitals-alert-handler`, `record-archiving` |
| Message correlation | `Task_VitalsMonitor` publishes `VitalsAlert`, correlated on `=caseId` into `EventSubProcess_VitalsAlert` |
| Diagnostic failure → escalation | `Error_DiagSystem` → `BoundaryError_Diag` → `Task_EscalatePhysician` |
| Worker retries | `retries` on every `zeebe:taskDefinition` (3 for lab/vitals/archive, 1 for the alert handler, 2 for AI) |

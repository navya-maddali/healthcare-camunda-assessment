# Project Understanding — Use Case 2, Healthcare Treatment Journey

What the assessment asks for, how this project answers it, and why the structure looks the way it
does.

---

## 1. The problem

A multi-specialty hospital orchestrates the inpatient journey from admission to discharge.
Clinicians reconcile paper history by hand, diagnostic results arrive at different times from
different departments, and specialist consultations are arranged over the phone — so consults get
missed and discharges slip.

The target journey summarises prior records with AI at admission, runs ordered diagnostics in
parallel, lets the attending physician pull in exactly the consults a patient needs, and gates
discharge on an objective decision rather than a judgement call.

## 2. Requirements → implementation

| Assessment expectation | Where it lives |
|---|---|
| Multi-instance parallel diagnostics, cardinality from triage | `SubProcess_Diagnostics`, collection `=diagnosticTests` |
| Ad-hoc sub-process for specialist consults | `AdHoc_Consultations` — neuro / endo / physio |
| Two DMN tables, invoked from business rule tasks | `triage-care-pathway` (FIRST), `discharge-readiness` (UNIQUE) |
| Two AI Connector steps with process-variable prompts | `Task_HistorySummary`, `Task_DischargeSummary` (`io.camunda.agenticai:aiagent:1`, provider `openai`, model from `=aiModel`) |
| Safe looping on the not-ready path | Attempt counter in `Task_DischargeReady`'s output mapping + three-way `Gateway_Ready` + director reset |
| Tasklist forms with correct candidate groups | 8 `.form` files under `forms/` |
| Spring Boot 4 job workers on the framework | 5 workers extending `BaseWorker` |
| Message correlation from the vitals worker | `VitalsAlert`, `correlationKey = caseId` |
| BPMN error + escalation for a diagnostic timeout | `DIAGNOSTIC_SYSTEM_UNAVAILABLE` → boundary → escalation task |

## 3. Personas

| Persona | Tasks |
|---|---|
| Registration Desk Officer | Registration and Consent |
| Attending Physician | Treatment plan, consult selection, discharge sign-off, escalation |
| Diagnostics Technician | Cardiology Workup |
| Specialist (neuro / endo / physio) | The consult activated for them |
| Nurse | Treatment Administration |
| Clinical Director | Review after three failed discharge attempts |

The patient is the subject of the journey, not an actor — represented by process data.

## 4. Why the structure is what it is

Two mechanisms drive the layout, and both are enforced rather than conventional.

**Auto-deploy dictates the resource tree.** `CamundaDeploymentConfig` pushes
`processes/*.bpmn`, `dmn/*.dmn` and `forms/*.form` on startup. Those globs are flat, so `dmn/` and
`forms/` are siblings of `processes/` rather than nested inside it. The payoff is that the cluster
can never quietly run a stale model — a class of bug whose symptom (`/tasks` returning `[]` while
the element is ACTIVE) points nowhere near its cause.

**ArchUnit dictates the package tree.** `HealthcareArchitectureTest` runs six framework rules. The
binding one is that only `infrastructure.camunda` may import `io.camunda.client`, which is why the
job workers sit in `infrastructure/camunda/worker/` and engine-rejection handling sits in
`CamundaEngineExceptionAdvice` rather than beside the other web advice.

Everything else follows the framework's hexagonal convention: dependencies point inwards, `domain`
holds the rules, `application` the use cases and outbound ports, `infrastructure` the adapters.

## 5. Decisions worth defending

**`VitalsAssessment` is the single authority on thresholds.** They were previously inlined in the
worker *and* duplicated on the reading record, and the two had already drifted — the worker had
silently dropped the bradycardia check, so a dangerously low heart rate raised no alert.

**Idempotency keys are scoped per task, not globally.** The framework guard keys on
`(businessKey, elementId)`. Diagnostics branches add `testType`, or the second branch looks like a
replay of the first. The vitals check adds `dischargeAttemptCount`, or the second loop pass looks
like a redelivery of the first — which is exactly what once made the revise-plan loop unable to
terminate. At-least-once delivery is about the same *job* arriving twice, not the same *element*
being reached twice.

**Business errors and technical faults are separated deliberately.** `BusinessException` becomes a
BPMN error the process handles; everything else propagates so Camunda decrements retries and raises
an incident for an operator. Burning retries on a business outcome, or hiding an infrastructure
fault behind a BPMN error, would both be wrong.

**AI output is always reviewed by a clinician.** The discharge summary is drafted by the model and
shown read-only on the sign-off form — it never reaches the record unread.

## 6. Known limitations

- The AI connector's full raw HTTP response is persisted as a process variable alongside the
  extracted summary, headers and all. Mapping to `historySummary` alone would be tidier.
- Diagnostic results and the vitals feed are simulated deterministically; there is no analyser or
  bedside-monitor integration.
- Multi-instance results are not aggregated into an output collection.
- The ad-hoc completion condition is satisfied from the consult form rather than a dedicated
  "close consultation round" task.

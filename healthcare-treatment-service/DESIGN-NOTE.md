# Design Note — Healthcare Treatment Journey (UC2)

Camunda 8.9 SaaS · Spring Boot 4.0.5 job workers on the Aaseya Camunda Process Framework.

---

## 1. Sub-process choices

**Parallel Diagnostics — embedded, multi-instance parallel.**
Cardinality is dynamic: `inputCollection` is `=diagnosticTests`, the list the triage decision
returns, so one branch runs per ordered test. Embedded rather than a call activity because the
branches are meaningless outside this admission — they share `caseId` and `patientId`, are never
started independently, and have no separate lifecycle to monitor. A call activity would buy
reuse we do not need at the cost of an extra deployment artifact and cross-instance variable
propagation.

One subtlety drove a fix. `Task_Triage` declares `resultVariable="triageResult"` *and* output
mappings. `resultVariable` is local to the task scope, and when output mappings are present only
the mapped targets propagate — so `triageResult` does not exist outside the task. The loop must
therefore read `=diagnosticTests` (the mapped output), not `=triageResult.diagnosticTests`.

**Treatment Execution — embedded**, so the vitals-alert event sub-process can be scoped to it.
A non-interrupting message start event inside it means alerts are handled *concurrently* with
treatment administration rather than interrupting it — clinically the right semantics, since an
alert should not cancel the nurse's task.

**Specialist Consultations — ad-hoc sub-process**, `ordering="Parallel"`,
`cancelRemainingInstances="false"`. The physician activates only the consults a given patient
needs at runtime; a gateway chain would have forced the decision into the model. Consults already
in flight are allowed to finish when the stage completes.

---

## 2. DMN design

### `triage-care-pathway` — hit policy FIRST

Inputs `admissionType`, `chiefComplaint`, `vitalsSeverity`; outputs `carePlan`,
`diagnosticTests`, `priority`, `assignedWard`.

FIRST is chosen because the rules are deliberately **overlapping and ordered by specificity**:
`ER + CHEST_PAIN + CRITICAL` must win over `ER + CHEST_PAIN + MODERATE`, which must win over the
catch-all. UNIQUE would reject this table outright; COLLECT would return several care plans for
one patient, which is not a decision. The final rule has every input blank, so the table is
**total** — triage can never fail to match, and the business rule task cannot incident on a
no-match.

`diagnosticTests` returns a list, which is what feeds the multi-instance cardinality — the
decision, not the model, determines how many diagnostic branches run.

### `discharge-readiness` — hit policy UNIQUE

Inputs `vitalsTrend`, `treatmentStepsCompleted`, `labResultsNormal`; outputs `dischargeReady`,
`dischargeReason`.

Discharge is a safety gate, so ambiguity must be impossible: exactly one rule may match, and
UNIQUE makes the engine enforce that rather than leaving it to review. The rules are mutually
exclusive by construction — `DETERIORATING` blocks regardless of the other inputs, and the
`STABLE`/`IMPROVING` rules partition the two booleans.

UNIQUE has a consequence worth stating: when an input is **missing**, no rule matches and the
decision returns `null`, after which `=dischargeResult.dischargeReady` fails. `treatmentStepsCompleted`
and `labResultsNormal` are therefore mandatory fields on the nurse's `treatment-admin-form`. This
is intentional — a discharge gate should refuse to answer on incomplete data rather than guess.

Both tables are unit-testable in isolation through
`POST /v2/decision-definitions/evaluation` without starting a process instance.

---

## 3. AI Connector design

Two AI steps, both implemented as **Camunda AI Connector tasks** (`io.camunda:http-json:1`,
element template `io.camunda.connectors.OpenAI.v1`) — not as job workers. Business logic that
belongs to the model stays in the model.

**History summarisation (admission).** System message constrains the output shape and forbids
invention; the user message is assembled from `patientName`, `patientId`, `admissionType` and
`chiefComplaint`. Result mapped from `=response.body.choices[1].message.content` to
`historySummary`, which the physician then reads on the treatment-plan form.

**Discharge summary drafting.** The prompt is built from variables produced *across the whole
journey* — `carePlan` (triage DMN), `treatmentPlan` (physician), `vitalsTrend` (vitals worker)
and `historySummary` (the first AI step) — which is what makes it process-aware rather than a
static template. Mapped to `dischargeSummary` and shown read-only on the sign-off form so a
clinician always reviews AI output before it reaches the record.

`temperature: 0.2` on both: clinical documentation should be reproducible, not creative.

**Failure handling.** `connectionTimeoutInSeconds: 20` bounds a hanging model call and
`retries="2"` bounds transient failures; exhausting them raises an incident rather than silently
degrading. The API key is a Camunda Console connector secret (`{{secrets.OPENAI_API_KEY}}`) and
never appears in the model.

---

## 4. Error handling and resilience

**BPMN error vs. incident — a deliberate split.** The framework's `BaseWorker` translates
`BusinessException` into `newThrowErrorCommand` and lets everything else propagate. So:

- *Expected business outcomes* → `BusinessException`. `LabOrderingUseCase` throws
  `DIAGNOSTIC_SYSTEM_UNAVAILABLE`, caught by the boundary event on the diagnostics sub-process
  and escalated to the physician as a Tasklist task. The clinical flow continues.
- *Technical faults* → left to propagate, so Camunda decrements retries and eventually raises an
  incident for an operator. Burning the retry budget on a business outcome, or hiding an
  infrastructure fault behind a BPMN error, would both be wrong.

**Bounded retries.** 3 for diagnostics and archiving, 1 for the alert handler (an alert that
cannot be logged should surface immediately, not retry), 2 for the AI steps.

**Loop safety.** The not-ready path is bounded three ways: an attempt counter incremented by a
script task, a threshold gateway escalating to the Clinical Director after three attempts, and —
critically — the director's task **resets `dischargeAttemptCount` to 0**. Without that reset the
counter stays above the threshold and `escalate → revise → escalate` never terminates. The vitals
simulation is also derived from `dischargeAttemptCount` rather than in-memory state, so the
patient can actually recover and the loop converges.

**Idempotency.** Workers extend `BaseWorker`, whose `IdempotencyGuard` short-circuits a replayed
job keyed on `(businessKey, elementId)`. The key is mapped per task in the BPMN, not globally:
the multi-instance branches use `=caseId + "-" + testType`, because keying on `caseId` alone
would make the guard treat the second diagnostic branch as a replay of the first and skip it.
Archiving additionally checks `existsByCaseId` and is backed by a unique constraint on
`case_id` — the guard covers the common case, the constraint covers the concurrent race.

**Message correlation.** `VitalsMonitoringUseCase` publishes `VitalsAlert` through the framework's
`ProcessService` port with `correlationKey = caseId`, matching the `zeebe:subscription` declared
on the message. The event sub-process is non-interrupting, so alerting never cancels treatment.

---

## 5. Layering

`domain` → no Spring, no Camunda, no JPA; `application` → use cases and outbound ports;
`infrastructure` → Camunda workers, JPA adapters, wiring. Workers hold no business rules; they
bind variables, call a use case, and map the result to a `WorkResult`.

`VitalsAssessment` is the single authority on vitals thresholds. Previously those thresholds were
inlined in the worker *and* duplicated on the reading record, and the two had already drifted —
the worker had silently dropped the bradycardia check, so a dangerously low heart rate raised no
alert. That class of bug is why the rule lives in exactly one place.

Schema is Flyway-owned with Hibernate in `validate` mode, so entity/schema drift fails at startup
rather than in production.

---

## 6. Known limitations

- The AI tasks require an `OPENAI_API_KEY` connector secret; without it they incident.
- Diagnostic results and the vitals feed are simulated deterministically — there is no real
  analyser or bedside-monitor integration.
- The ad-hoc completion condition (`=consultsComplete = true`) is satisfied from the consult form.
  A dedicated "close consultation round" physician task would model the intent more directly.
- Multi-instance results are not aggregated into an output collection; each branch writes
  `testResult` within its own scope. Collecting them would need the cardiology branch to emit the
  same shape as the automated branches.

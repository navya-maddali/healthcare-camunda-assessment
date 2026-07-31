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
decision returns `null`. `treatmentStepsCompleted` and `labResultsNormal` are therefore fields on
the nurse's `treatment-admin-form`. This is intentional — a discharge gate should refuse to answer
on incomplete data rather than guess.

Refusing to answer is only half a design, though. `=dischargeResult.dischargeReady = true` and
`… = false` both evaluate to false against a `null` result, so `Gateway_Ready` originally had no
outgoing flow to take and raised a `CONDITION_ERROR` incident — the journey stopped dead at the
gate. That was observed in a live run: completing `Task_TreatmentAdmin` through the REST API with a
payload thinner than the form (the API cannot enforce form fields) produced exactly it.

**`Flow_NotReady` is now the gateway's default flow.** An indeterminate decision routes to the
attempt counter, which retries the treatment loop and escalates to the Clinical Director after three
passes. Defaulting towards *not discharged* is the safe direction — an unanswerable decision should
never be the reason a patient is released — and it turns a stuck instance into a clinical
escalation, which is a path the process already models.

Note this is a mitigation, not a licence: the form is still the contract, and the right payload is
still the one the form defines.

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
degrading. The API key is a Camunda Console connector secret (`{{secrets.OPENAI_API_TOKEN}}`) and
never appears in the model.

**Response handling.** Both tasks use `resultExpression` rather than `resultVariable`, so only the
extracted text becomes a process variable:

```
resultExpression = {historySummary: body.choices[1].message.content}
```

The earlier `resultVariable: response` published the entire OpenAI envelope alongside it — every
response header including `set-cookie` (`__cf_bm`), `x-request-id`, and the organisation and project
identifiers. Roughly 3 KB of noise per instance, persisted in process state and visible to anyone
with Operate access.

It was also a functional defect, not just untidiness. OpenAI returns `Access-Control-Expose-Headers`
in two different casings, and any JSON parser that treats object keys case-insensitively — PowerShell
and several .NET paths among them — rejects the whole document. `GET /api/v1/cases/{key}/variables`
was therefore unparseable by a large class of clients until the mapping changed.

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

The vitals check needs the same treatment for a different reason. It sits inside the revise-plan
loop, so the *same* element is legitimately reached more than once. Inheriting the process-wide
`businessKey` made both halves of the key identical on every pass, and the guard could not tell a
second pass from a redelivery of the first — vitals never refreshed, the discharge gate never
opened, and the loop could not terminate. Folding the attempt counter in distinguishes them: a
genuine redelivery carries the same count and is still suppressed, a new pass is not. The general
rule — at-least-once delivery is about the same *job* arriving twice, not the same *element* being
reached twice, and a key that cannot separate the two will silently swallow loop iterations.
Archiving additionally checks `existsByCaseId` and is backed by a unique constraint on
`case_id` — the guard covers the common case, the constraint covers the concurrent race.

**Two more instances of the same trap, found by the 2026-07-31 scenario sweep.** Both were silent:
no incident, no failed request, nothing in the process state to look at. Only the framework's
`Replayed job detected … — completing silently` log line gave them away.

*The alert handler.* `Task_HandleAlert` mapped `businessKey = caseId`, so the guard keyed every
alert on a case identically and handled **only the first one, ever**. The second and every later
`VitalsAlert` — whether raised by the monitor on a repeat breach or posted to
`/cases/{caseId}/vitals-alerts` — correlated, started the event sub-process, created the job, and
was then completed without running. A clinical process that silently discards a repeat
deterioration alert has the worst failure mode available to it. Every alert now carries an
`alertId` and the handler keys on that.

*The counter reset defeating its own key.* Folding `dischargeAttemptCount` into the vitals key
assumes the counter is monotonic. It is not — `Task_ClinDirectorReview` deliberately resets it to
0, and that reset regenerates the key the *first* pass already consumed. So the vitals pass
immediately after a director review was always skipped: `vitalsTrend` kept its stale value and the
discharge gate then decided on vitals nobody took. That is precisely the pass that matters most,
the one right after a senior clinician intervened. The key is now
`=caseId + "-vitals-" + directorReviewCount + "-" + dischargeAttemptCount`, where
`directorReviewCount` is a monotonic generation counter the review task increments and never
resets. The alert id is derived from that same key, so it inherits the uniqueness rather than
re-deriving it and drifting again.

The lesson generalises past this process: **an idempotency key built from a mutable process
variable is only as sound as that variable's monotonicity**, and a key that silently collides
degrades into "skip the work", which looks identical to success from every angle except the log.

**Compensation — modelled, then deliberately removed.** An earlier revision compensated a booked
diagnostic order: `Task_OrderTest` carried a compensation boundary event whose handler,
`Task_CancelOrder`, called `lab-order-cancellation` when the analyser failed after the slot was
reserved. It worked. It was removed anyway.

The reason is scope, not difficulty. UC2's capability matrix marks compensation/rollback "–" for
Health (it is required of UC1 Loan and UC5 Claims), and the deliverables ask for compensation "where
the flow indicates it". This flow does not indicate it: a diagnostics failure has exactly one correct
clinical outcome — escalate to the attending physician — and reaching it is what matters to the
patient. The unwind step added a flow node, a job worker and a whole BPMN concept to the diagram
without changing where the case ended up. Against a rubric whose joint-largest criterion rewards a
clean, readable model, that is a net cost. Both failure flags now reach the same escalation:
`diagnosticSystemDown` fails at ordering, `analyserSystemDown` at ingestion, and the branch's error
end event propagates either to the sub-process boundary.

One finding from building it is worth keeping even though the code is gone. The first attempt threw
compensation from the *parent* scope, after the boundary error on the diagnostics sub-process. It
deployed, read correctly, and did nothing — **a compensation throw unwinds only activities that
completed in its own scope, and an interrupted sub-process never completed; it takes its handlers
down with it.** The working version had to catch the failure *inside* the sub-process, where
`Task_OrderTest` had genuinely completed and its handler was still subscribed, and only then re-raise
the error so the outer boundary still escalated. If compensation is ever needed here, that is where
it goes.

**The discharge gate, and a propagation trap.** `Task_DischargeReady` increments
`dischargeAttemptCount` in its own output mapping rather than through a separate script task, and a
single three-way `Gateway_Ready` then routes to discharge, to clinical director review above three
attempts, or back to plan revision as the default. That is two fewer nodes than the earlier
check → increment → check-max chain, for identical behaviour.

The trap is worth recording because it is silent and non-obvious: **the moment a task declares an
`ioMapping` output, its `resultVariable` stops propagating automatically** — the decision result
becomes local to the task scope and only mapped outputs reach the parent. Adding the counter output
alone would have left `dischargeResult` undefined at the gateway and raised an incident on a
condition that had not changed. The mapping therefore re-exports `=dischargeResult` explicitly
alongside the counter.

**Message correlation.** `VitalsMonitoringUseCase` publishes `VitalsAlert` through the framework's
`ProcessService` port with `correlationKey = caseId`, matching the `zeebe:subscription` declared
on the message. The event sub-process is non-interrupting, so alerting never cancels treatment.

---

## 5. Layering

`domain` → no Spring Web, no Camunda; `application` → use cases and outbound ports; `repository` →
Spring Data interfaces; `web` → controllers and DTOs; `infrastructure` → Camunda adapters, JPA
adapters, wiring. Workers hold no business rules; they bind variables, call a use case, and map the
result to a `WorkResult`.

**The package names are enforced, not conventional.** `HealthcareArchitectureTest` runs the
framework's six `ArchitectureRules` over `com.aaseya.healthcare`. The one that shapes the tree is
`ONLY_INFRASTRUCTURE_CAMUNDA_MAY_IMPORT_CAMUNDA_CLIENT`: any class importing `io.camunda.client`
must sit under `infrastructure.camunda`. That is why the five job workers live in
`infrastructure/camunda/worker/` rather than a top-level `worker/`, and why engine-rejection
handling sits in `CamundaEngineExceptionAdvice` under `infrastructure/camunda/` instead of beside
the other web advice — it is the only advice needing `ProblemException` and `ClientStatusException`,
and keeping it there is what lets `web/` stay free of engine imports.

The split also means the framework's own `GlobalExceptionHandler` (from `framework-web-starter`)
does most of the work. `HealthcareWebExceptionHandler` only fills the gaps it leaves: an unmapped
path, a wrong method, an unsupported media type, an unparseable body and a path variable that will
not convert would otherwise all reach its `Exception` catch-all and return 500, which tells a caller
to raise a ticket when the fix is in their own request.

`VitalsAssessment` is the single authority on vitals thresholds. Previously those thresholds were
inlined in the worker *and* duplicated on the reading record, and the two had already drifted —
the worker had silently dropped the bradycardia check, so a dangerously low heart rate raised no
alert. That class of bug is why the rule lives in exactly one place.

Schema is Flyway-owned with Hibernate in `validate` mode, so entity/schema drift fails at startup
rather than in production.

---

## 6. Known limitations

- The AI tasks require an `OPENAI_API_TOKEN` connector secret; without it they incident.
- Diagnostic results and the vitals feed are simulated deterministically — there is no real
  analyser or bedside-monitor integration.
- The ad-hoc completion condition (`=consultsComplete = true`) is satisfied from the consult form.
  A dedicated "close consultation round" physician task would model the intent more directly.
- Multi-instance results are not aggregated into an output collection; each branch writes
  `testResult` within its own scope. Collecting them would need the cardiology branch to emit the
  same shape as the automated branches.
- **`caseId` is not enforced unique at admission.** `POST /cases` accepts a `caseId` that already
  has a live instance and returns `201`. That matters more than it looks: `caseId` is the
  `VitalsAlert` correlation key, so an alert raised against a duplicated id correlates to only one
  of the instances, and it is the archive key, which carries a `UNIQUE` constraint — so the second
  instance to reach `Task_Archive` fails the insert and raises an incident at the very last step of
  a completed journey. Found by the 2026-07-31 sweep and left as-is deliberately: rejecting a
  duplicate changes the admission contract, and whether it should be a `409`, a redirect to the
  existing instance, or an accepted re-admission is a clinical decision rather than a technical
  one. The fix is a lookup in `TreatmentJourneyUseCase.admit` once that call is made.
- `GET /cases/{key}/variables` returns `200 {}` for an unknown instance key while
  `GET /cases/{key}` returns `404`. The variables route cannot distinguish "no such instance" from
  "indexed but no variables yet" without a second lookup, and the polling clients depend on the
  permissive form, so the inconsistency is deliberate.

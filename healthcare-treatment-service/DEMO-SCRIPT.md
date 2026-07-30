# Demo Script

A ~12 minute walkthrough. Each beat names what to show and the point it makes — the point matters
more than the clicking.

**Before you start:** service running (`mvn spring-boot:run "-Dspring-boot.run.profiles=local"`),
Operate and Tasklist open, `/actuator/health` green, and a unique `caseId` ready.

---

### 0 · Frame it (30s)

An inpatient journey from admission to discharge. Four things are worth watching: diagnostics run
in **parallel**, consults are chosen by the physician at **runtime**, discharge is gated by a
**decision table** rather than an opinion, and the not-ready path **loops safely**.

### 1 · Deployment is automatic (1 min)

Show the startup log:

```
Deployed Processes: <healthcare-treatment-journey:21>
Deployed Decisions: <discharge-readiness:7>,<triage-care-pathway:7>
Deployed Forms:     8 forms
```

> All 11 resources go up together on every start. The process references forms and decisions by id,
> so a partial deployment leaves dangling references — and a model edited but not redeployed fails
> in a way that points nowhere near its cause: the task list comes back empty while the element is
> still ACTIVE.

### 2 · Admit (1 min)

`POST /api/v1/cases` with the happy-path payload. Show the response — instance key **and version**.

> The API mirrors Tasklist rather than replacing it. Every human step can be driven either way,
> which is what makes the whole journey scriptable.

### 3 · Registration → AI → triage (2 min)

Complete **Registration and Consent** in Tasklist, then show `/variables`.

> Three things just happened unattended. The AI summarised the history from process variables —
> `historySummary` is real model output, not a template. The triage DMN returned the care pathway
> *and* the list of tests. And that list became the collection driving the multi-instance
> sub-process, so the fan-out width is decided at runtime by a decision table.

Show `["ECG", "CARDIOLOGY_WORKUP"]` and the two branches in Operate.

### 4 · Parallel diagnostics (1.5 min)

Show the ECG branch already finished — `orderId`, then `testResult` with a summary — while
Cardiology Workup waits for a human.

> One branch is fully automated, the other is a person. Same sub-process, same cardinality
> expression. That is the point of multi-instance here.

### 5 · Ad-hoc consults (2 min)

Complete **Cardiology Workup**, then on **Define Treatment Plan** tick *specialist consultations
required*. Activate **one** consult in the ad-hoc stage.

> Nothing in the model says which specialists a patient sees. The physician activates only what is
> needed, at runtime. Modelling this as three optional parallel branches would have been wrong —
> it implies a fixed set decided at design time.

### 6 · Vitals, alerting, and the loop (3 min)

Complete **Treatment Administration** with both boxes ticked. Then let vitals run.

> Watch this fail. The first vitals pass breaches thresholds, the worker publishes `VitalsAlert`
> correlated on `caseId`, and a **non-interrupting** event sub-process handles it — alerting must
> never cancel treatment. Discharge readiness then returns false and the flow loops back to the
> physician.

Revise the plan, re-administer, and show pass two recovering and the gate opening.

> The loop is bounded three ways: an attempt counter, a gateway escalating to the Clinical Director
> after three attempts, and — critically — the director's task resetting the counter. Without that
> reset, escalate-revise-escalate never terminates.
>
> There is a subtler trap here. The framework suppresses replayed jobs keyed on
> `(businessKey, elementId)`. Both were identical on every loop pass, so the second pass looked
> exactly like a redelivery of the first and was silently skipped — vitals never refreshed and the
> instance cycled forever. The key now folds in the attempt count. At-least-once delivery is about
> the same *job* arriving twice, not the same *element* being reached twice.

### 7 · Discharge and archive (1 min)

Show the AI-drafted discharge summary read-only on the sign-off form. Sign off, then:

```sql
SELECT case_id, patient_name, care_plan, vitals_trend, archived_at FROM patient_case;
```

> A clinician always reviews AI output before it reaches the record.

### 8 · Exception path (1.5 min)

Start an instance with `"diagnosticSystemDown": true`, complete registration, show **Escalate to
Physician** — and show that **no incident was raised**.

> A diagnostic system being down is an expected business outcome, so it becomes a BPMN error the
> process handles. A technical fault would instead burn retries and raise an incident for an
> operator. Conflating the two either wastes the retry budget or hides a real outage.

### 9 · Close (30s)

If asked what is enforced rather than merely intended: `mvn verify` runs six ArchUnit rules. The
binding one keeps `io.camunda.client` inside `infrastructure.camunda` — which is why the workers
live where they do. The structure is not a convention; it fails the build.

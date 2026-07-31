# End-to-End Test Report — 2026-07-31

> **Superseded — this report records process version 28, before the UC2 scope simplification.**
> It is kept as the evidence of what was tested and what it found, not as a description of the
> current model. Three things below no longer exist: the compensation path (**S4**), the
> `lab-order-cancellation` worker, and `Gateway_MaxAttempts` (the attempt counter moved into
> `Task_DischargeReady`'s output mapping and the loop is now routed by a three-way `Gateway_Ready`).
> The AI steps have also moved from the HTTP connector to the AI Agent connector. Everything else —
> S1, S2, S3, S5–S8, and both idempotency defects — still applies. **Re-run this sweep after the
> next deployment and replace this file.** See `USE-CASE-2.md` for why compensation was dropped.

A full scenario sweep of the deployed journey: clean build, real deployment to the `sin-1` Camunda
8.9 SaaS cluster, and every reachable path driven through the Java REST API against live Zeebe,
Operate and Tasklist data. Two silent defects were found and fixed; the run below is the post-fix
state.

**Result: every path reachable, zero unexplained incidents, 437/437 Postman assertions green.**

---

## How it was run

```bash
# 1. clean build, architecture tests included
mvn clean verify                       # ArchUnit 6/6 green

# 2. start; @Deployment pushes all 11 resources on every boot
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

Startup confirms the deployment actually happened rather than being assumed:

```
Deployed Processes:  <healthcare-treatment-journey:28>
Deployed Decisions:  <discharge-readiness:13>,<triage-care-pathway:13>
Deployed Forms:      <registration-form:23>, … 8 total
Starting job worker: lab-test-ordering, lab-result-ingestion, lab-order-cancellation,
                     vitals-monitoring, vitals-alert-handler, record-archiving
Started HealthcareTreatmentApplication in 12.7 seconds
```

Everything below was then driven through `http://localhost:8081/api/v1` — no Tasklist clicking, no
`zbctl`, no direct engine calls. Process variables were re-read after **every** transition rather
than only at the end, so each assertion is against observed state, not inferred state.

Version 26 was the starting point. 27 and 28 are the two fixes described below.

---

## Scenario coverage

| # | Scenario | Drives | Result |
|---|---|---|---|
| S1 | Happy path, full | consults + revise loop + discharge + archive | COMPLETED, 9 audit rows, 0 incidents |
| S2 | Both DMN tables, every rule | direct `/decisions/{id}/evaluation` | 12/12 rules + the UNIQUE no-match hole |
| S3 | Exception path | `diagnosticSystemDown` | BPMN error → escalation, 0 incidents |
| S4 | Compensation path *(path since removed)* | `analyserSystemDown` | order booked then withdrawn, 0 incidents |
| S5 | Message correlation | manual `POST /vitals-alerts` ×2 | both handled, treatment not interrupted |
| S6 | Max-attempts escalation | 4 failed discharge checks | Clinical Director Review, loop converged |
| S7 | Negative API contract | 9 malformed / hostile requests | all RFC-7807, cross-instance guard holds |
| S8 | Cancellation | `DELETE /cases/{key}` | 204, tasks drained, second delete 404 |
| S9 | Regression suite | newman, whole collection | 301 requests, 437 assertions, **0 failures** |

---

## S1 — Happy path

`CASE-WALK2-07310118` / pik `2251799815044589`, admitted `ER / CHEST_PAIN / CRITICAL`.

Checked at each transition:

1. **Admit** → `201`, key + version returned. `GET /cases/{key}` 404s for ~2s while the engine
   indexes — expected, needed 3 polls at 700 ms.
2. **Registration** completed via `POST /cases/{key}/tasks/completion` (no key — the single-task
   route).
3. **AI history summary** — real `gpt-4o-mini` output, 41 words, names the patient:
   > *"Patient Anita Rao (ID P-4471) was admitted via ER with a chief complaint of chest pain…"*
4. **Triage DMN** → `CARDIAC_CRITICAL / P1 / CCU`, `diagnosticTests = [ECG, ECHO, TROPONIN,
   CARDIOLOGY_WORKUP]`.
5. **Multi-instance fan-out** → `/elements` showed 1 `MULTI_INSTANCE_BODY` + 4 `SUB_PROCESS`, with
   3 automated lab branches and 1 user task, exactly matching the 4 entries above.
6. **Lab workers** wrote `orderId`, `testStatus`, `testResult`.
7. **Cardiology Workup** completed by `userTaskKey` (scoped route).
8. **Treatment Plan** completed by `elementId` (backward-compatible route).
9. **Ad-hoc consults** — activated via `POST /diversion`, then two tasks waited concurrently.
10. **Vitals pass 1** → `DETERIORATING`, alert raised → discharge gate `false`, counter `1`.
11. **Loop** → replan, re-administer → pass 2 `IMPROVING` → gate opens.
12. **AI discharge summary** — real output with a `Home Care Instructions` section echoing
    `carePlan`, `treatmentPlan` and `vitalsTrend`, proving the prompt received live variables.
13. **Sign-off → archive** → `COMPLETED`, `patient_case` row with all seven fields populated.
14. `GET /cases/{key}/incidents` → `[]`.

### Multi-instance scoping, confirmed

`orderId` / `testResult` at parent scope reflect **whichever branch finished last** — they are not
aggregated. `testType` is the multi-instance input element and is likewise last-writer-wins. This
is expected for the current model and is recorded as a known limitation.

---

## S2 — Decision tables, every rule

Driven directly so no rule is left as dead code:

```
triage-care-pathway (FIRST)
  ER/CHEST_PAIN/CRITICAL    -> CARDIAC_CRITICAL  P1 CCU     [ECG,ECHO,TROPONIN,CARDIOLOGY_WORKUP]
  ER/CHEST_PAIN/MODERATE    -> CARDIAC_STANDARD  P2 CCU     [ECG,CARDIOLOGY_WORKUP]
  OPD/RESPIRATORY/CRITICAL  -> RESPIRATORY_CARE  P1 ICU     [CHEST_XRAY,LAB_COMPLETE]
  WARD/NEUROLOGICAL/MILD    -> NEURO_CARE        P1 NEURO   [CT_BRAIN,LAB_COMPLETE,CARDIOLOGY_WORKUP]
  OPD/FEVER/MILD            -> GENERAL_CARE      P3 GENERAL [LAB_COMPLETE,CHEST_XRAY]
  ER/CHEST_PAIN/MILD        -> GENERAL_CARE (FIRST falls through to the catch-all)

discharge-readiness (UNIQUE) — all 7 rules returned their documented pair
  DETERIORATING/-/-      false  "Vitals deteriorating. Clinical review required."
  STABLE/true/true       true   "All discharge criteria met. Patient cleared."
  IMPROVING/true/true    true   …
  STABLE/false/-         false  "Treatment course not completed."
  IMPROVING/false/-      false  …
  STABLE/true/false      false  "Abnormal lab results. Review required."
  IMPROVING/true/false   false  …
```

**The UNIQUE no-match hole reproduces.** `vitalsTrend: "UNKNOWN"` matches no rule and returns an
empty result — the null `dischargeResult` that used to stall `Gateway_Ready` with a
`CONDITION_ERROR`. The default flow added earlier routes it to "not ready" instead, which is the
safe direction: an indeterminate decision must never be the reason a patient is released.

---

## S3 / S4 — Failure paths

Both reach **Escalate to Physician** with **zero incidents** — a BPMN error is a business outcome,
not a fault — and neither archives, because `Task_Archive` is not on the escalation path
(`GET /archive/{caseId}` → 404, correct).

The two flags differ in exactly the way the design intends, and parent-scope variables prove it:

| | `diagnosticSystemDown` | `analyserSystemDown` |
|---|---|---|
| fails at | ordering | result ingestion |
| `testType` | `ECG` | `ECG` |
| `orderId` | *absent* | `ORD-ECG-1CF906` |
| `testStatus` | *absent* | `ORDERED` |
| `cancelledOrderId` | *absent* | `ORD-ECG-1CF906` |

```
Lab order placed             | orderId=ORD-ECG-1CF906
Business exception translated  type=lab-result-ingestion code=DIAGNOSTIC_SYSTEM_UNAVAILABLE
Compensated diagnostic order | order=ORD-ECG-1CF906 test=ECG status=CANCELLED
```

Nothing was booked in S3, so there is nothing to compensate. In S4 the booking succeeded, so
compensation ran on **the exact order that was placed** and left `cancelledOrderId` behind as
durable evidence.

---

## S5 — Message correlation *(defect found and fixed)*

The event sub-process is nested **inside `SubProcess_Treatment`**, so `VitalsAlert` only correlates
while Treatment Execution is active. With `Task_TreatmentAdmin` waiting:

- `POST /cases/{caseId}/vitals-alerts` → `202`, handler ran with the supplied message.
- `Task_TreatmentAdmin` **stayed ACTIVE** — the non-interrupting start event does not kill
  treatment, which is the whole point of the pattern.
- A second alert on the same case **also** ran the handler.

That last line is the fix. Before it, the second alert did nothing:

```
Replayed job detected type=vitals-alert-handler elementId=Task_HandleAlert
businessKey=CASE-ALERT-07310124 — completing silently
```

`Task_HandleAlert` mapped `businessKey = caseId`, so the framework's `IdempotencyGuard` keyed every
alert on a case identically and handled **only the first one, ever**. Later alerts correlated,
started the event sub-process and created the job — which was then completed without running.
Silently discarding a repeat deterioration alert is the worst failure mode this process has.

Fixed in **v27**: every alert carries an `alertId`, and the handler keys on that. Callers may
supply their own for at-most-once semantics; omitting it means each call is a distinct alert.

---

## S6 — Max-attempts escalation *(second defect found and fixed)*

Four consecutive failing discharge checks, holding `labResultsNormal = false`:

```
attempt 1 -> DETERIORATING  ready=False  Vitals deteriorating. Clinical review required.
attempt 2 -> IMPROVING      ready=False  Abnormal lab results. Review required.
attempt 3 -> STABLE         ready=False  Abnormal lab results. Review required.
attempt 4 -> STABLE         ready=False  Abnormal lab results. Review required.
-> Gateway_MaxAttempts routes to Task_ClinDirectorReview (candidateGroup clinical-director)
```

After the review the ioMapping applied cleanly — `dischargeAttemptCount 0`, `directorReviewCount
1`, `directorDecision CONTINUE_TREATMENT`, revised `treatmentPlan`. The loop then resumed at
`Task_TreatmentPlan` and converged to a real discharge: **COMPLETED, archived, 16 audit rows, 0
incidents.**

The reset is what exposed the second defect. On the first run the post-review vitals pass never
happened:

```
Replayed job detected type=vitals-monitoring elementId=Task_VitalsMonitor
businessKey=CASE-DIR-07310130-vitals-0 — completing silently
```

The vitals idempotency key folded in `dischargeAttemptCount`, which assumes that counter is
monotonic — but `Task_ClinDirectorReview` resets it to 0, regenerating the key the *first* pass
already consumed. So `vitalsTrend` kept its stale `STABLE` and the gate opened on vitals nobody
took. **The patient was discharged on an observation that was never made**, and the skipped pass
was the one immediately after a senior clinician intervened.

Fixed in **v28** with a monotonic generation counter:
`=caseId + "-vitals-" + directorReviewCount + "-" + dischargeAttemptCount`.

Verified by re-running the same scenario and watching the worker rather than trusting the outcome:

```
'Vitals done' executions : 6 -> 7   (the pass really ran)
vitals jobs replayed     : 0 -> 0   (nothing suppressed)
trend from that pass     : DETERIORATING   (a fresh observation, not the stale STABLE)
```

---

## S7 — Negative API contract

Every response is RFC-7807 `ProblemDetail`.

| Request | Status | Body |
|---|---|---|
| completion, unknown instance | `409` | `No user task waiting on instance …` |
| completion, unknown element id | `409` | `No user task waiting at 'Task_DoesNotExist' …` |
| **user task key from another instance** | `409` | `No user task 226… waiting on instance 675…` |
| no-key completion, 2 tasks waiting | `409` | names both contenders |
| malformed JSON | `400` | `Request body could not be parsed as JSON` |
| `Content-Type: text/plain` | `415` | `Unsupported Media Type` |
| `DELETE /cases/{key}/variables` | `405` | `Method Not Allowed` |
| unknown decision id | `404` | engine rejection, surfaced verbatim |
| `GET /archive/{unknown}` | `404` | — |

The cross-instance guard was verified by consequence, not just status code: after the rejected
call, the other instance's `Task_Registration` was re-queried and **was still waiting**. The older
unscoped `POST /tasks/{taskKey}/completion` had no such guard, which is why the scoped route exists —
and why the unscoped one has since been removed entirely.

---

## S8 — Cancellation

`DELETE /cases/{key}` → `204`; tasks drain to `[]` immediately. `GET /cases/{key}` still reports
`ACTIVE` for a few seconds — read lag in the indexed store, not a failed cancel. A second `DELETE`
returns `404 Expected to cancel a process instance … but no such process was found`, which is the
authoritative confirmation the first one landed.

---

## S9 — Regression suite

```
requests   301
assertions 437     failed 0
duration   1m 1s
```

Two collection assertions had to be **corrected, not silenced**: `01.13` asserted
`businessKey === caseId`, which was literally asserting the alert-handler bug, and `01.18`
hard-coded the old `caseId-vitals-1` key shape. Both now assert the corrected contract, and `01.13`
additionally asserts the key is *not* the bare `caseId` so the defect cannot return unnoticed.

---

## What this run changed

| Version | Change |
|---|---|
| 26 | starting point |
| 27 | alert handler keys on a per-alert `alertId` instead of `caseId` |
| 28 | vitals key pairs a monotonic `directorReviewCount` with `dischargeAttemptCount` |

Both defects were **silent**: no incident, no failed request, nothing in the process state to look
at. Each surfaced only as a `Replayed job detected … — completing silently` line in the worker log,
and each degraded into "skip the clinical step", which is indistinguishable from success from
every angle except that log. Watching worker execution counts — not just process outcomes — is what
caught them.

Open items are recorded in [`DESIGN-NOTE.md`](DESIGN-NOTE.md) §6, chiefly that `caseId` is not
enforced unique at admission.

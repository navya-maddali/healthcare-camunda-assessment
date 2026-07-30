# Running the collection

How to get a clean green run, and what each assertion is actually proving.

---

## Before you run

1. Service up on **8081**: `mvn spring-boot:run "-Dspring-boot.run.profiles=local"`
2. **Healthcare Treatment Journey — Local** environment selected.
3. `caseId`, `processInstanceKey`, `userTaskKey` and the `err*` variables left **blank** — the
   scripts populate them. A stale `processInstanceKey` from a previous run is the single most
   common cause of a confusing failure.
4. `00 Health` green.

Run folders **01** and **02** in the Collection Runner, in order. Folders 03 and 04 are independent
and can be run any time.

## What a good run looks like

| Folder | Requests | Roughly |
|---|---|---|
| 00 Health | 1 | instant |
| 01 Happy path | 20 | 2–4 min, mostly polling |
| 02 Exception path | 4 | under a minute |
| 03 Decision tables | 8 | instant |
| 04 Utilities | 6 | on demand |

## Polling, not sleeping

Zeebe indexes asynchronously. A task exists in the engine before it is queryable, so fixed delays
either flake or waste time. Every `Await …` request re-invokes itself with
`pm.execution.setNextRequest(pm.info.requestName)` and gives up after a bounded number of attempts
using the `pollCount` variable.

If a run stalls on an `Await`, the instance is genuinely not where the collection expects — read
`04 Utilities → List active elements` and `List incidents` before suspecting the collection.

## The two beats worth watching

**The vitals loop runs twice on purpose.** `01.13` sees the first vitals pass breach and
`vitalsTrend` go `DETERIORATING`; discharge readiness returns false and the flow loops back to the
physician. `01.14`–`01.17` revise the plan and re-administer, and `01.18` asserts the loop
converged — `IMPROVING` or `STABLE`, gate open. A run that reaches discharge on the *first* pass
means the loop was skipped, which is a regression, not luck.

**The exception path raises no incident.** `02.5` asserts **Escalate to Physician** is waiting.
`DIAGNOSTIC_SYSTEM_UNAVAILABLE` is a business outcome routed through a BPMN error, not a technical
fault — so `List incidents` should be empty. An incident here means the worker threw the wrong
exception type.

## Direct DMN evaluation

Folder 03 calls `POST /decisions/{decisionId}/evaluation`, bypassing the process entirely. Useful
when a gate misbehaves and you need to know whether the decision table or the data feeding it is
wrong — `03.6`–`03.8` confirm `IMPROVING` and `STABLE` open discharge while `DETERIORATING` does
not.

## Verifying the archive

`01.24` reads the record back through `GET /archive/{{caseId}}`. Postman cannot query PostgreSQL,
so to confirm the row itself:

```sql
SELECT case_id, patient_name, care_plan, vitals_trend, archived_at
FROM patient_case WHERE case_id = '<caseId from this run>';
```

## When a run fails

| Symptom | Likely cause |
|---|---|
| `01.2 Await Registration` never resolves | Deployed model missing `<zeebe:userTask />`. Restart the service — it redeploys on startup. |
| Everything 404s | Service on 8080, environment pointing at 8081 (or the reverse). |
| A completion returns 409 | The journey is not at that step. Check `List active elements`. |
| A completion returns 400 | Malformed body, or a path variable that is not a number. |
| `01.18` fails | The vitals loop did not converge — check the `Task_VitalsMonitor` idempotency key includes `dischargeAttemptCount`. |
| An AI step incidents | `OPENAI_API_TOKEN` connector secret missing in Camunda Console. |

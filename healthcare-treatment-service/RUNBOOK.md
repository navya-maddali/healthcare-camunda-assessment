# Runbook — Healthcare Treatment Journey

Everything needed to set the service up, run the journey, watch it in Operate, and demo it.
Replaces the former `CHEAT-SHEET`, `POSTMAN-TO-OPERATE`, `RUN-WALKTHROUGH` and `DEMO-SCRIPT`.

- [1. One-page reference](#1-one-page-reference)
- [2. Setup](#2-setup)
- [3. Run it with Postman, watching Operate](#3-run-it-with-postman-watching-operate)
- [4. The same journey with curl](#4-the-same-journey-with-curl)
- [5. Exception path](#5-exception-path)
- [6. Demo beat sheet (~12 min)](#6-demo-beat-sheet-12-min)
- [7. When something looks wrong](#7-when-something-looks-wrong)

---

## 1. One-page reference

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.11'      # only if a shell predates the permanent fix
mvn spring-boot:run "-Dspring-boot.run.profiles=local"     # port 8081, deploys on startup
```

| | |
|---|---|
| Health | `http://localhost:8081/actuator/health` |
| Swagger UI | `http://localhost:8081/swagger-ui/index.html` |
| API base | `http://localhost:8081/api/v1` |
| Tasklist / Operate | Camunda Console → your cluster |

### Happy-path payload

```json
{
  "caseId": "CASE-1001",
  "variables": {
    "patientId": "PAT-1001", "patientName": "Asha Verma",
    "admissionType": "ER", "chiefComplaint": "CHEST_PAIN", "vitalsSeverity": "MODERATE"
  }
}
```

`ER / CHEST_PAIN / MODERATE` makes triage return `["ECG", "CARDIOLOGY_WORKUP"]` — two parallel
branches, one automatic and one human. `caseId` must be unique; it is both the correlation key and
the archive key. A repeated `caseId` makes the framework's idempotency guard skip work silently.

### The API

| Verb | Path | Purpose |
|---|---|---|
| POST | `/cases` | Admit a patient, start the journey |
| GET | `/cases/{key}` | Lifecycle state and version |
| GET | `/cases/{key}/tasks` | User tasks waiting now |
| POST | `/cases/{key}/tasks/completion` | Complete the only waiting task |
| POST | `/cases/{key}/tasks/{idOrKey}/completion` | Complete by element id **or** user task key |
| GET | `/cases/{key}/tasks/outcomes` | Audit trail of human steps |
| GET | `/cases/{key}/variables` | All process variables |
| GET | `/cases/{key}/incidents` | Incidents |
| GET | `/cases/{key}/elements` | Active elements |
| POST | `/cases/{key}/diversion` | Activate an ad-hoc consult / move the token |
| POST | `/cases/{caseId}/vitals-alerts` | Publish `VitalsAlert` by hand |
| DELETE | `/cases/{key}` | Cancel |
| POST | `/decisions/{decisionId}/evaluation` | Evaluate a DMN directly |
| GET | `/archive/{caseId}` | Read the archived record |

### Task order

1. **Registration and Consent** — `registration-desk`. Needs `consentObtained: true`.
2. *(auto)* AI history summary → triage DMN → diagnostics fan-out.
3. **Cardiology Workup** — `diagnostics-technician`. The ECG branch runs itself.
4. **Define Treatment Plan** — `attending-physician`. Tick consults to demo the ad-hoc stage.
5. **Specialist consults** *(optional)* — activate individually; tick *round complete* on the last.
6. **Treatment Administration** — `nurse`. Must tick *course completed* **and** *labs normal*.
7. *(auto)* Vitals: pass one breaches and raises `VitalsAlert`, pass two recovers.
8. **Discharge Sign-off** — `attending-physician`, after the AI drafts the summary.
9. *(auto)* Archiving writes a `patient_case` row.

### Exception injection

| Flag | Fails at | Result |
|---|---|---|
| `"diagnosticSystemDown": true` | ordering | BPMN error → **Escalate to Physician** |
| `"analyserSystemDown": true` | result ingestion | BPMN error → **Escalate to Physician** |

Neither raises an incident: both are business outcomes routed through a BPMN error. Both reach the
same escalation — see `DESIGN-NOTE.md` for why there is no separate unwind step.

### Verify the archive

```sql
SELECT case_id, patient_name, care_plan, vitals_trend, archived_at FROM patient_case;
```

---

## 2. Setup

| Requirement | Version |
|---|---|
| JDK | 21 |
| Maven | 3.9+ |
| PostgreSQL | 17.x, running |
| Camunda | 8.9 SaaS cluster |

**1. Install the framework once** — this project resolves its parent POM from `~/.m2`:

```bash
cd "camunda-process-framework-0.1.1/"
mvn install -DskipTests
```

**2. Create the database.** Flyway creates the tables; do not create them by hand:

```bash
psql -h localhost -U postgres -c "CREATE DATABASE healthcare_journey;"
```

**3. Configure credentials** — copy `src/main/resources/application-local.yml.example` to
`application-local.yml` and fill in cluster id, region, client id and client secret from
**Camunda Console → your cluster → API**. The file is git-ignored.

**4. Add the AI secret.** In **Camunda Console → your cluster → Connector secrets**, add
`OPENAI_API_TOKEN`. Both AI Agent tasks reference it as `{{secrets.OPENAI_API_TOKEN}}`. Without it
they raise incidents — though each has a boundary error that routes the journey past the AI step
rather than stranding it.

**5. Start:**

```bash
mvn clean verify
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

`verify` runs the six ArchUnit rules. Watch the startup log for:

```
Successfully validated 3 migrations
Deployed Processes: <healthcare-treatment-journey:N>
Started HealthcareTreatmentApplication in ~12 seconds
```

> **Deployment is automatic.** The service pushes the BPMN, both DMN tables and all eight forms on
> every startup, so the cluster can never run an older model than your source. Note version `N` —
> you will look for it in Operate. Instances already running an older version never migrate.

Confirm `db` and `camundaClient` are both `UP`:

```bash
curl -s http://localhost:8081/actuator/health
```

---

## 3. Run it with Postman, watching Operate

Open **Operate** and **Tasklist** from Camunda Console in separate tabs. In Operate → Processes →
**Healthcare Treatment Journey**, check the version selector matches the startup log.

Import both files from `postman/`, select the **Healthcare Treatment Journey — Local** environment,
and leave `caseId`, `processInstanceKey`, `userTaskKey` and the `err*` variables **blank** — the
scripts fill them. A stale `processInstanceKey` is the most common cause of confusing failures.

Run requests individually (not the Collection Runner) so you can watch Operate between steps. In the
Runner, set the delay to 1000 ms so the `Await …` pollers do not spin.

### 01.1 Admit patient
201 with `processInstanceKey` and `version`. **Operate:** the instance appears; the token sits on
**Registration and Consent**. **Tasklist:** the task appears for `registration-desk`.

### 01.2 → 01.3 Registration
`01.2` polls until the task is queryable — Zeebe indexes asynchronously, so a task exists slightly
before it can be read back. `01.3` completes it. Three things then happen unattended: the AI history
summary, the triage DMN, and the diagnostics fan-out.

### 01.7 Verify triage decision
`carePlan: CARDIAC_STANDARD`, `priority: P2`, `assignedWard: CCU`,
`diagnosticTests: ["ECG","CARDIOLOGY_WORKUP"]`, and a real `historySummary`.

**Operate:** open **Parallel Diagnostics** — two branch instances. The ECG branch already ran itself
(order → ingest); the cardiology branch waits on a human.

> The multi-instance stage doing its job: the number of branches was decided at runtime by a
> decision table, and one branch is fully automated while the other is a person.

### 01.8 → 01.10 Cardiology workup, treatment plan
Both branches converge and the token moves to **Define Treatment Plan**. `01.10` sets
`consultsRequired: false`, so the ad-hoc stage is skipped.

> **To demo the ad-hoc stage**, set `consultsRequired: true`. The token parks on **Specialist
> Consultations** with *no* consult started — nothing in the model says which specialists a patient
> sees. Activate one with `POST /cases/{key}/diversion`, body `{"toElementId":"Task_NeurologyConsult"}`,
> complete it with `consultsComplete: true`, and the stage closes.

### 01.12 Treatment administration
Tick both *treatment steps completed* and *lab results normal* — the discharge DMN reads both.

### 01.13 First vitals check — **watch this fail on purpose**
`vitalsTrend: DETERIORATING`, `vitalsAlertRaised: true`, `dischargeAttemptCount: 1`,
`businessKey` ending `-vitals-0-0-alert`.

**Operate:** inside **Treatment Execution**, the **Vitals Alert Handler** event sub-process has run.
It is *non-interrupting* — treatment was never cancelled, the alert was handled alongside it. The
readiness DMN returns false and the token loops back to the physician.

> A first pass that fails is correct behaviour. If the journey reached discharge on the first
> attempt, the loop was skipped — *that* would be the bug.

### 01.14 → 01.18 The second pass
Revise the plan, re-administer. `01.18` asserts `businessKey` ends `-vitals-0-1`, the trend is no
longer `DETERIORATING`, and `dischargeResult.dischargeReady` is `true`.

> The two passes must report *different* trends and *different* business keys. If both keys matched,
> the second vitals job would be suppressed as a replayed job and the instance would cycle forever.

### 01.21 → 01.24 Discharge and archive
**Draft Discharge Summary (AI)** runs, then sign-off. In Tasklist the AI-drafted summary is shown
read-only — a clinician always reviews AI output before it reaches the record. The instance moves to
**Completed** with no incidents, and `01.24` reads the archived row back from PostgreSQL.

### Folders 03 and 04
`03` calls the DMN tables directly, bypassing the process — useful when a gate misbehaves and you
need to know whether the table or the data feeding it is wrong. `04` pokes at a running instance.
**Raise vitals alert** is worth trying while an instance is inside treatment execution: it correlates
a message on `caseId` into the running process, the same mechanism the vitals worker uses. Outside
that window the message has nothing to correlate to.

### Regression run

```bash
cd postman
npx newman run healthcare-treatment-journey.postman_collection.json \
  -e healthcare-treatment-journey.postman_environment.json
```

---

## 4. The same journey with curl

```bash
KEY=...   # processInstanceKey from the admit response

# 1 — Admit
curl -X POST http://localhost:8081/api/v1/cases \
  -H 'Content-Type: application/json' \
  -d '{"caseId":"CASE-CURL-1","variables":{
        "patientId":"PAT-1001","patientName":"Asha Verma",
        "admissionType":"ER","chiefComplaint":"CHEST_PAIN","vitalsSeverity":"MODERATE"}}'

# 2 — What is waiting
curl -s http://localhost:8081/api/v1/cases/$KEY/tasks

# 3 — Complete the only waiting task
curl -X POST http://localhost:8081/api/v1/cases/$KEY/tasks/completion \
  -H 'Content-Type: application/json' \
  -d '{"completedBy":"registration-desk","variables":{"consentObtained":true}}'

# 4 — Complete by element id
curl -X POST http://localhost:8081/api/v1/cases/$KEY/tasks/Task_TreatmentPlan/completion \
  -H 'Content-Type: application/json' \
  -d '{"completedBy":"attending-physician","variables":{
        "treatmentPlan":"Dual antiplatelet therapy","consultsRequired":false}}'

# 5 — Complete by user task key (same route, numeric segment)
curl -X POST http://localhost:8081/api/v1/cases/$KEY/tasks/4503599628719836/completion \
  -H 'Content-Type: application/json' -d '{"variables":{"resultsUploaded":true}}'

# 6 — Activate an ad-hoc consult
curl -X POST http://localhost:8081/api/v1/cases/$KEY/diversion \
  -H 'Content-Type: application/json' -d '{"toElementId":"Task_NeurologyConsult"}'

# 7 — Inspect
curl -s http://localhost:8081/api/v1/cases/$KEY/variables
curl -s http://localhost:8081/api/v1/cases/$KEY/incidents        # [] on a clean run
curl -s http://localhost:8081/api/v1/cases/$KEY/tasks/outcomes   # who completed what
curl -s http://localhost:8081/api/v1/archive/CASE-CURL-1
```

Timing notes that look like bugs and are not: `GET /cases/{key}` 404s for 1–2 s after `POST /cases`
while the engine indexes, and `/variables` lags a completion by about a second — reading immediately
after completing a task returns pre-completion values.

---

## 5. Exception path

Folder **02**, or by hand with `"diagnosticSystemDown": true` in the admit payload.

Complete registration, then watch the ordering worker fail. **Operate:** the boundary event **System
Down** fires on the diagnostics sub-process and the token lands on **Escalate to Physician**.

**Check the incident count: zero.** A diagnostics system being down is an expected business outcome
routed through a BPMN error, not a technical fault. A technical fault would instead burn the retry
budget and raise an incident for an operator. Conflating the two either wastes retries or hides a
real outage.

`"analyserSystemDown": true` is the second injection point: the order *is* placed, then ingestion
throws, and the same boundary error carries the case to the same escalation.

---

## 6. Demo beat sheet (~12 min)

**0 · Frame it (30s).** An inpatient journey from admission to discharge. Four things worth
watching: diagnostics run in **parallel**, consults are chosen by the physician at **runtime**,
discharge is gated by a **decision table** rather than an opinion, and the not-ready path **loops
safely**.

**1 · Deployment is automatic (1 min).** Show the startup log — all 11 resources go up together. The
process references forms and decisions by id, so a partial deployment leaves dangling references, and
a model edited but not redeployed fails in a way that points nowhere near its cause: the task list
comes back empty while the element is still ACTIVE.

**2 · Admit (1 min).** Show the response — instance key *and* version.

**3 · Registration → AI → triage (2 min).** Complete registration in Tasklist, then show
`/variables`. Three things happened unattended: the AI summarised the history from process variables,
the triage DMN returned the care pathway *and* the test list, and that list became the collection
driving the multi-instance sub-process — so fan-out width is decided at runtime by a decision table.

**4 · Parallel diagnostics (1.5 min).** ECG branch already finished; cardiology waits for a human.
Same sub-process, same cardinality expression. That is the point of multi-instance here.

**5 · Ad-hoc consults (2 min).** Tick *consults required*, activate **one**. Nothing in the model
says which specialists a patient sees. Modelling this as three optional parallel branches would have
been wrong — it implies a fixed set decided at design time.

**6 · Vitals, alerting, and the loop (3 min).** Watch pass one fail: the worker publishes
`VitalsAlert` correlated on `caseId`, and a **non-interrupting** event sub-process handles it —
alerting must never cancel treatment. Readiness returns false and the flow loops back.

> The loop is bounded three ways: an attempt counter incremented by the readiness task itself, a
> gateway escalating to the Clinical Director above three attempts, and — critically — the director's
> task resetting the counter. Without that reset, escalate-revise-escalate never terminates.
>
> The subtler trap: the framework suppresses replayed jobs keyed on `(businessKey, elementId)`. Both
> were identical on every loop pass, so pass two looked exactly like a redelivery of pass one and was
> silently skipped — vitals never refreshed and the instance cycled forever. The key now folds in both
> counters. At-least-once delivery is about the same *job* arriving twice, not the same *element*
> being reached twice.

**7 · Discharge and archive (1 min).** AI-drafted summary read-only on the sign-off form; sign off;
show the `patient_case` row.

**8 · Exception path (1.5 min).** `diagnosticSystemDown: true` → **Escalate to Physician**, and **no
incident**.

**9 · Close (30s).** `mvn verify` runs six ArchUnit rules. The binding one keeps `io.camunda.client`
inside `infrastructure.camunda` — which is why the workers live where they do. The structure is not a
convention; it fails the build.

---

## 7. When something looks wrong

| Symptom | Cause |
|---|---|
| `/tasks` returns `[]` but Operate shows an active USER_TASK | Deployed BPMN missing `<zeebe:userTask />`. Restart — it redeploys from source. |
| Task exists but not in Tasklist | Same cause. |
| Everything 404s in Postman | Service on 8080, environment pointing at 8081, or the reverse. |
| A completion returns 409 | The journey is not at that step. Run **04 → List active elements**. |
| A completion returns 400 | Malformed body, or a path variable that is not a number. |
| An AI step raises an incident | `OPENAI_API_TOKEN` connector secret missing or misnamed in Console. |
| The loop never converges | Both vitals passes reported the same trend — check the `businessKey` on `Task_VitalsMonitor` still folds in both counters. |
| Ad-hoc consults never appear in `/tasks` | Expected. They must be activated via `POST /cases/{key}/diversion` first. |
| `JAVA_HOME is not defined correctly` | Needs JDK 21; check the path exists. |
| Triage falls through to `GENERAL_CARE` | The DMN reads exact enums — `ER`, `CHEST_PAIN`, `CRITICAL`. Free text or a `severity` key silently hits the catch-all rule. |
| PowerShell reports `Count 1` on an empty incident list | `@(Invoke-RestMethod …)` wraps an empty JSON array. Confirm with `curl`. |

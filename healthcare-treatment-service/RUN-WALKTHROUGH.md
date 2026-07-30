# Run Walkthrough

A first run from a clean machine to an archived case.

Every value below is **transcribed from an actual run** — process instance `4503599628719714`,
case `CASE-WALK2-07310051`, definition version 26, on 31 July 2026. Nothing here is illustrative;
if your run differs, that difference is worth investigating.

---

## 1. Prerequisites

| Requirement | Version |
|---|---|
| JDK | 21 |
| Maven | 3.9+ |
| PostgreSQL | 17.x |
| Camunda | 8.9 SaaS cluster |

Install the framework once — this project resolves its parent POM and starters from `~/.m2`:

```bash
cd "camunda-process-framework-0.1.1/"
mvn install -DskipTests
```

Create the database. Flyway creates the tables; do not create them by hand:

```bash
psql -h localhost -U postgres -c "CREATE DATABASE healthcare_journey;"
```

If `mvn` reports *"JAVA_HOME is not defined correctly"*, point it at a JDK 21 that exists:

```bash
JAVA_HOME="C:/Program Files/Java/jdk-21.0.11" mvn clean verify
```

Copy `src/main/resources/application-local.yml.example` to `application-local.yml` and fill in the
cluster id, region, client id and client secret. That file is git-ignored.

The two AI tasks need an **`OPENAI_API_TOKEN` connector secret in Camunda Console**
(Cluster → Connector secrets). It is a cluster-side secret; the service never sees it.

---

## 2. Start

```bash
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

Startup should show Flyway at **v3**, five workers registered, and all 11 resources deployed:

```
Migrating schema "public" to version "3 - case task outcome"
Deployed Processes: <healthcare-treatment-journey:26>
Started HealthcareTreatmentApplication in 13.8 seconds
```

```bash
curl -s http://localhost:8081/actuator/health
# {"components":{"camundaClient":{"status":"UP"},"db":{"status":"UP"},…},"status":"UP"}
```

Both `camundaClient` and `db` must be `UP` before going further.

---

## 3. The journey, step by step

### 1 — Admit

```bash
curl -X POST http://localhost:8081/api/v1/cases \
  -H 'Content-Type: application/json' \
  -d '{"caseId":"CASE-WALK2-07310051","variables":{
        "patientId":"P-4471","patientName":"Anita Rao",
        "admissionType":"ER","chiefComplaint":"CHEST_PAIN","vitalsSeverity":"CRITICAL"}}'
```

```json
{"caseId":"CASE-WALK2-07310051","processInstanceKey":4503599628719714,
 "processDefinitionKey":2251799815040045,"version":26}
```

> **The three triage inputs must be exactly `admissionType`, `chiefComplaint` and
> `vitalsSeverity`.** The decision table reads those names. Sending `severity` instead of
> `vitalsSeverity`, or `"chest pain"` instead of `"CHEST_PAIN"`, matches no specific rule and falls
> through to the catch-all — you get `GENERAL_CARE` / `P3` / `GENERAL` and wonder why a critical
> cardiac admission was routed to a general ward. That is the table working correctly on the input
> it was given.

### 2 — State

`GET /cases/{key}` returns **404 for the first second or two** while the engine indexes the new
instance. This run needed 6 polls at 700 ms. That is expected; poll rather than treating it as an
error.

```json
{"processInstanceKey":4503599628719714,"processDefinitionId":"healthcare-treatment-journey",
 "version":26,"state":"ACTIVE","hasIncident":false,"startDate":"2026-07-30T19:21:57.266Z","endDate":null}
```

### 3–4 — Registration

```bash
curl -s http://localhost:8081/api/v1/cases/$KEY/tasks
```

```json
[{"userTaskKey":4503599628719725,"elementId":"Task_Registration",
  "name":"Registration and Consent","state":"CREATED",…}]
```

One task waits, so complete it without naming it:

```bash
curl -X POST http://localhost:8081/api/v1/cases/$KEY/tasks/completion \
  -H 'Content-Type: application/json' \
  -d '{"completedBy":"reception.desk","variables":{
        "consentGiven":true,"registrationComplete":true,"insuranceVerified":true}}'
```

Returns the task it completed — `Task_Registration` — rather than a bare `204`.

### 5 — AI: Summarize History

Poll `GET /cases/{key}/variables` until `historySummary` appears. Observed:

> Patient Anita Rao (ID P-4471) was admitted via ER with a chief complaint of chest pain. Relevant
> prior history includes no documented allergies. Clinical status on admission indicates the patient
> is experiencing acute chest pain, necessitating further evaluation and management.

40 words, within the 80-word ceiling the system prompt sets. The prompt is interpolated with the
real admission variables, and the model is instructed never to invent findings — when the input
carries no history it says so rather than fabricating one.

This is an **HTTP Connector job running cluster-side**, not a worker in this service. If it fails,
the failure is an incident on `Task_HistorySummary`, and the cause is almost always the missing or
misnamed connector secret. The name is `OPENAI_API_TOKEN`, not `OPENAI_API_KEY`.

### 6 — DMN: Triage and Care Pathway

Same variables read. Rule 1 matched (`ER` + `CHEST_PAIN` + `CRITICAL`):

```
carePlan       : CARDIAC_CRITICAL
priority       : P1
assignedWard   : CCU
diagnosticTests: ECG, ECHO, TROPONIN, CARDIOLOGY_WORKUP
```

`triageResult` holds all four; the task's output mapping also lifts each to its own top-level
variable, which is what the downstream expressions read.

### 7 — Parallel diagnostics

`GET /cases/{key}/elements` while the sub-process runs:

```
elementId              type                state
SubProcess_Diagnostics MULTI_INSTANCE_BODY ACTIVE
SubProcess_Diagnostics SUB_PROCESS         ACTIVE
Task_CardiologyWorkup  USER_TASK           ACTIVE
```

One `MULTI_INSTANCE_BODY` plus the child instances still running. `diagnosticTests` has four
entries, so four branches started; by the time this was read the three lab branches had finished and
only the cardiology branch was still open, waiting on a human.

### 8 — Lab workers

```
orderId   : ORD-TROPONIN-1B2AB0
testStatus: ORDERED
testResult: {"testType":"TROPONIN","status":"COMPLETED","orderId":"ORD-TROPONIN-1B2AB0",
             "summary":"Troponin I below assay threshold."}
```

> These are **per-branch** variables that each branch writes to the parent scope, so what you read
> is whichever branch finished last — here TROPONIN. That is not a bug; a multi-instance branch's
> `orderId` is meaningful inside its own branch. Do not read process-level `orderId` expecting all
> four.

Each branch keys its idempotency guard on `caseId + "-" + testType`, so the four run independently
and a redelivery of one does not suppress another.

### 9 — Cardiology Workup, by user task key

Read the key from `/tasks`, then:

```bash
curl -X POST http://localhost:8081/api/v1/cases/$KEY/tasks/4503599628719836/completion \
  -H 'Content-Type: application/json' \
  -d '{"completedBy":"dr.cardio","variables":{
        "cardiologyFindings":"ST elevation II, III, aVF. Inferior STEMI.","ecgPerformed":true}}'
```

This route verifies the key belongs to `$KEY` before completing anything — see §5.

### 10 — Treatment Plan, by element id

The same route takes an element id instead:

```bash
curl -X POST http://localhost:8081/api/v1/cases/$KEY/tasks/Task_TreatmentPlan/completion \
  -H 'Content-Type: application/json' \
  -d '{"completedBy":"dr.mehta","variables":{
        "treatmentPlan":"Dual antiplatelet therapy, IV heparin, urgent PCI within 90 minutes.",
        "consultsRequired":true}}'
```

`consultsRequired: true` sends the token into the ad-hoc sub-process.

### 11 — Ad-hoc specialist consultations

**The three consult tasks do not appear on their own.** An ad-hoc sub-process activates nothing
until asked; `GET /tasks` returns `[]` while `/elements` shows `AdHoc_Consultations` ACTIVE. That
is the modelling construct behaving correctly, not a stall.

Activate the ones the case needs:

```bash
curl -X POST http://localhost:8081/api/v1/cases/$KEY/diversion \
  -H 'Content-Type: application/json' -d '{"toElementId":"Task_NeurologyConsult"}'
```

Both then appear:

```
     userTaskKey elementId
4503599628719961 Task_NeurologyConsult
4503599628719964 Task_PhysioConsult
```

Set `consultsComplete: true` on the last one you complete — that is the sub-process's completion
condition, and without it the scope stays open.

### 12–14 — Treatment administration and the first vitals pass

```bash
curl -X POST http://localhost:8081/api/v1/cases/$KEY/tasks/completion \
  -H 'Content-Type: application/json' \
  -d '{"completedBy":"nurse.priya","variables":{
        "medicationsAdministered":true,"observationsRecorded":true,
        "nursingNotes":"Tolerated PCI well. Chest pain resolved.",
        "treatmentStepsCompleted":true,"labResultsNormal":false}}'
```

> **Send the form's fields.** `treatment-admin-form` defines `treatmentStepsCompleted` and
> `labResultsNormal`, and the discharge decision reads both. Completing this task through the API
> with a thinner payload omits them, and the decision table — hit policy UNIQUE — then matches no
> rule at all. The form is the contract; the API does not enforce it for you.

Then:

```
vitalsTrend          : DETERIORATING
vitalsAlertRaised    : True
dischargeReady       : False
dischargeReason      : Vitals deteriorating. Clinical review required.
dischargeAttemptCount: 1
```

The non-interrupting event sub-process fired alongside the main flow — that is `vitalsAlertRaised`.

### 15–17 — The revise-plan loop converges

Second pass through `Task_TreatmentPlan` and `Task_TreatmentAdmin`, this time with
`labResultsNormal: true`:

```
vitalsTrend    : IMPROVING
dischargeReady : True
dischargeReason: All discharge criteria met. Patient cleared.
```

The loop terminating is the point. `Task_VitalsMonitor` keys its idempotency guard on
`caseId + "-vitals-" + dischargeAttemptCount`, so pass one keys on `…-vitals-0` and pass two on
`…-vitals-1`. Keyed on the case id alone, pass two would be treated as a redelivery, complete
silently without running, leave `vitalsTrend` at `DETERIORATING`, and loop until the attempt counter
escalated to the Clinical Director.

### 18 — AI: Draft Discharge Summary

Observed output, abridged:

```
**Discharge Summary**
**Patient Name:** Anita Rao
**Care Plan:** CARDIAC_CRITICAL
…
**Treatment Administered:** Post-PCI: Beta-blocker, ACE inhibitor, continue DAPT
**Final Vitals Trend:** Improving
---
**HOME CARE**
- Continue prescribed beta-blocker and ACE inhibitor as directed.
- Adhere to Dual Antiplatelet Therapy (DAPT) regimen.
- Monitor for any recurrence of chest pain…
```

Worth checking rather than glancing at: the summary echoes `patientName`, `carePlan`,
`treatmentPlan` and `vitalsTrend` back with the values this run actually produced, and carries the
`HOME CARE` section the prompt asks for. If those read as empty or generic, the prompt received
nulls and the upstream steps are what to look at.

### 19–20 — Sign-off, completion, archive

```
processInstanceKey : 4503599628719714
version            : 26
state              : COMPLETED
hasIncident        : False
endDate            : 2026-07-30T19:23:32.425Z
```

```bash
curl -s http://localhost:8081/api/v1/archive/CASE-WALK2-07310051
```

All seven columns populated — `caseId`, `patientId`, `patientName`, `carePlan`, `treatmentPlan`,
`dischargeSummary`, `vitalsTrend`. To confirm the row directly:

```sql
SELECT case_id, patient_name, care_plan, vitals_trend, archived_at
FROM patient_case WHERE case_id = 'CASE-WALK2-07310051';
```

### 21 — The audit trail

```bash
curl -s http://localhost:8081/api/v1/cases/$KEY/tasks/outcomes
```

```
elementId             completedBy    completedAt
Task_Registration     reception.desk 2026-07-31T00:52:04.941382
Task_CardiologyWorkup dr.cardio      2026-07-31T00:52:13.661504
Task_TreatmentPlan    dr.mehta       2026-07-31T00:52:18.512517
Task_NeurologyConsult specialist     2026-07-31T00:53:05.896985
Task_PhysioConsult    specialist     2026-07-31T00:53:06.561239
Task_TreatmentAdmin   nurse.priya    2026-07-31T00:53:11.617731
Task_TreatmentPlan    dr.mehta       2026-07-31T00:53:16.618816
Task_TreatmentAdmin   nurse.priya    2026-07-31T00:53:22.067345
Task_DischargeSignoff dr.mehta       2026-07-31T00:53:33.153910
```

Nine rows for nine human steps, in order, each with the JSON that was submitted. `Task_TreatmentPlan`
and `Task_TreatmentAdmin` appear twice — the two passes of the revise loop, kept as separate
clinical acts rather than collapsed.

### 22 — Incidents

```bash
curl -s http://localhost:8081/api/v1/cases/$KEY/incidents   # []
```

Empty for the whole run.

---

## 4. Watching it in Operate and Tasklist

The REST calls above and the Operate UI read the same engine data, so the UI is for seeing the
shape of the thing rather than for verification. Three moments are worth actually looking at:

| When | Operate | Tasklist |
|---|---|---|
| After step 4 | Token on `Task_HistorySummary`, then `Task_Triage`. Variables panel shows `historySummary`. | — |
| After step 11 | `AdHoc_Consultations` open with only the activated branches inside it | Two consult tasks, both rendering `specialist-consult-form` |
| After step 20 | Completed instance, full token path including both loop passes | Task list empty for this case |

Filter Operate by process `healthcare-treatment-journey` — the cluster is shared with unrelated
projects.

[`POSTMAN-TO-OPERATE.md`](POSTMAN-TO-OPERATE.md) has the click-by-click version.

---

## 5. Error contract

Verified against this build:

| Request | Response |
|---|---|
| `POST /cases/{key}/tasks/completion` with two consults waiting | `409` — *"has 2 tasks waiting (Task_NeurologyConsult, Task_PhysioConsult); complete one by element id or user task key"* |
| Same, after the instance completed | `409` — *"No user task waiting on instance …"* |
| A user task key from a **different** instance | `409` — *"No user task … waiting on instance …"*, and the other instance's task is left untouched |
| Unmapped path | `404` |
| Wrong method | `405` |
| `Content-Type: text/plain` | `415` |
| Malformed JSON body | `400` |

All are RFC 7807 `ProblemDetail`.

The cross-instance case is the one that matters. `POST /tasks/{taskKey}/completion` — the older
unscoped route — has no instance to check against and will complete whatever key it is handed.

---

## 6. Regression

```bash
cd postman
npx newman run healthcare-treatment-journey.postman_collection.json \
  -e healthcare-treatment-journey.postman_environment.json
```

Last run: **263 requests, 391 assertions, 0 failures, 57s.**

```bash
mvn clean verify   # ArchUnit 6/6
```

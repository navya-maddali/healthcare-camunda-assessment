# Step-by-step: Postman → Operate → Tasklist

Run the whole treatment journey once, driving it from Postman and watching each step land in
Camunda Operate. Every request is named exactly as it appears in the collection, and each step says
what you should see before moving on.

Allow about 20 minutes for the happy path, plus 5 for the exception and compensation paths.

---

## Part 0 — Before you start

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

**3. Configure credentials:**

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

Fill in cluster id, region, client id and client secret from **Camunda Console → your cluster →
API**. The file is git-ignored.

**4. Add the AI secret.** In **Camunda Console → your cluster → Connector secrets**, add
`OPENAI_API_TOKEN`. The BPMN references it as `{{secrets.OPENAI_API_TOKEN}}`. Without it, both AI
steps raise incidents — you will see them in Operate as red badges.

---

## Part 1 — Start the service

```bash
mvn clean verify
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

`verify` runs the architecture tests — six rules, all should pass.

**Watch the startup log for these three lines.** They tell you the process artifacts reached the
cluster:

```
Successfully validated 2 migrations
Deployed Processes: <healthcare-treatment-journey:N>
Started HealthcareTreatmentApplication in ~12 seconds
```

> **Deployment is automatic.** The service pushes the BPMN, both DMN tables and all eight forms on
> every startup. You never deploy by hand, and the cluster can never be running an older model than
> your source. Note the version number `N` — you will look for it in Operate in a moment.

Confirm it is healthy:

```bash
curl -s http://localhost:8081/actuator/health
```

Both `db` and `camundaClient` must be `UP`. Swagger UI is at
`http://localhost:8081/swagger-ui/index.html` if you want to browse the API.

---

## Part 2 — Open Operate and Tasklist

In **Camunda Console**, open your cluster and use the launch buttons for **Operate** and
**Tasklist**. Keep both open in separate browser tabs — you will switch between them constantly.
(The direct URLs follow the pattern `https://<region>.operate.camunda.io/<clusterId>`, but going
through Console is the reliable way in.)

In Operate, go to **Processes** and select **Healthcare Treatment Journey**. Check the version
selector shows the same version number the startup log printed. If it shows a lower one, the page
is cached — reload it.

You are now looking at the model you are about to run. Nine swimlanes, left to right: admissions,
AI services, clinical decisioning, diagnostics, physician, specialists, nursing, senior review,
records.

---

## Part 3 — Import the Postman collection

1. Postman → **Import** → both files from the `postman/` folder.
2. Select the **Healthcare Treatment Journey — Local** environment, top right.
3. Leave `caseId`, `processInstanceKey`, `userTaskKey` and the `err*` variables **blank** — the
   scripts fill them in. A stale `processInstanceKey` from an earlier run is the single most common
   cause of confusing failures.
4. Run **00 Health → Service health**. It must pass before anything else.

The environment points at `http://localhost:8081`. If you started the service on the default 8080,
change `baseUrl` and `localBaseUrl`.

---

## Part 4 — The happy path, one request at a time

Run these individually (not the Collection Runner) so you can watch Operate between steps.

### 01.1 Admit patient

Sends the admission payload and starts the journey.

**Postman:** 201, response carries `processInstanceKey` and the `version` it started on.

**Operate:** Processes → Healthcare Treatment Journey → the instance appears in the list. Click it.
The token sits on **Registration and Consent**, in the *Patient & Admissions* lane.

**Tasklist:** the task appears for candidate group `registration-desk`.

### 01.2 Await Registration

Polls until the user task is queryable. Zeebe indexes asynchronously, so a task exists in the
engine slightly before it can be read back.

### 01.3 Complete Registration

Completes the task through the API. You could equally do it in Tasklist — the point is that both
routes drive the same process.

**Operate:** watch the token move. Three things now happen without you:

1. **Summarize History (AI)** — the connector calls OpenAI.
2. **Triage & Care Pathway** — the DMN decides the care plan *and* the list of tests.
3. **Parallel Diagnostics** — the sub-process fans out, one branch per test.

### 01.7 Verify triage decision

**Postman:** `carePlan: CARDIAC_STANDARD`, `priority: P2`, `assignedWard: CCU`,
`diagnosticTests: ["ECG","CARDIOLOGY_WORKUP"]`, and `historySummary` — real model output, not a
template.

**Operate:** open the **Parallel Diagnostics** sub-process. Two branch instances. The **ECG** branch
has already run itself (order → ingest result); the **CARDIOLOGY_WORKUP** branch is waiting on a
human.

> This is the multi-instance stage doing its job: the number of branches was decided at runtime by
> a decision table, and one branch is fully automated while the other is a person.

### 01.8 Complete Cardiology Workup

**Operate:** both branches converge, the sub-process completes, the token moves to **Define
Treatment Plan**.

### 01.10 Complete Treatment Plan

The request sets `consultsRequired: false`, so the ad-hoc stage is skipped and the token goes
straight to treatment execution.

> **To demo the ad-hoc stage instead**, set `consultsRequired: true` in the body. The token then
> parks on **Specialist Consultations** with *no* consult started — nothing in the model says which
> specialists a patient sees. Activate one with
> `POST /api/v1/cases/{key}/diversion` and body `{"toElementId":"Task_NeurologyConsult"}`, complete
> it with `consultsComplete: true`, and the stage closes.

### 01.12 Complete Treatment Administration

You must tick both *treatment steps completed* and *lab results normal* — the discharge decision
reads both.

### 01.13 Await first vitals check — **watch this one fail on purpose**

**Postman:** `vitalsTrend: DETERIORATING`, `vitalsAlertRaised: true`, `dischargeAttemptCount: 1`.

**Operate:** in the **Treatment Execution** sub-process you can see the **Vitals Alert Handler**
event sub-process has run. It is *non-interrupting* — treatment was never cancelled, the alert was
handled alongside it.

The discharge-readiness decision returns false, the attempt counter increments, and the token loops
back to the physician.

> A first pass that fails is correct behaviour, not a problem. If the journey reached discharge on
> the first attempt, the loop was skipped — that would be the bug.

### 01.14 – 01.17 — the second pass

Revise the plan, administer again. Vitals recover this time.

### 01.18 Verify the loop converged

**Postman:** `vitalsTrend` is now `IMPROVING` or `STABLE`, and the gate opens.

> The two passes must report *different* trends. If both said `DETERIORATING`, the vitals task
> would have been suppressed as a replayed job — the idempotency key folds in the attempt counter
> precisely to stop that.

### 01.21 – 01.22 Discharge Sign-off

**Operate:** **Draft Discharge Summary (AI)** runs, then the token reaches sign-off.

**Tasklist:** open the task — the AI-drafted summary is shown read-only. A clinician always reviews
AI output before it reaches the record.

### 01.23 Await instance completion

**Operate:** the instance moves to **Completed**. Filter for *Completed* instances if it disappears
from your view. No incidents.

### 01.24 Verify archived record

**Postman:** the archived case comes back from PostgreSQL.

Confirm the row directly:

```sql
SELECT case_id, patient_name, care_plan, vitals_trend, archived_at FROM patient_case;
```

---

## Part 5 — The exception path

### 02.1 Admit with diagnostics offline

Starts an instance with `diagnosticSystemDown: true`.

### 02.2 / 02.5

Complete registration, then watch the ordering worker fail.

**Operate:** the boundary event **System Down** fires on the diagnostics sub-process and the token
lands on **Escalate to Physician**.

**Check the Incidents count: zero.** A diagnostics system being down is an expected business
outcome routed through a BPMN error, not a technical fault. A technical fault would instead burn
the retry budget and raise an incident for an operator. Conflating the two either wastes retries or
hides a real outage.

### 02.6 Cancel exception instance

Cleans up.

---

## Part 6 — The compensation path

Not in the collection yet — send it by hand. Same payload as 01.1, but add `analyserSystemDown`:

```json
{
  "caseId": "CASE-COMP-1",
  "variables": {
    "caseId": "CASE-COMP-1", "patientId": "PAT-1", "patientName": "Asha Verma",
    "admissionType": "ER", "chiefComplaint": "CHEST_PAIN", "vitalsSeverity": "MODERATE",
    "analyserSystemDown": true
  }
}
```

Complete registration, then watch the service log:

```
Lab order placed             | orderId=ORD-ECG-896F8E
Compensated diagnostic order | order=ORD-ECG-896F8E test=ECG status=CANCELLED
```

**Operate:** inside the diagnostics sub-process, **Cancel Lab Order** ran before the error
propagated, and the token then reached **Escalate to Physician**.

> The difference between the two failure flags is the whole point.
> `diagnosticSystemDown` fails at *ordering* — nothing was booked, so there is nothing to withdraw.
> `analyserSystemDown` fails at *ingestion* — the slot was already reserved, so it has to be
> released before the case moves on. Unwind first, escalate second.

---

## Part 7 — The decision tables on their own

Folder **03** calls the DMN tables directly, bypassing the process. Useful when a gate misbehaves
and you need to know whether the table or the data feeding it is wrong.

`03.1`–`03.5` cover triage, including a severity that falls through. `03.6`–`03.8` confirm
`IMPROVING` and `STABLE` open discharge while `DETERIORATING` does not.

---

## Part 8 — Utilities

Folder **04** is for poking at a running instance: waiting tasks, all variables, incidents, active
elements, publishing a `VitalsAlert` by hand, and cancelling.

**Raise vitals alert** is worth trying while an instance is inside treatment execution — it
correlates a message on `caseId` into the running process, the same mechanism the vitals worker
uses. Outside that window the message has nothing to correlate to.

---

## If something looks wrong

| Symptom | Cause |
|---|---|
| A task never appears, `/tasks` returns `[]`, but Operate shows an active USER_TASK | The deployed model is missing `<zeebe:userTask />`. Restart the service — it redeploys from source. |
| Everything 404s in Postman | Service on 8080, environment pointing at 8081, or the reverse. |
| A completion returns 409 | The journey is not at that step. Run **04 → List active elements**. |
| A completion returns 400 | Malformed body, or a path variable that is not a number. |
| An AI step shows an incident in Operate | `OPENAI_API_TOKEN` connector secret missing or misnamed in Console. |
| The loop never converges | Both vitals passes reported the same trend — check the idempotency key on `Task_VitalsMonitor`. |
| `JAVA_HOME is not defined correctly` | Needs JDK 21; check the path actually exists. |

Instances already running an older process version never migrate to a new one. After changing the
model, start a **new** instance and cancel the stranded ones.

# Run Walkthrough

A first run from a clean machine to an archived case. Commands are PowerShell-friendly; the API
calls work equally well from Postman or curl.

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

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.11'
```

## 2. Configure

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

Fill in cluster id, region, client id and client secret from Camunda Console → Cluster → API. The
file is git-ignored.

In Camunda Console → Cluster → **Connector secrets**, add `OPENAI_API_TOKEN`. The BPMN references
it as `{{secrets.OPENAI_API_TOKEN}}`; without it both AI steps raise incidents. It is a *cluster*
secret — it does not go in `application-local.yml`.

## 3. Build and start

```bash
mvn clean verify
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

`verify` runs the architecture tests — six rules, all of which should pass. Startup should show
Flyway at v2, the deployment, the five workers, and `Started HealthcareTreatmentApplication`.

```bash
curl -s http://localhost:8081/actuator/health
```

`camundaClient` and `db` both `UP`. If `camundaClient` is `DOWN`, the credentials in
`application-local.yml` are wrong — the app still starts, which is deliberate.

## 4. Admit a patient

```bash
curl -s -X POST http://localhost:8081/api/v1/cases \
  -H "Content-Type: application/json" \
  -d '{"caseId":"CASE-1001","variables":{"caseId":"CASE-1001","patientId":"PAT-1001",
       "patientName":"Asha Verma","admissionType":"ER","chiefComplaint":"CHEST_PAIN",
       "vitalsSeverity":"MODERATE"}}'
```

The response carries `processInstanceKey` and the `version` it started on. Keep the key.

## 5. Walk the journey

Zeebe indexes asynchronously, so a task may take a second to appear. Poll rather than assume:

```bash
curl -s http://localhost:8081/api/v1/cases/<key>/tasks
```

**Registration and Consent.** Complete with consent captured:

```bash
curl -s -X POST http://localhost:8081/api/v1/tasks/<userTaskKey>/completion \
  -H "Content-Type: application/json" \
  -d '{"variables":{"consentObtained":true,"allergiesNotes":"No known drug allergies"}}'
```

Three things now happen without you: the AI summarises the history, the triage DMN returns
`CARDIAC_STANDARD` with `["ECG","CARDIOLOGY_WORKUP"]`, and the diagnostics sub-process fans out.
The ECG branch orders and ingests its own result. Confirm:

```bash
curl -s http://localhost:8081/api/v1/cases/<key>/variables
```

You should see `historySummary`, `triageResult`, and `testResult` for the ECG branch.

**Cardiology Workup** → **Define Treatment Plan** → *(optional consults)* →
**Treatment Administration**. On treatment administration you must tick both *course completed* and
*labs normal*; the discharge decision reads both.

**The vitals loop runs twice by design.** Pass one breaches, publishes `VitalsAlert`, and the
non-interrupting event sub-process handles it — so discharge readiness fails once and the flow
returns to the physician. Pass two recovers and the gate opens. Watching it fail once is the point.

**Discharge Sign-off** appears after the AI drafts the summary. Then archiving runs.

## 6. Confirm the archive

```bash
curl -s http://localhost:8081/api/v1/archive/CASE-1001
```

```sql
SELECT case_id, patient_name, care_plan, vitals_trend, archived_at
FROM patient_case WHERE case_id = 'CASE-1001';
```

## 7. The exception path

```bash
curl -s -X POST http://localhost:8081/api/v1/cases \
  -H "Content-Type: application/json" \
  -d '{"caseId":"CASE-1002","variables":{"caseId":"CASE-1002","patientId":"PAT-1002",
       "patientName":"Ravi Kumar","admissionType":"ER","chiefComplaint":"CHEST_PAIN",
       "vitalsSeverity":"MODERATE","diagnosticSystemDown":true}}'
```

Complete registration; `LabTestOrderWorker` then throws `DIAGNOSTIC_SYSTEM_UNAVAILABLE`, the
boundary error fires, and **Escalate to Physician** appears. Note that no incident is raised — this
is a business outcome, not a technical fault, and the distinction is deliberate.

## 8. If it goes wrong

**`/tasks` returns `[]` but `/elements` shows an ACTIVE `USER_TASK`.** The deployed model is
missing `<zeebe:userTask />`. Restarting redeploys from source. Instances already running an older
version never migrate — start a new one and cancel the old.

**An AI task raises an incident.** The `OPENAI_API_TOKEN` connector secret is missing or misnamed.

**The vitals loop never converges.** The idempotency key for `Task_VitalsMonitor` must include
`dischargeAttemptCount`; without it every pass after the first is suppressed as a replay.

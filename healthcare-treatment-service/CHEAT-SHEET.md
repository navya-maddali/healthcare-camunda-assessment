# Cheat Sheet — Healthcare Treatment Journey

Everything you need mid-demo, on one page.

## Start it

```bash
mvn spring-boot:run "-Dspring-boot.run.profiles=local"     # port 8081, deploys on startup
```

| | |
|---|---|
| Health | `http://localhost:8081/actuator/health` |
| Swagger UI | `http://localhost:8081/swagger-ui/index.html` |
| API base | `http://localhost:8081/api/v1` |
| Tasklist / Operate | Camunda Console → your cluster |

## Happy-path payload

```json
{
  "caseId": "CASE-1001",
  "variables": {
    "caseId": "CASE-1001", "patientId": "PAT-1001", "patientName": "Asha Verma",
    "admissionType": "ER", "chiefComplaint": "CHEST_PAIN", "vitalsSeverity": "MODERATE"
  }
}
```

`ER / CHEST_PAIN / MODERATE` makes triage return `["ECG", "CARDIOLOGY_WORKUP"]` — two parallel
branches, one automatic and one human. `caseId` must be unique; it is both the correlation key and
the archive key.

## The API

| Verb | Path | Purpose |
|---|---|---|
| POST | `/cases` | Admit a patient, start the journey |
| GET | `/cases/{key}` | Lifecycle state and version |
| GET | `/cases/{key}/tasks` | User tasks waiting now |
| POST | `/tasks/{taskKey}/completion` | Complete by task key |
| POST | `/cases/{key}/tasks/{elementId}/completion` | Complete by element id |
| GET | `/cases/{key}/variables` | All process variables |
| GET | `/cases/{key}/incidents` | Incidents |
| GET | `/cases/{key}/elements` | Active elements |
| POST | `/cases/{caseId}/vitals-alerts` | Publish `VitalsAlert` by hand |
| POST | `/decisions/{decisionId}/evaluation` | Evaluate a DMN directly |
| GET | `/archive/{caseId}` | Read the archived record |

## Task order

1. **Registration and Consent** — `registration-desk`. Needs `consentObtained: true`.
2. *(auto)* AI history summary → triage DMN → diagnostics fan-out.
3. **Cardiology Workup** — `diagnostics-technician`. The ECG branch runs itself.
4. **Define Treatment Plan** — `attending-physician`. Tick consults to demo the ad-hoc stage.
5. **Specialist consults** *(optional)* — activate individually; tick *round complete* on the last.
6. **Treatment Administration** — `nurse`. Must tick *course completed* **and** *labs normal*.
7. *(auto)* Vitals: pass one breaches and raises `VitalsAlert`, pass two recovers.
8. **Discharge Sign-off** — `attending-physician`, after the AI drafts the summary.
9. *(auto)* Archiving writes a `patient_case` row.

## Exception and compensation paths

| Flag | Fails at | Result |
|---|---|---|
| `"diagnosticSystemDown": true` | ordering | BPMN error → **Escalate to Physician**. Nothing booked, so nothing to compensate. |
| `"analyserSystemDown": true` | result ingestion | Order *was* booked → compensation cancels it → **Escalate to Physician**. |

Neither raises an incident: both are business outcomes routed through a BPMN error.

## Verify the archive

```sql
SELECT case_id, patient_name, care_plan, vitals_trend, archived_at FROM patient_case;
```

## When something looks wrong

| Symptom | Cause |
|---|---|
| `/tasks` returns `[]` but `/elements` shows an ACTIVE `USER_TASK` | Deployed BPMN missing `<zeebe:userTask />`. Restart redeploys. |
| Task exists but not in Tasklist | Same cause. |
| AI task raises an incident | `OPENAI_API_TOKEN` connector secret missing in Camunda Console. |
| `JAVA_HOME is not defined correctly` | Needs JDK 21; check the path exists. |
| Startup fails on placeholders | Should not happen — every value has a default. Check `application-local.yml` is valid YAML. |
| Vitals loop never converges | Regression in the idempotency key. It must include `dischargeAttemptCount`. |

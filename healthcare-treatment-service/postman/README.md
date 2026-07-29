# Postman — Healthcare Treatment Journey

Drives the full inpatient journey against a Camunda 8.9 SaaS cluster over the v2 REST API.
32 requests across 6 folders, with assertions on every step.

## Import

1. Postman → **Import** → both files in this folder.
2. Select the **Healthcare Treatment Journey — SaaS** environment (top-right).
3. Fill in the four blanks from Camunda Console → Cluster → API:

   | Variable | Where to find it |
   |---|---|
   | `clusterId` | Cluster overview, the UUID |
   | `region` | Cluster overview (e.g. `sin-1`) |
   | `clientId` | API client credentials |
   | `clientSecret` | API client credentials, shown once at creation |

   `clientId` / `clientSecret` / `accessToken` are marked **secret**, so Postman masks them and
   leaves them out of exports.

4. Run **00 Auth → Get access token**. Everything else refreshes it automatically — the
   collection pre-request script renews the token ~60s before expiry.

## Folders

| Folder | What it does |
|---|---|
| **00 Auth** | OAuth token, cluster topology check |
| **01 Deploy** | Deploys BPMN + 2 DMN + 8 forms in one call |
| **02 Happy path** | Admission → triage → diagnostics → treatment → vitals → discharge → archive |
| **03 Exception path** | `diagnosticSystemDown` → boundary error → Escalate to Physician |
| **04 Scenario variants** | Direct DMN evaluation for triage and discharge rules; manual message publish |
| **05 Utilities** | Task/variable/element/incident queries, cancel, local health |

Folders 02 and 03 are ordered and work in the Collection Runner. Zeebe is eventually consistent,
so polling requests re-invoke themselves via `setNextRequest` rather than relying on fixed delays.

## Deployment request needs manual file selection

Postman cannot store file paths in an exported collection. Open **01 Deploy → Body → form-data**;
each of the 11 `resources` rows has an empty file picker and the file it wants is named in the
**Description** column. All 11 must go in one call — the BPMN references forms and decisions by
id, so a partial deployment leaves dangling references.

## Two known blockers

Both are handled by the collection, and both are worth fixing in the code.

**1. AI connector tasks incident.** `Task_HistorySummary` and `Task_DischargeSummary` need an
`OPENAI_API_KEY` connector secret in Camunda Console (Cluster → Connector secrets). Without it
they raise `ConnectorInputException`. Requests `02.5` and `03.3` route around this with a
process-instance modification. Delete them once the secret exists.

**2. The vitals loop cannot converge.** `VitalsMonitoringUseCase.observe()` returns a breaching
reading only while `priorAttempts == 0`, so the second pass should recover and open the discharge
gate. It never does: the framework `IdempotencyGuard` keys on job type + element id + `caseId`,
all identical on every loop pass, so every vitals check after the first is logged as

```
Replayed job detected type=vitals-monitoring elementId=Task_VitalsMonitor
businessKey=CASE-… — completing silently
```

and completes without running. `vitalsTrend` stays `DETERIORATING` for the life of the instance,
`discharge-readiness` keeps returning false, and the instance cycles
Treatment Plan → Treatment Admin → Vitals → Discharge gate → Increment → Treatment Plan forever.
`Gateway_MaxAttempts` does not stop it — over 3 attempts routes to Clinical Director Review, which
flows straight back to Treatment Plan.

Request `02.11` forces the route to discharge sign-off so the archive stage is still covered.
Delete it once the defect is fixed.

Folder 04 evaluates `discharge-readiness` directly and confirms `IMPROVING` and `STABLE` both
return `dischargeReady: true` — the decision table is correct, only the trend feeding it is stale.

## Verifying the archive

Postman cannot query PostgreSQL. Request `02.13` logs the SQL to the Postman console:

```sql
SELECT case_id, patient_name, care_plan, vitals_trend, archived_at
FROM patient_case WHERE case_id = '<caseId from this run>';
```

## Local health check

**05 Utilities → Local service health** hits the Spring Boot app rather than Camunda, and asserts
`db` and `camundaClient` are both `UP`. Point `localBaseUrl` at whichever port the app is on —
it defaults to `http://localhost:8080`.

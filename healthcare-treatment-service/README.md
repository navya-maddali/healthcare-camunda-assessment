# Healthcare Treatment Journey — Camunda 8.9 (Assessment Use Case 2)

Orchestrates the inpatient journey from admission to discharge: AI-summarised history at
admission, DMN-driven triage, parallel multi-instance diagnostics, physician-selected specialist
consults via an ad-hoc sub-process, treatment execution with vitals alerting by message
correlation, a DMN discharge-readiness gate, and an AI-drafted discharge summary.

Built on the **Aaseya Camunda Process Framework**, which is consumed as a published Maven
artifact and is **never modified**. This project lives outside the framework tree:

```
Desktop\Healthcare\
├── camunda-process-framework-0.1.1 (3)\   the base framework — untouched
└── healthcare-treatment-service\          this project
```

---

## Prerequisites

| Requirement | Version used |
|---|---|
| JDK | 21 |
| Maven | 3.9+ |
| PostgreSQL | 17.4 (`postgres` / `postgres`) |
| Camunda | 8.9 SaaS cluster |
| Base framework | `com.aaseya.camunda:*:0.1.0-SNAPSHOT` installed to `~/.m2` |

**Install the framework first.** This project resolves its parent POM and starters from the
local repository, so the framework must be installed before the first build:

```bash
cd "camunda-process-framework-0.1.1 (3)/camunda-process-framework-0.1.1"
mvn install -DskipTests
```

**Create the database:**

```bash
psql -h localhost -U postgres -c "CREATE DATABASE healthcare_journey;"
```

Flyway creates the schema on first start — do not create tables by hand.

---

## Configuration

No credentials are committed. `application.yml` reads them from the environment:

```bash
export CAMUNDA_CLIENT_ID='<api client id>'
export CAMUNDA_CLIENT_SECRET='<api client secret>'
export CAMUNDA_CLUSTER_ID='<cluster uuid>'
export CAMUNDA_REGION='sin-1'
```

The two AI Connector tasks additionally need an **`OPENAI_API_KEY` secret created in Camunda
Console** (Cluster → Connector secrets). The BPMN references it as `{{secrets.OPENAI_API_KEY}}`.
Without it those two tasks raise incidents.

---

## Build and run

```bash
cd healthcare-treatment-service
mvn clean verify
mvn spring-boot:run
```

Expected on startup:

```
Flyway   : Successfully applied 2 migrations to schema "public", now at version v2
Workers  : lab-test-ordering, lab-result-ingestion, vitals-monitoring,
           vitals-alert-handler, record-archiving
Started Application in ~12 seconds
```

```bash
curl -s http://localhost:8080/actuator/health     # camundaClient UP, db UP
```

---

## Deploy the process artifacts

BPMN, DMN and forms live under `src/main/resources/processes/`. Deploy all of them together —
the process references the forms and decisions by id, so a partial deployment leaves dangling
references.

```bash
TOKEN=$(curl -s -X POST https://login.cloud.camunda.io/oauth/token \
  -H "Content-Type: application/json" \
  -d "{\"grant_type\":\"client_credentials\",\"client_id\":\"$CAMUNDA_CLIENT_ID\",\"client_secret\":\"$CAMUNDA_CLIENT_SECRET\",\"audience\":\"zeebe.camunda.io\"}" \
  | grep -o '"access_token":"[^"]*"' | sed 's/.*:"//;s/"//')

BASE="https://${CAMUNDA_REGION}.zeebe.camunda.io/${CAMUNDA_CLUSTER_ID}/v2"

cd src/main/resources/processes
ARGS=""; for f in bpmn/*.bpmn dmn/*.dmn forms/*.form; do ARGS="$ARGS -F resources=@$f"; done
curl -s -X POST "$BASE/deployments" -H "Authorization: Bearer $TOKEN" $ARGS
```

Alternatively import the files into Web Modeler and deploy from there — recommended for the AI
tasks, since applying the **OpenAI element template** (`io.camunda.connectors.OpenAI.v1`) gives
you the connector UI instead of a raw service task.

---

## Project layout

Layering follows the framework convention; dependencies point inwards only.

```
src/main/java/com/aaseya/healthcare/
  domain/model/        Patient, VitalsReading, VitalsTrend, LabOrder, LabResult, PatientCaseRecord
  domain/service/      VitalsAssessment          — the single authority on vitals thresholds
  application/port/    PatientCaseArchive        — outbound port
  application/service/ LabOrderingUseCase, LabResultIngestionUseCase,
                       VitalsMonitoringUseCase, ArchiveCaseUseCase
  infrastructure/
    worker/            5 job workers, all extending the framework's BaseWorker
    persistence/       PatientCaseEntity, JPA repository, JpaPatientCaseArchive adapter
    config/            WorkerBeansConfig
src/main/resources/
  db/migration/        V1__framework_tables.sql, V2__patient_case.sql
  processes/bpmn|dmn|forms/
```

### Job workers

| Job type | Worker | Retries |
|---|---|---|
| `lab-test-ordering` | `LabTestOrderWorker` | 3 |
| `lab-result-ingestion` | `LabResultIngestionWorker` | 3 |
| `vitals-monitoring` | `VitalsMonitorWorker` | 3 |
| `vitals-alert-handler` | `VitalsAlertHandlerWorker` | 1 |
| `record-archiving` | `RecordArchiveWorker` | 3 |

The two AI steps are **not** workers — they are Camunda AI Connector tasks
(`io.camunda:http-json:1`).

### Human tasks and personas

All are Camunda user tasks (`<zeebe:userTask />`) and must be completed **through Tasklist**.

| Task | Form | Candidate group |
|---|---|---|
| Registration and Consent | `registration-form` | `registration-desk` |
| Cardiology Workup | `cardiology-workup-form` | `diagnostics-technician` |
| Define Treatment Plan | `treatment-plan-form` | `attending-physician` |
| Neurology / Endocrinology / Physiotherapy Consult | `specialist-consult-form` | `neuro-` / `endo-` / `physio-specialist` |
| Treatment Administration | `treatment-admin-form` | `nurse` |
| Discharge Sign-off | `discharge-form` | `attending-physician` |
| Escalate to Physician | `physician-escalation-form` | `attending-physician` |
| Clinical Director Review | `clinical-director-form` | `clinical-director` |

---

## Running the happy path

Start an instance with these variables (`caseId` must be unique — it is the correlation key and
the archive key):

```json
{
  "caseId": "CASE-1001",
  "patientId": "PAT-1001",
  "patientName": "Asha Verma",
  "admissionType": "ER",
  "chiefComplaint": "CHEST_PAIN",
  "vitalsSeverity": "MODERATE"
}
```

Then, in Tasklist:

1. **Registration and Consent** — captures demographics and consent.
2. *(automatic)* AI history summary → triage DMN → diagnostics fan-out.
   With `ER / CHEST_PAIN / MODERATE` the triage decision returns
   `["ECG", "CARDIOLOGY_WORKUP"]`, so two branches run in parallel.
3. **Cardiology Workup** — the `CARDIOLOGY_WORKUP` branch. The `ECG` branch is automatic
   (order → ingest result).
4. **Define Treatment Plan** — set the plan and tick *Specialist consultations required?* if you
   want to demo the ad-hoc stage.
5. **Specialist consults** *(only if requested)* — activate individual consults at runtime. Tick
   *Consultation round complete* on the last one to satisfy the completion condition.
6. **Treatment Administration** — **must** tick *Treatment course completed* and *Lab results
   normal*; the discharge decision reads both.
7. *(automatic)* Vitals monitoring. The first pass deliberately breaches, publishes `VitalsAlert`,
   and the event sub-process handles it — so discharge readiness fails once and the flow loops
   back to the physician. On the second pass vitals recover and the gate opens.
8. **Discharge Sign-off** — after the AI drafts the summary.
9. *(automatic)* Archiving writes a `patient_case` row.

```sql
SELECT case_id, patient_name, care_plan, vitals_trend, archived_at FROM patient_case;
```

## Running the exception path

Start an instance with `"diagnosticSystemDown": true`. `LabTestOrderWorker` raises
`DIAGNOSTIC_SYSTEM_UNAVAILABLE`, the boundary error event fires, and **Escalate to Physician**
appears in Tasklist.

---

## Troubleshooting

**`No qualifying bean of type 'IdempotencyGuard'`** — the framework's
`FrameworkCamundaAutoConfiguration` has no `@AutoConfigureAfter`, so its `@ConditionalOnBean`
guards evaluate before `JdbcTemplateAutoConfiguration` and the Camunda client register their
beans, and the beans are silently skipped. `WorkerBeansConfig` declares them locally to work
around this. Do not remove it.

**Flyway silently does nothing** — Spring Boot 4 split autoconfigurations into separate
artifacts. `flyway-core` alone is not enough; `spring-boot-flyway` must also be on the classpath
or `FlywayAutoConfiguration` is absent and migrations never run.

**Schema validation fails at startup** — `ddl-auto` is `validate` on purpose. Fix the migration
or the entity rather than switching to `update`.

**Tasks do not appear in Tasklist** — every user task needs `<zeebe:userTask />`. Without it they
are legacy job-worker tasks, invisible to the Tasklist v2 API.

**AI tasks incident** — the `OPENAI_API_KEY` connector secret is missing in Camunda Console.

---

## Documents

- [`DESIGN-NOTE.md`](DESIGN-NOTE.md) — sub-process choices, DMN hit policies, AI prompt design
  and error-handling strategy (assessment deliverable).

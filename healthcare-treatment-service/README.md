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

No credentials are committed. Copy the example profile and fill it in:

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

`application-local.yml` is git-ignored (`.gitignore`: `**/application-local.yml`) and holds the
cluster id, region, client id and client secret from Camunda Console → Cluster → API. Run with the
`local` profile and nothing else is needed:

```bash
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

Environment variables still work if you prefer them — `application.yml` reads
`CAMUNDA_CLIENT_ID`, `CAMUNDA_CLIENT_SECRET`, `CAMUNDA_CLUSTER_ID`, `CAMUNDA_REGION`, `DB_URL`,
`DB_USER`, `DB_PASSWORD` and `SERVER_PORT`. Every one has a default, so a missing value surfaces
as a connection error you can read rather than an unresolvable-placeholder crash before the
context is even built.

> Connector-secret exports downloaded from Camunda Console are named `<clusterId>.env`. Those are
> **cluster** secrets (the AI connector's token, for one) — they do not contain the client
> credentials this service needs, and `.gitignore` catches them via `*.env`.

The two AI Connector tasks additionally need an **`OPENAI_API_TOKEN` secret created in Camunda
Console** (Cluster → Connector secrets). The BPMN references it as `{{secrets.OPENAI_API_TOKEN}}`.
Without it those two tasks raise incidents. On the `sin-1` cluster this secret is configured and
verified working — a run on 2026-07-30 returned a summary from `gpt-4o-mini` with no incident.

---

## Build and run

```bash
cd healthcare-treatment-service
mvn clean verify
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

`mvn verify` runs the architecture tests. The example profile sets port **8081**, which is what the
Postman environment targets; `application.yml` defaults to 8080 without it.

Expected on startup:

```
Flyway   : Successfully applied 3 migrations to schema "public", now at version v3
Deployed : <healthcare-treatment-journey:N>, 2 decisions, 8 forms
Workers  : lab-test-ordering, lab-result-ingestion, lab-order-cancellation,
           vitals-monitoring, vitals-alert-handler, record-archiving
Started HealthcareTreatmentApplication in ~13 seconds
```

```bash
curl -s http://localhost:8081/actuator/health     # camundaClient UP, db UP
```

API documentation is served at `/swagger-ui/index.html`, raw schema at `/v3/api-docs`.

Everything the journey needs at runtime is also reachable over REST under `/api/v1` — admit,
inspect and complete tasks, read variables, incidents and active elements, evaluate decisions,
and read back the archive. That is what the Postman collection drives.

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/cases` | Admit a patient and start the journey |
| `GET` | `/cases/{key}` | Lifecycle state; 404 for a second or two while the engine indexes |
| `GET` | `/cases/{key}/tasks` | User tasks currently waiting |
| `POST` | `/cases/{key}/tasks/completion` | Complete **the** waiting task — no key needed |
| `POST` | `/cases/{key}/tasks/{idOrKey}/completion` | Complete by element id **or** by user task key |
| `GET` | `/cases/{key}/tasks/outcomes` | Audit trail of every human step completed here |
| `POST` | `/tasks/{taskKey}/completion` | Complete by key, unscoped |
| `GET` | `/cases/{key}/variables` | Every process variable, JSON parsed |
| `GET` | `/cases/{key}/incidents` | Incidents, resolved ones included |
| `GET` | `/cases/{key}/elements` | Active element instances |
| `POST` | `/cases/{key}/diversion` | Move the token — also how ad-hoc consults are activated |
| `POST` | `/cases/{caseId}/vitals-alerts` | Correlate the `VitalsAlert` message |
| `DELETE` | `/cases/{key}` | Cancel the journey |
| `POST` | `/decisions/{decisionId}/evaluation` | Evaluate a DMN table directly |
| `GET` | `/archive/{caseId}` | Read the archived record back from PostgreSQL |

**Completing tasks.** Three routes exist because they serve different callers. Most of the journey
waits on exactly one task, so `POST /cases/{key}/tasks/completion` is the least typing and the one
to reach for. Naming the task is only necessary during the specialist-consultation phase, where
several wait at once — and there the no-key route returns `409` naming the contenders rather than
guessing. The `{idOrKey}` segment takes either form: all digits is a user task key, anything else is
a BPMN element id. Element ids here are all `Task_*`, so nothing is ambiguous.

Completing by key through `/cases/{key}/tasks/{idOrKey}/completion` checks the task actually belongs
to that journey. The older unscoped `/tasks/{taskKey}/completion` does not — a key copied from
another case would complete that other case. Prefer the scoped form.

Every completion accepts an optional `completedBy`, and writes a row to `case_task_outcome`:

```bash
curl -X POST http://localhost:8081/api/v1/cases/$KEY/tasks/completion \
  -H 'Content-Type: application/json' \
  -d '{"completedBy":"dr.mehta","variables":{"treatmentPlan":"…","consultsRequired":false}}'
```

That trail outlives both the engine's history retention and the instance itself — cancelling a
journey does not erase what was submitted before it was cancelled.

**Raising a vitals alert.** `POST /cases/{caseId}/vitals-alerts` returns `202` and publishes the
`VitalsAlert` message. Two things are worth knowing before you call it:

- The event sub-process is nested **inside Treatment Execution**, so the alert only correlates while
  that sub-process is active — that is, while Treatment Administration is waiting or vitals are
  being taken. Published outside that window there is no open subscription to catch it.
- Each call raises a **distinct** alert. Supply your own `alertId` in the payload if you need the
  call to be idempotent across your retries; omit it and one is generated per call. The handler uses
  `alertId` as its idempotency key, which is what allows a repeat deterioration to be handled
  instead of silently swallowed.

The start event is non-interrupting, so an alert never cancels treatment — the handler runs
alongside the waiting task.

---

## Deploy the process artifacts

**Deployment is automatic.** `CamundaDeploymentConfig` carries an `@Deployment` annotation that
pushes all 11 resources — 1 BPMN + 2 DMN + 8 forms — to the cluster on every startup:

```java
@Deployment(resources = {
        "classpath*:processes/*.bpmn",
        "classpath*:dmn/*.dmn",
        "classpath*:forms/*.form"
})
```

They go up in one deployment, which is what the process requires: it references the forms and
decisions by id, so a partial deployment leaves dangling references. Camunda checksums each
resource, so restarting without an edit does not create a new version.

The startup log names what went up:

```
Deployed Processes: <healthcare-treatment-journey:21>
Deployed Decisions: <discharge-readiness:7>,<triage-care-pathway:7>
Deployed Forms:     <registration-form:17>, … (8 total)
```

This closes a failure mode that is otherwise easy to miss. Editing the BPMN without redeploying
leaves the cluster running the previous version indefinitely — running instances stay bound to the
version they started on. The symptom is quiet: `GET /api/v1/cases/{key}/tasks` returns `[]` and
Tasklist shows nothing, while `/elements` still reports an ACTIVE element of type `USER_TASK`.
That combination means the deployed version is missing `<zeebe:userTask />` — the element exists,
but the engine created no user-task entity for it.

Instances already running an older version do **not** migrate. After a change that matters, start a
new instance and cancel the stranded ones. To confirm which version an instance is on:

```bash
curl -s http://localhost:8081/api/v1/cases/<key> | grep -o '"processDefinitionVersion":[0-9]*'
```

For editing rather than deploying, import the files into Web Modeler — recommended for the AI
tasks, since applying the **OpenAI element template** (`io.camunda.connectors.OpenAI.v1`) gives you
the connector UI instead of a raw service task.

---

## Project layout

Layering follows the framework convention; dependencies point inwards only.

```
src/main/java/com/aaseya/healthcare/
  HealthcareTreatmentApplication.java
  domain/              Patient, VitalsReading, VitalsTrend, LabOrder, LabResult,
                       PatientCaseRecord, PatientCaseEntity,
                       VitalsAssessment      — the single authority on vitals thresholds
  application/         PatientCaseArchive, ProcessOrchestrationPort  (outbound ports)
                       LabOrderingUseCase, LabResultIngestionUseCase,
                       VitalsMonitoringUseCase, ArchiveCaseUseCase, TreatmentJourneyUseCase
  repository/          PatientCaseJpaRepository
  web/                 TreatmentJourneyController, HealthcareWebExceptionHandler
    dto/               request and response records
  config/              OpenApiConfig
  infrastructure/
    camunda/           CamundaProcessOrchestration, CamundaDeploymentConfig,
                       CamundaEngineExceptionAdvice, WorkerBeansConfig
      worker/          5 job workers, all extending the framework's BaseWorker
    persistence/       JpaPatientCaseArchive adapter
src/main/resources/
  application.yml, application-local.yml.example
  db/migration/        V1__framework_tables.sql, V2__patient_case.sql
  processes/           healthcare-treatment-journey.bpmn
  dmn/                 triage-care-pathway.dmn, discharge-readiness.dmn
  forms/               8 Tasklist forms
src/test/java/com/aaseya/healthcare/
  architecture/        HealthcareArchitectureTest
```

Two placements are load-bearing rather than stylistic:

- **Everything touching `io.camunda.client` lives under `infrastructure/camunda/`** — the workers
  and the engine exception advice included. `HealthcareArchitectureTest` enforces this via the
  framework's `ONLY_INFRASTRUCTURE_CAMUNDA_MAY_IMPORT_CAMUNDA_CLIENT` rule; move one out and the
  build fails.
- **`dmn/` and `forms/` are siblings of `processes/`, not nested inside it** — the `@Deployment`
  glob patterns are flat.

### Job workers

| Job type | Worker | Retries |
|---|---|---|
| `lab-test-ordering` | `LabTestOrderWorker` | 3 |
| `lab-result-ingestion` | `LabResultIngestionWorker` | 3 |
| `lab-order-cancellation` | `LabOrderCancelWorker` | 3 |
| `vitals-monitoring` | `VitalsMonitorWorker` | 3 |
| `vitals-alert-handler` | `VitalsAlertHandlerWorker` | 1 |
| `record-archiving` | `RecordArchiveWorker` | 3 |

**Idempotency keys are per-task, and getting one wrong fails silently.** `BaseWorker` short-circuits
a job whose `(businessKey, elementId)` pair it has already seen, so a `businessKey` that repeats
when the work does not is indistinguishable from "skip this step". Three tasks therefore map keys
that are *not* the `caseId`: the diagnostic branches fold in `testType`, `Task_VitalsMonitor` folds
in both `directorReviewCount` and `dischargeAttemptCount`, and the alert handler keys on a per-alert
`alertId`. The only symptom of a collision is
`Replayed job detected … — completing silently` in the log. See [`TEST-REPORT.md`](TEST-REPORT.md)
for two real instances of this and how they were caught.

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
appears in Tasklist. No incident is raised — this is a business outcome routed through a BPMN error,
not a technical fault.

## Running the compensation path

Start an instance with `"analyserSystemDown": true`. The order is placed successfully and the
analyser then fails while reporting the result, so there is a booking to withdraw. Compensation runs
inside the diagnostics sub-process before the error propagates, and the log shows the same order
being cancelled:

```
Lab order placed             | orderId=ORD-ECG-896F8E
Compensated diagnostic order | order=ORD-ECG-896F8E test=ECG status=CANCELLED
```

Escalation is then reached with the slot already released. The two flags differ deliberately:
`diagnosticSystemDown` fails at ordering, so nothing is booked and there is nothing to compensate.

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

**Tasks do not appear in Tasklist, and `/tasks` returns `[]`** — every user task needs
`<zeebe:userTask />`. Without it they are legacy job-worker tasks, invisible to the Tasklist v2
API and to `/user-tasks/search`. Check the *deployed* XML rather than the local file — this bites
hardest when the source is correct but was never redeployed. See *Deploy the process artifacts*.

**AI tasks incident** — the `OPENAI_API_TOKEN` connector secret is missing in Camunda Console.
Note the name: it is `OPENAI_API_TOKEN`, not `OPENAI_API_KEY`.

**A step appears to run but its variables never change** — and there is no incident, no failed
request and nothing wrong-looking in Operate. Grep the log for
`Replayed job detected … — completing silently`. That is the framework's idempotency guard
recognising a `(businessKey, elementId)` pair it has seen before and completing the job **without
running the worker**. It means the task's `businessKey` mapping repeats a value on a genuinely new
execution — the usual causes are keying on `caseId` alone where an element is reached more than
once, or folding in a counter that later gets reset. Fix the key mapping in the BPMN, not the
worker. Two real examples are written up in [`TEST-REPORT.md`](TEST-REPORT.md).

**`JAVA_HOME is not defined correctly`** — `mvn` fails before Spring Boot starts. The project
needs JDK 21; check that `JAVA_HOME` points at a JDK that actually exists on the machine.

---

## Documents

- [`POSTMAN-TO-OPERATE.md`](POSTMAN-TO-OPERATE.md) — **start here to run it.** Step by step from a
  cold start through Postman to watching each stage land in Operate and Tasklist.
- [`DESIGN-NOTE.md`](DESIGN-NOTE.md) — sub-process choices, DMN hit policies, AI prompt design
  and error-handling strategy (assessment deliverable).
- [`PROJECT-UNDERSTANDING.md`](PROJECT-UNDERSTANDING.md) — what the assessment asks for and where
  each requirement is implemented.
- [`RUN-WALKTHROUGH.md`](RUN-WALKTHROUGH.md) — the same journey driven with curl instead of Postman.
- [`TEST-REPORT.md`](TEST-REPORT.md) — the 2026-07-31 end-to-end scenario sweep: every path,
  every DMN rule, both failure modes, the negative API contract, and the two silent idempotency
  defects it uncovered.
- [`DEMO-SCRIPT.md`](DEMO-SCRIPT.md) — a ~12 minute presented walkthrough, with the point of each beat.
- [`CHEAT-SHEET.md`](CHEAT-SHEET.md) — one-page reference: endpoints, payloads, task order, flags.
- [`postman/POSTMAN-RUN.md`](postman/POSTMAN-RUN.md) — running the collection unattended and what
  each assertion proves.

# Postman — Healthcare Treatment Journey

Drives the full inpatient journey through **this service's own REST API**, not the Camunda cluster
directly. 39 requests across 5 folders, with assertions on every step.

The service holds the Camunda credentials, so the collection needs no OAuth token, no cluster id
and no client secret — it only needs the service to be running.

## Import and run

1. Postman → **Import** → both files in this folder.
2. Select the **Healthcare Treatment Journey — Local** environment (top-right).
3. Start the service on the port the environment expects:

   ```bash
   mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
   ```

4. Run **00 Health** to confirm `db` and `camundaClient` are both `UP`, then run folder **01** in
   the Collection Runner.

`baseUrl` is `http://localhost:8081/api/v1` and `localBaseUrl` is `http://localhost:8081`. If you
run the service on its default 8080, change both. Every other environment variable — `caseId`,
`processInstanceKey`, `userTaskKey` and the `err*` trio — is populated by the scripts as the run
proceeds; leave them blank.

## Folders

| Folder | What it does |
|---|---|
| **00 Health** | Actuator health — asserts `db` and `camundaClient` are `UP` |
| **01 Happy path** | Admission → AI summary → triage → diagnostics → treatment → vitals loop → discharge → archive |
| **02 Exception path** | `diagnosticSystemDown` → boundary error → Escalate to Physician |
| **03 Decision tables** | Direct DMN evaluation — 5 triage cases, 3 discharge-readiness cases |
| **04 Utilities** | Task/variable/element/incident queries, manual vitals alert, cancel |

Folders 01 and 02 are ordered and work in the Collection Runner. Zeebe is eventually consistent,
so polling requests re-invoke themselves via `setNextRequest` rather than relying on fixed delays.

## Deployment is not part of this collection

The 11 process resources (1 BPMN + 2 DMN + 8 forms) are deployed separately — see *Deploy the
process artifacts* in the root README. Deploy all 11 together; the process references forms and
decisions by id, so a partial deployment leaves dangling references.

**If a run stalls with no task appearing, suspect a stale deployment before suspecting the
collection.** Running instances stay bound to the version they started on. A deployed BPMN missing
`<zeebe:userTask />` produces exactly this: `/tasks` returns `[]` forever while `/elements` still
shows an ACTIVE `USER_TASK`. Redeploy, then start a fresh instance.

## Removed: the AI-incident polls

`01.4 Await AI incident` and `02.3 Await AI incident` have been deleted. They were written when the
`OPENAI_API_TOKEN` connector secret was missing and `Task_HistorySummary` reliably raised an
incident. That secret is now configured and the AI step succeeds, so neither could ever find its
incident — each polled 25 times, logged *"No AI incident"*, and moved on, wasting 50 polls per run.
They also asserted on `OPENAI_API_KEY`, which is not the secret's name.

Nothing referenced them by name (`setNextRequest` is only ever self-referential here), so the
ordered runs in folders 01 and 02 flow straight through.

## The vitals loop converges

Earlier revisions of this collection documented a defect where the revise-plan loop could not
terminate: the framework `IdempotencyGuard` keys on `(businessKey, elementId)`, and
`Task_VitalsMonitor` inherited the process-wide `businessKey`, so every pass after the first was
treated as a redelivery and completed silently. `vitalsTrend` stayed `DETERIORATING` and the
discharge gate never opened.

Fixed by folding the attempt counter into the key. Pass one keys on `…-vitals-0` and reports
`DETERIORATING`; pass two keys on `…-vitals-1`, runs, and reports `IMPROVING`. Requests `01.13`
through `01.18` walk both passes and `01.18` asserts the loop converged. No workaround requests
are needed, and none remain.

## Verifying the archive

Postman cannot query PostgreSQL. `01.24` reads the record back through the API instead:

```
GET {{baseUrl}}/archive/{{caseId}}
```

To confirm the row directly:

```sql
SELECT case_id, patient_name, care_plan, vitals_trend, archived_at
FROM patient_case WHERE case_id = '<caseId from this run>';
```

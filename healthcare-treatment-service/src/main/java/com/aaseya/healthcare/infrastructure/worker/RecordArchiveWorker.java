package com.aaseya.healthcare.infrastructure.worker;

import com.aaseya.camunda.framework.core.idempotency.IdempotencyGuard;
import com.aaseya.camunda.framework.core.worker.BaseWorker;
import com.aaseya.camunda.framework.core.worker.VariableMapper;
import com.aaseya.camunda.framework.core.worker.WorkResult;
import com.aaseya.healthcare.application.service.ArchiveCaseUseCase;
import com.aaseya.healthcare.domain.model.PatientCaseRecord;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Archives the discharged case to the {@code patient_case} table.
 *
 * <p>The BPMN maps {@code caseId} into {@code businessKey} for this task, so the framework's
 * idempotency guard engages and a replayed job completes without touching the database.
 */
@Component
public class RecordArchiveWorker extends BaseWorker<RecordArchiveWorker.ArchiveVars> {

    private static final Logger log = LoggerFactory.getLogger(RecordArchiveWorker.class);

    private final ArchiveCaseUseCase archiveCase;

    /**
     * @param mapper        framework variable mapper
     * @param guard         framework idempotency guard
     * @param meterRegistry Micrometer registry for {@code framework_job_*} counters
     * @param archiveCase   archiving use case
     */
    public RecordArchiveWorker(VariableMapper mapper, IdempotencyGuard guard,
                               MeterRegistry meterRegistry, ArchiveCaseUseCase archiveCase) {
        super(mapper, guard, meterRegistry);
        this.archiveCase = archiveCase;
    }

    /**
     * Typed input variables for the {@code record-archiving} job.
     *
     * @param caseId           admission business key
     * @param patientId        hospital identifier for the patient
     * @param patientName      full name as captured at registration
     * @param carePlan         care pathway chosen by triage
     * @param treatmentPlan    plan authored by the attending physician
     * @param dischargeSummary summary drafted by the discharge AI step
     * @param vitalsTrend      final trend reported by vitals monitoring
     */
    public record ArchiveVars(
            String caseId,
            String patientId,
            String patientName,
            String carePlan,
            String treatmentPlan,
            String dischargeSummary,
            String vitalsTrend) {
    }

    @Override
    protected Class<ArchiveVars> varsType() {
        return ArchiveVars.class;
    }

    @JobWorker(type = "record-archiving", autoComplete = false)
    public void handleArchive(JobClient client, ActivatedJob job) {
        execute(client, job);
    }

    @Override
    protected WorkResult doWork(ArchiveVars vars, ActivatedJob job) {
        PatientCaseRecord record = new PatientCaseRecord(
                vars.caseId(),
                vars.patientId(),
                vars.patientName(),
                vars.carePlan(),
                vars.treatmentPlan(),
                vars.dischargeSummary(),
                vars.vitalsTrend());

        ArchiveCaseUseCase.Outcome outcome = archiveCase.archive(record);

        if (outcome.alreadyArchived()) {
            log.info("Already archived | case={}", vars.caseId());
        } else {
            log.info("Archived | patient={} case={}", vars.patientName(), vars.caseId());
        }

        return WorkResult.completed(Map.of("archiveReferenceId", outcome.referenceId()));
    }
}

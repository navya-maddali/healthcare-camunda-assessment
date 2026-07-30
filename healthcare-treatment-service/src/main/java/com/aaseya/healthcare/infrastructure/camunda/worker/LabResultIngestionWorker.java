package com.aaseya.healthcare.infrastructure.camunda.worker;

import com.aaseya.camunda.framework.core.idempotency.IdempotencyGuard;
import com.aaseya.camunda.framework.core.worker.BaseWorker;
import com.aaseya.camunda.framework.core.worker.VariableMapper;
import com.aaseya.camunda.framework.core.worker.WorkResult;
import com.aaseya.healthcare.application.LabResultIngestionUseCase;
import com.aaseya.healthcare.domain.LabResult;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Ingests the result of a placed diagnostic order.
 *
 * <p>Writes a single {@code testResult} variable, which the diagnostics sub-process collects
 * into the {@code labResults} output collection — one entry per multi-instance branch.
 */
@Component
public class LabResultIngestionWorker extends BaseWorker<LabResultIngestionWorker.ResultVars> {

    private static final Logger log = LoggerFactory.getLogger(LabResultIngestionWorker.class);

    private final LabResultIngestionUseCase ingestion;

    /**
     * @param mapper        framework variable mapper
     * @param guard         framework idempotency guard
     * @param meterRegistry Micrometer registry for {@code framework_job_*} counters
     * @param ingestion     result ingestion use case
     */
    public LabResultIngestionWorker(VariableMapper mapper, IdempotencyGuard guard,
                                    MeterRegistry meterRegistry, LabResultIngestionUseCase ingestion) {
        super(mapper, guard, meterRegistry);
        this.ingestion = ingestion;
    }

    /**
     * Typed input variables for the {@code lab-result-ingestion} job.
     *
     * @param orderId            order being reported on
     * @param testType           the test that was run
     * @param caseId             admission business key
     * @param analyserSystemDown simulates the analyser failing after the order was accepted;
     *                           absent on the happy path, so boxed to tolerate {@code null}
     */
    public record ResultVars(String orderId, String testType, String caseId,
                             Boolean analyserSystemDown) {
    }

    @Override
    protected Class<ResultVars> varsType() {
        return ResultVars.class;
    }

    @JobWorker(type = "lab-result-ingestion", autoComplete = false)
    public void handleResultIngestion(JobClient client, ActivatedJob job) {
        execute(client, job);
    }

    @Override
    protected WorkResult doWork(ResultVars vars, ActivatedJob job) {
        boolean analyserDown = Boolean.TRUE.equals(vars.analyserSystemDown());
        LabResult result = ingestion.ingest(vars.orderId(), vars.testType(), analyserDown);

        log.info("Result ingested | order={} test={} status={}",
                result.orderId(), result.testType(), result.status());

        return WorkResult.completed(Map.of("testResult", Map.of(
                "orderId", result.orderId(),
                "testType", result.testType(),
                "status", result.status(),
                "summary", result.summary())));
    }
}

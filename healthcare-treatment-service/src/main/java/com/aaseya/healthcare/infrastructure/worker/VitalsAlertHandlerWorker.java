package com.aaseya.healthcare.infrastructure.worker;

import com.aaseya.camunda.framework.core.idempotency.IdempotencyGuard;
import com.aaseya.camunda.framework.core.worker.BaseWorker;
import com.aaseya.camunda.framework.core.worker.VariableMapper;
import com.aaseya.camunda.framework.core.worker.WorkResult;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles a correlated {@code VitalsAlert} inside the non-interrupting event sub-process.
 *
 * <p>Deliberately writes no variables back: the event sub-process runs concurrently with
 * treatment administration, and writing to the enclosing scope from a parallel branch would
 * race with the vitals worker over {@code vitalsTrend}.
 */
@Component
public class VitalsAlertHandlerWorker extends BaseWorker<VitalsAlertHandlerWorker.AlertVars> {

    private static final Logger log = LoggerFactory.getLogger(VitalsAlertHandlerWorker.class);

    /**
     * @param mapper        framework variable mapper
     * @param guard         framework idempotency guard
     * @param meterRegistry Micrometer registry for {@code framework_job_*} counters
     */
    public VitalsAlertHandlerWorker(VariableMapper mapper, IdempotencyGuard guard,
                                    MeterRegistry meterRegistry) {
        super(mapper, guard, meterRegistry);
    }

    /**
     * Typed input variables for the {@code vitals-alert-handler} job.
     *
     * @param alertMessage human-readable alert text carried on the message
     * @param vitalsTrend  trend reported at the time of the breach
     * @param patientId    patient the alert concerns
     */
    public record AlertVars(String alertMessage, String vitalsTrend, String patientId) {
    }

    @Override
    protected Class<AlertVars> varsType() {
        return AlertVars.class;
    }

    @JobWorker(type = "vitals-alert-handler", autoComplete = false)
    public void handleVitalsAlert(JobClient client, ActivatedJob job) {
        execute(client, job);
    }

    @Override
    protected WorkResult doWork(AlertVars vars, ActivatedJob job) {
        log.warn("=== VITALS ALERT === patient={} message={} trend={}",
                vars.patientId(), vars.alertMessage(), vars.vitalsTrend());
        return WorkResult.completed();
    }
}

package com.aaseya.healthcare.infrastructure.camunda.worker;

import com.aaseya.camunda.framework.core.idempotency.IdempotencyGuard;
import com.aaseya.camunda.framework.core.worker.BaseWorker;
import com.aaseya.camunda.framework.core.worker.VariableMapper;
import com.aaseya.camunda.framework.core.worker.WorkResult;
import com.aaseya.healthcare.application.VitalsMonitoringUseCase;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Observes the patient during treatment execution and, on a threshold breach, correlates a
 * {@code VitalsAlert} message back into the running instance.
 *
 * <p>The message is caught by the non-interrupting message start event of the vitals-alert event
 * sub-process, which is what demonstrates worker-to-process message correlation.
 */
@Component
public class VitalsMonitorWorker extends BaseWorker<VitalsMonitorWorker.VitalsVars> {

    private static final Logger log = LoggerFactory.getLogger(VitalsMonitorWorker.class);

    private final VitalsMonitoringUseCase monitoring;

    /**
     * @param mapper        framework variable mapper
     * @param guard         framework idempotency guard
     * @param meterRegistry Micrometer registry for {@code framework_job_*} counters
     * @param monitoring    vitals monitoring use case
     */
    public VitalsMonitorWorker(VariableMapper mapper, IdempotencyGuard guard,
                               MeterRegistry meterRegistry, VitalsMonitoringUseCase monitoring) {
        super(mapper, guard, meterRegistry);
        this.monitoring = monitoring;
    }

    /**
     * Typed input variables for the {@code vitals-monitoring} job.
     *
     * @param patientId             patient under observation
     * @param caseId                correlation key for the alert message
     * @param dischargeAttemptCount failed discharge-readiness checks so far; {@code null} on the
     *                              first pass, before the counter script task has run
     * @param businessKey           this pass's idempotency key, mapped in the BPMN and already
     *                              unique per monitoring pass; reused as the seed for the alert id
     *                              so the alert inherits exactly the same uniqueness
     */
    public record VitalsVars(String patientId, String caseId, Integer dischargeAttemptCount,
                             String businessKey) {
    }

    @Override
    protected Class<VitalsVars> varsType() {
        return VitalsVars.class;
    }

    @JobWorker(type = "vitals-monitoring", autoComplete = false)
    public void handleVitalsCheck(JobClient client, ActivatedJob job) {
        execute(client, job);
    }

    @Override
    protected WorkResult doWork(VitalsVars vars, ActivatedJob job) {
        int priorAttempts = vars.dischargeAttemptCount() == null ? 0 : vars.dischargeAttemptCount();

        String alertId = (vars.businessKey() == null
                ? vars.caseId() + "-vitals-" + priorAttempts
                : vars.businessKey()) + "-alert";

        VitalsMonitoringUseCase.Outcome outcome =
                monitoring.monitor(vars.patientId(), vars.caseId(), priorAttempts, alertId);

        if (outcome.breached()) {
            log.warn("Vitals alert | patient={} HR={} SpO2={}",
                    vars.patientId(), outcome.reading().heartRate(), outcome.reading().spO2());
        }

        log.info("Vitals done | patient={} trend={} alert={}",
                vars.patientId(), outcome.trend(), outcome.breached());

        return WorkResult.completed(Map.of(
                "vitalsTrend", outcome.trend().name(),
                "vitalsAlertRaised", outcome.breached()));
    }
}

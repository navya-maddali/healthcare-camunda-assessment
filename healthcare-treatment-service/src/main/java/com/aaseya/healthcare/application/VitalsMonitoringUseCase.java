package com.aaseya.healthcare.application;

import com.aaseya.camunda.framework.core.process.ProcessService;
import com.aaseya.camunda.framework.core.process.PublishMessageCommand;
import com.aaseya.healthcare.domain.VitalsReading;
import com.aaseya.healthcare.domain.VitalsTrend;
import com.aaseya.healthcare.domain.VitalsAssessment;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Takes an observation and, on a threshold breach, correlates a {@code VitalsAlert} message
 * back into the running instance — the message-correlation behaviour the assessment asks the
 * vitals worker to demonstrate.
 *
 * <p>Readings are derived from {@code priorAttempts} (how many discharge-readiness checks have
 * already failed) rather than from a counter held in the worker. An in-memory counter made the
 * outcome depend on JVM lifetime, produced a permanently deteriorating patient, and therefore
 * an unterminating revise-plan loop. Deriving from process state makes each execution
 * reproducible, safe to retry, and guarantees the loop converges.
 */
public class VitalsMonitoringUseCase {

    private final ProcessService processService;

    /**
     * @param processService framework port used to publish the alert message
     */
    public VitalsMonitoringUseCase(ProcessService processService) {
        this.processService = processService;
    }

    /**
     * Outcome of one monitoring pass.
     *
     * @param trend    the trend to write back to the process
     * @param breached whether an alert was raised
     * @param reading  the observation taken
     */
    public record Outcome(VitalsTrend trend, boolean breached, VitalsReading reading) {
    }

    /**
     * Observes the patient and raises an alert if a threshold is breached.
     *
     * @param patientId     patient under observation
     * @param caseId        correlation key for the alert message
     * @param priorAttempts failed discharge-readiness checks so far
     * @return the trend and whether an alert was raised
     */
    public Outcome monitor(String patientId, String caseId, int priorAttempts) {
        VitalsReading reading = observe(patientId, caseId, priorAttempts);
        VitalsTrend trend = VitalsAssessment.trendFor(reading, priorAttempts);
        boolean breached = VitalsAssessment.isBreached(reading);

        if (breached) {
            processService.publish(new PublishMessageCommand(
                    "VitalsAlert",
                    caseId,
                    Map.of("alertMessage", "Vitals threshold breached",
                            "vitalsTrend", trend.name()),
                    Duration.ofMinutes(10),
                    null));
        }

        return new Outcome(trend, breached, reading);
    }

    /**
     * Stands in for the bedside monitor feed. The first pass breaches so the alert path and the
     * revise-plan loop are both exercised; once the physician has revised the plan the patient
     * responds, which is what lets the loop terminate.
     */
    private VitalsReading observe(String patientId, String caseId, int priorAttempts) {
        if (priorAttempts == 0) {
            return new VitalsReading(patientId, caseId, 122.0, 85.0, 39.9, Instant.now());
        }
        return new VitalsReading(patientId, caseId, 78.0, 97.0, 36.9, Instant.now());
    }
}

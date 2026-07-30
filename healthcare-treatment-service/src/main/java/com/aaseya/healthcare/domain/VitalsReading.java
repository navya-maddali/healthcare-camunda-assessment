package com.aaseya.healthcare.domain;

import java.time.Instant;

/**
 * A single set of observations taken for a patient at a point in time.
 *
 * <p>This record carries data only. Whether a reading is clinically acceptable is decided
 * by {@link com.aaseya.healthcare.domain.VitalsAssessment}, so the thresholds live
 * in exactly one place rather than being duplicated into each worker.
 *
 * @param patientId   identifier of the patient observed
 * @param caseId      business key of the admission this reading belongs to
 * @param heartRate   heart rate in beats per minute
 * @param spO2        peripheral oxygen saturation as a percentage
 * @param temperature core body temperature in degrees Celsius
 * @param recordedAt  instant the observation was taken
 */
public record VitalsReading(
        String patientId,
        String caseId,
        double heartRate,
        double spO2,
        double temperature,
        Instant recordedAt) {
}

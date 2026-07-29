package com.aaseya.healthcare.domain.model;

/**
 * The clinical record archived when a patient is discharged.
 *
 * <p>This is the domain view of the case. The JPA entity that persists it lives in the
 * infrastructure layer, so the domain stays free of persistence annotations.
 *
 * @param caseId           business key of the admission; unique per archived case
 * @param patientId        hospital identifier for the patient
 * @param patientName      full name as captured at registration
 * @param carePlan         care pathway chosen by the triage decision
 * @param treatmentPlan    plan authored by the attending physician
 * @param dischargeSummary summary drafted by the discharge AI step
 * @param vitalsTrend      final trend reported by vitals monitoring
 */
public record PatientCaseRecord(
        String caseId,
        String patientId,
        String patientName,
        String carePlan,
        String treatmentPlan,
        String dischargeSummary,
        String vitalsTrend) {
}

package com.aaseya.healthcare.domain;

/**
 * Holds patient information for the healthcare treatment journey.
 *
 * <p>Field values mirror the {@code registration-form} select options and the input entries of
 * the {@code triage-care-pathway} decision table, so the form, the DMN and this record stay
 * describable as one contract.
 *
 * @param patientId      hospital identifier for the patient
 * @param patientName    full name as captured at registration
 * @param admissionType  {@code ER}, {@code REFERRAL} or {@code ELECTIVE}
 * @param chiefComplaint {@code CHEST_PAIN}, {@code RESPIRATORY}, {@code NEUROLOGICAL},
 *                       {@code METABOLIC}, {@code ORTHOPEDIC} or {@code GENERAL}
 * @param vitalsSeverity {@code CRITICAL}, {@code MODERATE} or {@code STABLE} at admission
 * @param caseId         business key of this admission, used for message correlation and
 *                       for idempotent archiving
 */
public record Patient(
        String patientId,
        String patientName,
        String admissionType,
        String chiefComplaint,
        String vitalsSeverity,
        String caseId) {
}

package com.aaseya.healthcare.domain;

import java.time.LocalDateTime;

/**
 * What a clinician actually submitted when they completed a human step.
 *
 * <p>Camunda already knows a task was completed, but its history is retention-bound and it does not
 * record who pressed the button in this service's terms. Keeping our own row means the audit of a
 * case survives independently of the engine — the same reason {@link PatientCaseRecord} exists
 * rather than the journey being reconstructed from Operate.
 *
 * @param caseId             business key of the admission, {@code null} when the instance no longer
 *                           carries one
 * @param processInstanceKey engine key of the journey the task belonged to
 * @param userTaskKey        engine key of the completed task
 * @param elementId          BPMN element id, stable across instances
 * @param taskName           human-readable task name as modelled
 * @param completedBy        who completed it, as supplied by the caller; {@code null} when omitted
 * @param variables          the submitted form variables, serialised as JSON
 * @param completedAt        when this service completed the task
 */
public record CaseTaskOutcomeRecord(
        String caseId,
        long processInstanceKey,
        long userTaskKey,
        String elementId,
        String taskName,
        String completedBy,
        String variables,
        LocalDateTime completedAt) {
}

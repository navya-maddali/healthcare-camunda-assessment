package com.aaseya.healthcare.web.dto;

import com.aaseya.healthcare.domain.CaseTaskOutcomeRecord;

/**
 * Read model for one completed human step.
 *
 * <p>{@code completedAt} is rendered as a string so the audit trail reads the same regardless of how
 * the caller's JSON parser handles dates.
 *
 * @param userTaskKey engine key of the completed task
 * @param elementId   BPMN element id
 * @param taskName    task name as modelled
 * @param completedBy who completed it, or {@code null} when the caller did not say
 * @param variables   the submitted form variables, as JSON
 * @param completedAt when this service completed the task
 */
public record TaskOutcomeView(
        long userTaskKey,
        String elementId,
        String taskName,
        String completedBy,
        String variables,
        String completedAt) {

    /**
     * @param record persisted outcome
     * @return the view of it
     */
    public static TaskOutcomeView from(CaseTaskOutcomeRecord record) {
        return new TaskOutcomeView(
                record.userTaskKey(),
                record.elementId(),
                record.taskName(),
                record.completedBy(),
                record.variables(),
                record.completedAt() == null ? null : record.completedAt().toString());
    }
}

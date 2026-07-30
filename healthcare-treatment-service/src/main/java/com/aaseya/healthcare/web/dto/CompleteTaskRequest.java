package com.aaseya.healthcare.web.dto;

import java.util.Map;

/**
 * Body accepted by every task-completion endpoint.
 *
 * <p>Both fields are optional and the body itself may be omitted, so a task with no form data is
 * completed with a bare {@code POST}.
 *
 * @param completedBy who is completing the step; recorded in the audit trail, not sent to the engine
 * @param variables   values captured on the task form, merged into the process instance
 */
public record CompleteTaskRequest(String completedBy, Map<String, Object> variables) {

    /** @return the submitted variables, or an empty map when absent */
    public Map<String, Object> variablesOrEmpty() {
        return variables == null ? Map.of() : variables;
    }

    /**
     * @param request a body that may itself be {@code null}
     * @return the variables it carries, treating an absent body and an absent field alike
     */
    public static Map<String, Object> variablesOf(CompleteTaskRequest request) {
        return request == null ? Map.of() : request.variablesOrEmpty();
    }

    /**
     * @param request a body that may itself be {@code null}
     * @return who completed the step, or {@code null}
     */
    public static String completedByOf(CompleteTaskRequest request) {
        return request == null ? null : request.completedBy();
    }
}

package com.aaseya.healthcare.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * JPA mapping for one completed human step.
 *
 * <p>Sits beside {@link PatientCaseEntity} for the same reason: JPA annotations are permitted in the
 * domain, so the aggregate and its mapping stay in one place.
 *
 * <p>The schema is owned by Flyway ({@code V3__case_task_outcome.sql}); Hibernate runs in
 * {@code validate} mode and will fail fast if the two drift.
 */
@Entity
@Table(name = "case_task_outcome")
public class CaseTaskOutcomeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id")
    private String caseId;

    @Column(name = "process_instance_key", nullable = false)
    private long processInstanceKey;

    @Column(name = "user_task_key", nullable = false)
    private long userTaskKey;

    @Column(name = "element_id")
    private String elementId;

    @Column(name = "task_name")
    private String taskName;

    @Column(name = "completed_by")
    private String completedBy;

    /**
     * The submitted form variables, as JSON. {@code TEXT} rather than {@code jsonb}: nothing queries
     * inside this column, and a JSON column type would need a Hibernate mapping that
     * {@code ddl-auto: validate} would then have to agree with.
     */
    @Column(name = "variables", columnDefinition = "TEXT")
    private String variables;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }

    public long getProcessInstanceKey() { return processInstanceKey; }
    public void setProcessInstanceKey(long processInstanceKey) { this.processInstanceKey = processInstanceKey; }

    public long getUserTaskKey() { return userTaskKey; }
    public void setUserTaskKey(long userTaskKey) { this.userTaskKey = userTaskKey; }

    public String getElementId() { return elementId; }
    public void setElementId(String elementId) { this.elementId = elementId; }

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }

    public String getCompletedBy() { return completedBy; }
    public void setCompletedBy(String completedBy) { this.completedBy = completedBy; }

    public String getVariables() { return variables; }
    public void setVariables(String variables) { this.variables = variables; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

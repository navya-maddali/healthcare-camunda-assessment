package com.aaseya.healthcare.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * JPA mapping for an archived case.
 *
 * <p>The persisted case aggregate. JPA annotations are allowed here — the architecture rule the
 * domain must satisfy forbids Spring Web, Servlet and Camunda types, not {@code jakarta.persistence}
 * — so the aggregate and its mapping stay in one place rather than being mirrored across layers.
 *
 * <p>The schema is owned by Flyway ({@code V2__patient_case.sql}); Hibernate runs in
 * {@code validate} mode and will fail fast if the two drift.
 */
@Entity
@Table(name = "patient_case")
public class PatientCaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", unique = true, nullable = false)
    private String caseId;

    @Column(name = "patient_id")
    private String patientId;

    @Column(name = "patient_name")
    private String patientName;

    @Column(name = "care_plan")
    private String carePlan;

    @Column(name = "treatment_plan", columnDefinition = "TEXT")
    private String treatmentPlan;

    @Column(name = "discharge_summary", columnDefinition = "TEXT")
    private String dischargeSummary;

    @Column(name = "vitals_trend")
    private String vitalsTrend;

    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    /** Stamps the archive time immediately before the first insert. */
    @PrePersist
    void stampArchivedAt() {
        this.archivedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }

    public String getPatientId() { return patientId; }
    public void setPatientId(String patientId) { this.patientId = patientId; }

    public String getPatientName() { return patientName; }
    public void setPatientName(String patientName) { this.patientName = patientName; }

    public String getCarePlan() { return carePlan; }
    public void setCarePlan(String carePlan) { this.carePlan = carePlan; }

    public String getTreatmentPlan() { return treatmentPlan; }
    public void setTreatmentPlan(String treatmentPlan) { this.treatmentPlan = treatmentPlan; }

    public String getDischargeSummary() { return dischargeSummary; }
    public void setDischargeSummary(String dischargeSummary) { this.dischargeSummary = dischargeSummary; }

    public String getVitalsTrend() { return vitalsTrend; }
    public void setVitalsTrend(String vitalsTrend) { this.vitalsTrend = vitalsTrend; }

    public LocalDateTime getArchivedAt() { return archivedAt; }
}

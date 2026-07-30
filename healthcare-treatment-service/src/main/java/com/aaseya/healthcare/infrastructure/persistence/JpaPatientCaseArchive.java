package com.aaseya.healthcare.infrastructure.persistence;

import com.aaseya.healthcare.application.PatientCaseArchive;
import com.aaseya.healthcare.domain.PatientCaseEntity;
import com.aaseya.healthcare.domain.PatientCaseRecord;
import com.aaseya.healthcare.repository.PatientCaseJpaRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter implementing the {@link PatientCaseArchive} outbound port.
 */
@Repository
public class JpaPatientCaseArchive implements PatientCaseArchive {

    private final PatientCaseJpaRepository repository;

    /**
     * @param repository Spring Data repository over the {@code patient_case} table
     */
    public JpaPatientCaseArchive(PatientCaseJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean existsByCaseId(String caseId) {
        return repository.existsByCaseId(caseId);
    }

    @Override
    @Transactional
    public void save(PatientCaseRecord record) {
        PatientCaseEntity entity = new PatientCaseEntity();
        entity.setCaseId(record.caseId());
        entity.setPatientId(record.patientId());
        entity.setPatientName(record.patientName());
        entity.setCarePlan(record.carePlan());
        entity.setTreatmentPlan(record.treatmentPlan());
        entity.setDischargeSummary(record.dischargeSummary());
        entity.setVitalsTrend(record.vitalsTrend());
        repository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PatientCaseRecord> findByCaseId(String caseId) {
        return repository.findByCaseId(caseId).map(e -> new PatientCaseRecord(
                e.getCaseId(),
                e.getPatientId(),
                e.getPatientName(),
                e.getCarePlan(),
                e.getTreatmentPlan(),
                e.getDischargeSummary(),
                e.getVitalsTrend()));
    }
}

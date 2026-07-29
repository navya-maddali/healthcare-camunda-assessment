package com.aaseya.healthcare.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository over {@link PatientCaseEntity}.
 *
 * <p>Not exposed beyond the infrastructure layer — {@link JpaPatientCaseArchive} adapts it to the
 * {@code PatientCaseArchive} port so the application layer never sees Spring Data.
 */
public interface PatientCaseJpaRepository extends JpaRepository<PatientCaseEntity, Long> {

    /**
     * @param caseId business key of the admission
     * @return {@code true} when a record already exists for this case
     */
    boolean existsByCaseId(String caseId);
}

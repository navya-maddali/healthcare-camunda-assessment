package com.aaseya.healthcare.repository;

import com.aaseya.healthcare.domain.PatientCaseEntity;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository over {@link PatientCaseEntity}.
 *
 * <p>Not exposed to the application layer — {@code JpaPatientCaseArchive} adapts it to the
 * {@code PatientCaseArchive} port so nothing above infrastructure sees Spring Data.
 */
public interface PatientCaseJpaRepository extends JpaRepository<PatientCaseEntity, Long> {

    /**
     * @param caseId business key of the admission
     * @return {@code true} when a record already exists for this case
     */
    boolean existsByCaseId(String caseId);

    /**
     * @param caseId business key of the admission
     * @return the archived row, or empty when the case has not been archived
     */
    Optional<PatientCaseEntity> findByCaseId(String caseId);
}

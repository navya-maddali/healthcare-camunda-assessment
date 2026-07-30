package com.aaseya.healthcare.application;

import com.aaseya.healthcare.domain.PatientCaseRecord;
import java.util.Optional;

/**
 * Outbound port for long-term storage of discharged cases.
 *
 * <p>The application layer depends on this interface; the JPA adapter that implements it lives
 * in {@code infrastructure.persistence}, so use cases stay testable without a database.
 */
public interface PatientCaseArchive {

    /**
     * Reports whether a case has already been archived.
     *
     * @param caseId business key of the admission
     * @return {@code true} when a record already exists for this case
     */
    boolean existsByCaseId(String caseId);

    /**
     * Persists the archived case.
     *
     * @param record the case to store
     */
    void save(PatientCaseRecord record);

    /**
     * Reads back an archived case.
     *
     * <p>This is what makes the archive verifiable over HTTP: the record is written by
     * {@code RecordArchiveWorker} at the end of the journey, and reading it confirms the process
     * reached its terminal state and the write actually landed in the database.
     *
     * @param caseId business key of the admission
     * @return the archived record, or empty when the case has not been archived
     */
    Optional<PatientCaseRecord> findByCaseId(String caseId);
}

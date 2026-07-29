package com.aaseya.healthcare.application.port;

import com.aaseya.healthcare.domain.model.PatientCaseRecord;

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
}

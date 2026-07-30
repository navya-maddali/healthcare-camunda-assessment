package com.aaseya.healthcare.application;

import com.aaseya.healthcare.domain.CaseTaskOutcomeRecord;
import java.util.List;

/**
 * Outbound port for the audit trail of completed human steps.
 *
 * <p>Sibling of {@link PatientCaseArchive}: the application layer depends on this interface and the
 * JPA adapter in {@code infrastructure.persistence} implements it, so the use case stays testable
 * without a database.
 */
public interface CaseTaskOutcomeArchive {

    /**
     * Records one completed human step.
     *
     * @param record what was submitted, and by whom
     */
    void save(CaseTaskOutcomeRecord record);

    /**
     * @param processInstanceKey journey to read the audit trail for
     * @return every step completed through this service, oldest first
     */
    List<CaseTaskOutcomeRecord> findByProcessInstanceKey(long processInstanceKey);
}

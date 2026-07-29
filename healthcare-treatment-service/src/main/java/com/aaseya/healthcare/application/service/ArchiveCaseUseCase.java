package com.aaseya.healthcare.application.service;

import com.aaseya.healthcare.application.port.PatientCaseArchive;
import com.aaseya.healthcare.domain.model.PatientCaseRecord;

/**
 * Archives a discharged case.
 *
 * <p>Archiving is idempotent at two levels: the framework's {@code IdempotencyGuard} short-circuits
 * a replayed job before the worker runs, and this use case still checks for an existing record
 * before inserting. The second check matters because the guard only engages when a
 * {@code businessKey} is in scope, and because a unique constraint on {@code case_id} is the only
 * thing that holds under concurrent retries.
 */
public class ArchiveCaseUseCase {

    private final PatientCaseArchive archive;

    /**
     * @param archive outbound port to the case store
     */
    public ArchiveCaseUseCase(PatientCaseArchive archive) {
        this.archive = archive;
    }

    /**
     * Result of an archive attempt.
     *
     * @param referenceId       reference returned to the process
     * @param alreadyArchived   {@code true} when a record already existed
     */
    public record Outcome(String referenceId, boolean alreadyArchived) {
    }

    /**
     * Stores the case unless it is already present.
     *
     * @param record the case to archive
     * @return the archive reference and whether this call was a no-op
     */
    public Outcome archive(PatientCaseRecord record) {
        String referenceId = "ARCHIVED-" + record.caseId();

        if (archive.existsByCaseId(record.caseId())) {
            return new Outcome(referenceId, true);
        }

        archive.save(record);
        return new Outcome(referenceId, false);
    }
}

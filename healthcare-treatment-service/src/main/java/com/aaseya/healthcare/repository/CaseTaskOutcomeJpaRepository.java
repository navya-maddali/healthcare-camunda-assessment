package com.aaseya.healthcare.repository;

import com.aaseya.healthcare.domain.CaseTaskOutcomeEntity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository over {@link CaseTaskOutcomeEntity}.
 *
 * <p>Not exposed to the application layer — {@code JpaCaseTaskOutcomeArchive} adapts it to the
 * {@code CaseTaskOutcomeArchive} port so nothing above infrastructure sees Spring Data.
 */
public interface CaseTaskOutcomeJpaRepository extends JpaRepository<CaseTaskOutcomeEntity, Long> {

    /**
     * @param processInstanceKey journey to read the audit trail for
     * @return completed steps in the order they were completed
     */
    List<CaseTaskOutcomeEntity> findByProcessInstanceKeyOrderByCreatedAtAsc(long processInstanceKey);
}

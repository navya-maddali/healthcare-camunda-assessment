package com.aaseya.healthcare.infrastructure.persistence;

import com.aaseya.healthcare.application.CaseTaskOutcomeArchive;
import com.aaseya.healthcare.domain.CaseTaskOutcomeEntity;
import com.aaseya.healthcare.domain.CaseTaskOutcomeRecord;
import com.aaseya.healthcare.repository.CaseTaskOutcomeJpaRepository;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter implementing the {@link CaseTaskOutcomeArchive} outbound port.
 */
@Repository
public class JpaCaseTaskOutcomeArchive implements CaseTaskOutcomeArchive {

    private final CaseTaskOutcomeJpaRepository repository;

    /**
     * @param repository Spring Data repository over the {@code case_task_outcome} table
     */
    public JpaCaseTaskOutcomeArchive(CaseTaskOutcomeJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(CaseTaskOutcomeRecord record) {
        CaseTaskOutcomeEntity entity = new CaseTaskOutcomeEntity();
        entity.setCaseId(record.caseId());
        entity.setProcessInstanceKey(record.processInstanceKey());
        entity.setUserTaskKey(record.userTaskKey());
        entity.setElementId(record.elementId());
        entity.setTaskName(record.taskName());
        entity.setCompletedBy(record.completedBy());
        entity.setVariables(record.variables());
        entity.setCreatedAt(record.completedAt());
        repository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CaseTaskOutcomeRecord> findByProcessInstanceKey(long processInstanceKey) {
        return repository.findByProcessInstanceKeyOrderByCreatedAtAsc(processInstanceKey).stream()
                .map(e -> new CaseTaskOutcomeRecord(
                        e.getCaseId(),
                        e.getProcessInstanceKey(),
                        e.getUserTaskKey(),
                        e.getElementId(),
                        e.getTaskName(),
                        e.getCompletedBy(),
                        e.getVariables(),
                        e.getCreatedAt()))
                .toList();
    }
}

package com.aaseya.healthcare.application;

import com.aaseya.healthcare.application.PatientCaseArchive;
import com.aaseya.healthcare.application.ProcessOrchestrationPort;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.DecisionOutcome;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.ElementRef;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.InstanceState;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.JourneyIncident;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.JourneyTask;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.StartedInstance;
import com.aaseya.healthcare.domain.CaseTaskOutcomeRecord;
import com.aaseya.healthcare.domain.PatientCaseRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Drives a treatment journey from the service side.
 *
 * <p>Everything here is expressed in terms the caller already knows — a case id, a BPMN element id
 * — rather than engine keys. Resolving a key from an element id is the job this class exists to do;
 * without it a caller would have to search for a task, read its key out of the response and feed it
 * back in, which is engine plumbing rather than clinical intent.
 *
 * <p>A plain constructor-injected object, matching {@link ArchiveCaseUseCase} and the other use
 * cases; Spring stereotypes stay out of the application layer.
 */
public class TreatmentJourneyUseCase {

    /** BPMN process id of the deployed journey. */
    public static final String PROCESS_ID = "healthcare-treatment-journey";

    /** Message correlated into the non-interrupting event sub-process. */
    public static final String VITALS_ALERT_MESSAGE = "VitalsAlert";

    private final ProcessOrchestrationPort orchestration;
    private final PatientCaseArchive archive;
    private final CaseTaskOutcomeArchive outcomes;
    private final ObjectMapper objectMapper;

    /**
     * @param orchestration outbound port to the workflow engine
     * @param archive       outbound port to the case store
     * @param outcomes      outbound port to the human-step audit trail
     * @param objectMapper  serialises submitted variables for the audit trail
     */
    public TreatmentJourneyUseCase(
            ProcessOrchestrationPort orchestration,
            PatientCaseArchive archive,
            CaseTaskOutcomeArchive outcomes,
            ObjectMapper objectMapper) {
        this.orchestration = orchestration;
        this.archive = archive;
        this.outcomes = outcomes;
        this.objectMapper = objectMapper;
    }

    /** Raised when a caller names an element or task key that is not currently waiting. */
    public static class ElementNotActiveException extends RuntimeException {
        /**
         * @param message description naming the element and instance
         */
        public ElementNotActiveException(String message) {
            super(message);
        }
    }

    /**
     * Raised when a caller asks to complete "the" waiting task and the journey has more than one.
     *
     * <p>Distinct from {@link ElementNotActiveException} because the fix differs: here the caller
     * must name a task, there they named one that has already moved on. Both are 409 — the request
     * is well-formed, the journey is simply not in a state that can satisfy it.
     */
    public static class AmbiguousTaskException extends RuntimeException {
        /**
         * @param message description naming the instance and the tasks in contention
         */
        public AmbiguousTaskException(String message) {
            super(message);
        }
    }

    /**
     * Admits a patient and starts the journey.
     *
     * @param caseId    business key; generated when blank so callers may omit it
     * @param variables clinical variables captured at admission
     * @return keys identifying the new instance
     */
    public StartedInstance admit(String caseId, Map<String, Object> variables) {
        String resolved = (caseId == null || caseId.isBlank())
                ? "CASE-" + Long.toString(System.nanoTime(), 36).toUpperCase()
                : caseId;

        Map<String, Object> payload = new LinkedHashMap<>();
        if (variables != null) {
            payload.putAll(variables);
        }
        payload.put("caseId", resolved);

        return orchestration.startJourney(PROCESS_ID, payload);
    }

    /**
     * Completes the human step currently waiting at the given element.
     *
     * @param processInstanceKey instance to advance
     * @param elementId          BPMN element id of the waiting task
     * @param variables          variables captured on the form
     * @return the task that was completed
     * @throws ElementNotActiveException when no task is waiting at that element
     */
    public JourneyTask completeStep(long processInstanceKey, String elementId, Map<String, Object> variables) {
        return completeStep(processInstanceKey, elementId, null, variables);
    }

    /**
     * Completes the human step currently waiting at the given element, recording who did it.
     *
     * @param processInstanceKey instance to advance
     * @param elementId          BPMN element id of the waiting task
     * @param completedBy        who is completing the step, or {@code null}
     * @param variables          variables captured on the form
     * @return the task that was completed
     * @throws ElementNotActiveException when no task is waiting at that element
     */
    public JourneyTask completeStep(
            long processInstanceKey, String elementId, String completedBy, Map<String, Object> variables) {

        JourneyTask task = orchestration.activeTasks(processInstanceKey).stream()
                .filter(t -> t.elementId().equals(elementId))
                .findFirst()
                .orElseThrow(() -> new ElementNotActiveException(
                        "No user task waiting at '" + elementId + "' on instance " + processInstanceKey));

        return complete(processInstanceKey, task, completedBy, variables);
    }

    /**
     * Completes a human step by engine key, scoped to the journey it belongs to.
     *
     * <p>The scoped form is the one to prefer: it proves the key is waiting on <em>this</em> instance
     * before completing anything, so a key copied from another case fails loudly instead of quietly
     * advancing someone else's journey. {@link #completeTask(long, Map)} keeps the unscoped
     * behaviour for callers that hold a key and nothing else.
     *
     * @param processInstanceKey instance the task must belong to
     * @param userTaskKey        task to complete
     * @param completedBy        who is completing the step, or {@code null}
     * @param variables          variables captured on the form
     * @return the task that was completed
     * @throws ElementNotActiveException when that key is not waiting on that instance
     */
    public JourneyTask completeTaskOnInstance(
            long processInstanceKey, long userTaskKey, String completedBy, Map<String, Object> variables) {

        JourneyTask task = orchestration.activeTasks(processInstanceKey).stream()
                .filter(t -> t.userTaskKey() == userTaskKey)
                .findFirst()
                .orElseThrow(() -> new ElementNotActiveException(
                        "No user task " + userTaskKey + " waiting on instance " + processInstanceKey));

        return complete(processInstanceKey, task, completedBy, variables);
    }

    /**
     * Completes the one human step a journey is waiting on, without needing to name it.
     *
     * <p>Convenience for the single-task stretches of the journey, which is most of it. During the
     * specialist-consultation phase several tasks wait at once and this deliberately refuses to
     * guess.
     *
     * @param processInstanceKey instance to advance
     * @param completedBy        who is completing the step, or {@code null}
     * @param variables          variables captured on the form
     * @return the task that was completed
     * @throws ElementNotActiveException when nothing is waiting
     * @throws AmbiguousTaskException    when more than one task is waiting
     */
    public JourneyTask completeOnlyWaitingTask(
            long processInstanceKey, String completedBy, Map<String, Object> variables) {

        List<JourneyTask> waiting = orchestration.activeTasks(processInstanceKey);
        if (waiting.isEmpty()) {
            throw new ElementNotActiveException(
                    "No user task waiting on instance " + processInstanceKey);
        }
        if (waiting.size() > 1) {
            throw new AmbiguousTaskException("Instance " + processInstanceKey + " has "
                    + waiting.size() + " tasks waiting (" + elementIdsOf(waiting)
                    + "); complete one by element id or user task key");
        }
        return complete(processInstanceKey, waiting.get(0), completedBy, variables);
    }

    /**
     * Completes a human step by engine key, unscoped.
     *
     * @param userTaskKey task to complete
     * @param variables   variables captured on the form
     */
    public void completeTask(long userTaskKey, Map<String, Object> variables) {
        orchestration.completeTask(userTaskKey, variables);
    }

    /**
     * @param processInstanceKey journey to read the audit trail for
     * @return every human step completed through this service, oldest first
     */
    public List<CaseTaskOutcomeRecord> taskOutcomes(long processInstanceKey) {
        return outcomes.findByProcessInstanceKey(processInstanceKey);
    }

    /**
     * Completes a resolved task and records what was submitted.
     *
     * <p>The case id is read before the task is completed, not after: completing may finish the
     * instance outright, and the engine stops answering variable queries for it once it has.
     */
    private JourneyTask complete(
            long processInstanceKey, JourneyTask task, String completedBy, Map<String, Object> variables) {

        String caseId = caseIdOf(processInstanceKey);
        orchestration.completeTask(task.userTaskKey(), variables);

        outcomes.save(new CaseTaskOutcomeRecord(
                caseId,
                processInstanceKey,
                task.userTaskKey(),
                task.elementId(),
                task.name(),
                completedBy,
                toJson(variables),
                LocalDateTime.now()));

        return task;
    }

    /**
     * Best-effort read of the business key. The audit row is worth writing even when the engine is
     * mid-transition and will not answer, so a failure here degrades the row rather than the request.
     */
    private String caseIdOf(long processInstanceKey) {
        try {
            Object value = orchestration.variables(processInstanceKey).get("caseId");
            return value == null ? null : value.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String toJson(Map<String, Object> variables) {
        try {
            return objectMapper.writeValueAsString(variables == null ? Map.of() : variables);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String elementIdsOf(List<JourneyTask> tasks) {
        return String.join(", ", tasks.stream().map(JourneyTask::elementId).toList());
    }

    /**
     * Steps a journey past an element it is stuck on.
     *
     * <p>The element being left is terminated and the target activated in one modification, so the
     * instance never exists in both places. Supplying {@code fromElementId} is optional: when it is
     * absent the target is simply activated alongside whatever is already running.
     *
     * @param processInstanceKey instance to modify
     * @param fromElementId      element to terminate, or {@code null} to activate only
     * @param toElementId        element to activate
     * @param variables          variables to set on the activated scope
     * @throws ElementNotActiveException when {@code fromElementId} is not currently active
     */
    public void divert(
            long processInstanceKey,
            String fromElementId,
            String toElementId,
            Map<String, Object> variables) {

        List<Long> terminate = List.of();
        if (fromElementId != null && !fromElementId.isBlank()) {
            ElementRef source = orchestration.activeElements(processInstanceKey).stream()
                    .filter(e -> e.elementId().equals(fromElementId))
                    .findFirst()
                    .orElseThrow(() -> new ElementNotActiveException(
                            "Element '" + fromElementId + "' is not active on instance " + processInstanceKey));
            terminate = List.of(source.elementInstanceKey());
        }

        orchestration.moveTo(processInstanceKey, toElementId, terminate, variables);
    }

    /**
     * Raises a vitals alert against a case.
     *
     * @param caseId    correlation key of the running journey
     * @param variables alert payload
     */
    public void raiseVitalsAlert(String caseId, Map<String, Object> variables) {
        orchestration.publishMessage(VITALS_ALERT_MESSAGE, caseId, variables);
    }

    /**
     * @param processInstanceKey instance to inspect
     * @return lifecycle state, or empty while the engine is still indexing it
     */
    public Optional<InstanceState> state(long processInstanceKey) {
        return orchestration.instanceState(processInstanceKey);
    }

    /**
     * @param processInstanceKey instance to inspect
     * @return user tasks currently waiting
     */
    public List<JourneyTask> waitingTasks(long processInstanceKey) {
        return orchestration.activeTasks(processInstanceKey);
    }

    /**
     * @param processInstanceKey instance to inspect
     * @return every process variable
     */
    public Map<String, Object> variables(long processInstanceKey) {
        return orchestration.variables(processInstanceKey);
    }

    /**
     * @param processInstanceKey instance to inspect
     * @return incidents raised against the instance
     */
    public List<JourneyIncident> incidents(long processInstanceKey) {
        return orchestration.incidents(processInstanceKey);
    }

    /**
     * @param processInstanceKey instance to inspect
     * @return element instances currently active
     */
    public List<ElementRef> activeElements(long processInstanceKey) {
        return orchestration.activeElements(processInstanceKey);
    }

    /**
     * Cancels a journey.
     *
     * @param processInstanceKey instance to cancel
     */
    public void cancel(long processInstanceKey) {
        orchestration.cancel(processInstanceKey);
    }

    /**
     * Evaluates a decision table directly, without running a process.
     *
     * @param decisionId decision id as deployed
     * @param variables  decision inputs
     * @return the decision output and matched rules
     */
    public DecisionOutcome evaluate(String decisionId, Map<String, Object> variables) {
        return orchestration.evaluateDecision(decisionId, variables);
    }

    /**
     * @param caseId business key of the admission
     * @return the archived record once the journey has reached the archive step
     */
    public Optional<PatientCaseRecord> archived(String caseId) {
        return archive.findByCaseId(caseId);
    }
}

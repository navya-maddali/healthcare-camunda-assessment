package com.aaseya.healthcare.application;

import com.aaseya.healthcare.application.PatientCaseArchive;
import com.aaseya.healthcare.application.ProcessOrchestrationPort;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.DecisionOutcome;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.ElementRef;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.InstanceState;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.JourneyIncident;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.JourneyTask;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.StartedInstance;
import com.aaseya.healthcare.domain.PatientCaseRecord;
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

    /**
     * @param orchestration outbound port to the workflow engine
     * @param archive       outbound port to the case store
     */
    public TreatmentJourneyUseCase(ProcessOrchestrationPort orchestration, PatientCaseArchive archive) {
        this.orchestration = orchestration;
        this.archive = archive;
    }

    /** Raised when a caller names an element that is not currently waiting. */
    public static class ElementNotActiveException extends RuntimeException {
        /**
         * @param message description naming the element and instance
         */
        public ElementNotActiveException(String message) {
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
        JourneyTask task = orchestration.activeTasks(processInstanceKey).stream()
                .filter(t -> t.elementId().equals(elementId))
                .findFirst()
                .orElseThrow(() -> new ElementNotActiveException(
                        "No user task waiting at '" + elementId + "' on instance " + processInstanceKey));

        orchestration.completeTask(task.userTaskKey(), variables);
        return task;
    }

    /**
     * Completes a human step by engine key, for callers that already hold one.
     *
     * @param userTaskKey task to complete
     * @param variables   variables captured on the form
     */
    public void completeTask(long userTaskKey, Map<String, Object> variables) {
        orchestration.completeTask(userTaskKey, variables);
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

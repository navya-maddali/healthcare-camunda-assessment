package com.aaseya.healthcare.web;

import com.aaseya.healthcare.application.ProcessOrchestrationPort.DecisionOutcome;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.ElementRef;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.InstanceState;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.JourneyIncident;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.JourneyTask;
import com.aaseya.healthcare.application.ProcessOrchestrationPort.StartedInstance;
import com.aaseya.healthcare.application.TreatmentJourneyUseCase;
import com.aaseya.healthcare.domain.PatientCaseRecord;
import com.aaseya.healthcare.web.dto.CompleteTaskRequest;
import com.aaseya.healthcare.web.dto.TaskOutcomeView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound HTTP adapter over {@link TreatmentJourneyUseCase}.
 *
 * <p>The journey is started and steered through this API; the job workers remain the engine's way
 * of calling back into the service. Together they make this service the single entry point for the
 * journey, rather than requiring a caller to address the engine directly.
 *
 * <p>Holds no logic of its own — it binds HTTP to the use case and lets
 * {@link ApiExceptionHandler} translate failures.
 */
@RestController
@RequestMapping("/api/v1")
public class TreatmentJourneyController {

    private final TreatmentJourneyUseCase journey;

    /**
     * @param journey application service driving the journey
     */
    public TreatmentJourneyController(TreatmentJourneyUseCase journey) {
        this.journey = journey;
    }

    /**
     * Admission payload.
     *
     * @param caseId    business key; generated when omitted
     * @param variables clinical variables captured at admission
     */
    public record AdmitRequest(String caseId, Map<String, Object> variables) {
    }

    /**
     * Variables submitted with a form.
     *
     * @param variables values captured on the task form
     */
    public record VariablesRequest(Map<String, Object> variables) {
    }

    /**
     * Request to step past a blocked element.
     *
     * @param fromElementId element to terminate, optional
     * @param toElementId   element to activate
     * @param variables     variables to set on the activated scope
     */
    public record DivertRequest(
            String fromElementId,
            @NotBlank(message = "toElementId is required") String toElementId,
            Map<String, Object> variables) {
    }

    /**
     * Starts a journey.
     *
     * @param request admission payload, may be absent entirely
     * @return keys identifying the new instance
     */
    @PostMapping("/cases")
    @ResponseStatus(HttpStatus.CREATED)
    public StartedInstance admit(@RequestBody(required = false) AdmitRequest request) {
        AdmitRequest safe = request == null ? new AdmitRequest(null, Map.of()) : request;
        return journey.admit(safe.caseId(), safe.variables());
    }

    /**
     * @param key instance key
     * @return lifecycle state, or 404 while the engine has not indexed the instance
     */
    @GetMapping("/cases/{key}")
    public ResponseEntity<InstanceState> state(@PathVariable long key) {
        return journey.state(key)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * @param key instance key
     * @return user tasks currently waiting
     */
    @GetMapping("/cases/{key}/tasks")
    public List<JourneyTask> tasks(@PathVariable long key) {
        return journey.waitingTasks(key);
    }

    /**
     * Completes the single task a journey is waiting on.
     *
     * <p>Saves the caller a lookup for the long single-task stretches of the journey. Refuses rather
     * than guesses while the specialist consultations run in parallel.
     *
     * @param key     instance key
     * @param request variables and {@code completedBy}, may be absent
     * @return the task that was completed
     */
    @PostMapping("/cases/{key}/tasks/completion")
    public JourneyTask completeOnlyTask(
            @PathVariable long key,
            @RequestBody(required = false) CompleteTaskRequest request) {
        return journey.completeOnlyWaitingTask(
                key, CompleteTaskRequest.completedByOf(request), CompleteTaskRequest.variablesOf(request));
    }

    /**
     * Completes a waiting task, addressed either by BPMN element id or by engine key.
     *
     * <p>One route rather than two because both address the same thing and Spring cannot map the
     * same URI template twice. An all-digits segment is a user task key; anything else is an element
     * id. The two never collide — every element id in this process is of the form {@code Task_*}.
     *
     * <p>Completing by key here is scoped to {@code key}: a task belonging to another journey is
     * rejected rather than silently completed.
     *
     * @param key       instance key
     * @param idOrKey   BPMN element id, or the user task key
     * @param request   variables and {@code completedBy}, may be absent
     * @return the task that was completed
     */
    @PostMapping("/cases/{key}/tasks/{idOrKey}/completion")
    public JourneyTask completeStep(
            @PathVariable long key,
            @PathVariable String idOrKey,
            @RequestBody(required = false) CompleteTaskRequest request) {

        String completedBy = CompleteTaskRequest.completedByOf(request);
        Map<String, Object> variables = CompleteTaskRequest.variablesOf(request);

        if (isUserTaskKey(idOrKey)) {
            return journey.completeTaskOnInstance(key, Long.parseLong(idOrKey), completedBy, variables);
        }
        return journey.completeStep(key, idOrKey, completedBy, variables);
    }

    /**
     * @param key instance key
     * @return every human step completed through this API, oldest first
     */
    @GetMapping("/cases/{key}/tasks/outcomes")
    public List<TaskOutcomeView> taskOutcomes(@PathVariable long key) {
        return journey.taskOutcomes(key).stream().map(TaskOutcomeView::from).toList();
    }

    /**
     * A path segment is a user task key when it is all digits and fits a {@code long}. Element ids
     * in this process are never numeric, so nothing is ambiguous; a segment too long to be a key
     * falls through to the element-id path and fails there with a clear message.
     */
    private static boolean isUserTaskKey(String segment) {
        if (segment.isEmpty() || !segment.chars().allMatch(Character::isDigit)) {
            return false;
        }
        try {
            Long.parseLong(segment);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * @param key instance key
     * @return every process variable, JSON payloads parsed rather than escaped
     */
    @GetMapping("/cases/{key}/variables")
    public Map<String, Object> variables(@PathVariable long key) {
        return journey.variables(key);
    }

    /**
     * @param key instance key
     * @return incidents raised against the instance, resolved ones included
     */
    @GetMapping("/cases/{key}/incidents")
    public List<JourneyIncident> incidents(@PathVariable long key) {
        return journey.incidents(key);
    }

    /**
     * @param key instance key
     * @return element instances currently active
     */
    @GetMapping("/cases/{key}/elements")
    public List<ElementRef> elements(@PathVariable long key) {
        return journey.activeElements(key);
    }

    /**
     * Steps a journey past a blocked element.
     *
     * @param key     instance key
     * @param request elements to leave and enter, plus variables to seed
     */
    @PostMapping("/cases/{key}/diversion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void divert(@PathVariable long key, @Valid @RequestBody DivertRequest request) {
        journey.divert(key, request.fromElementId(), request.toElementId(), request.variables());
    }

    /**
     * Raises a vitals alert against a case.
     *
     * @param caseId  correlation key of the running journey
     * @param request alert payload, may be absent
     */
    @PostMapping("/cases/{caseId}/vitals-alerts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void raiseVitalsAlert(
            @PathVariable String caseId,
            @RequestBody(required = false) VariablesRequest request) {
        journey.raiseVitalsAlert(caseId, variablesOf(request));
    }

    /**
     * Cancels a journey.
     *
     * @param key instance key
     */
    @DeleteMapping("/cases/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable long key) {
        journey.cancel(key);
    }

    /**
     * Evaluates a decision table directly.
     *
     * @param decisionId decision id as deployed
     * @param request    decision inputs
     * @return decision output and matched rules
     */
    @PostMapping("/decisions/{decisionId}/evaluation")
    public DecisionOutcome evaluate(
            @PathVariable String decisionId,
            @RequestBody(required = false) VariablesRequest request) {
        return journey.evaluate(decisionId, variablesOf(request));
    }

    /**
     * Reads back an archived case from PostgreSQL.
     *
     * @param caseId business key of the admission
     * @return the archived record, or 404 when the journey has not archived yet
     */
    @GetMapping("/archive/{caseId}")
    public ResponseEntity<PatientCaseRecord> archived(@PathVariable String caseId) {
        return journey.archived(caseId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Treats an absent body and an absent {@code variables} field alike, as no variables. */
    private static Map<String, Object> variablesOf(VariablesRequest request) {
        if (request == null || request.variables() == null) {
            return Map.of();
        }
        return request.variables();
    }
}

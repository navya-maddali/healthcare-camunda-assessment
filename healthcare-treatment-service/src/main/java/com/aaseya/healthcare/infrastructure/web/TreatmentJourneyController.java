package com.aaseya.healthcare.infrastructure.web;

import com.aaseya.healthcare.application.port.ProcessOrchestrationPort.DecisionOutcome;
import com.aaseya.healthcare.application.port.ProcessOrchestrationPort.ElementRef;
import com.aaseya.healthcare.application.port.ProcessOrchestrationPort.InstanceState;
import com.aaseya.healthcare.application.port.ProcessOrchestrationPort.JourneyIncident;
import com.aaseya.healthcare.application.port.ProcessOrchestrationPort.JourneyTask;
import com.aaseya.healthcare.application.port.ProcessOrchestrationPort.StartedInstance;
import com.aaseya.healthcare.application.service.TreatmentJourneyUseCase;
import com.aaseya.healthcare.domain.model.PatientCaseRecord;
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
     * Completes the task waiting at an element.
     *
     * @param key       instance key
     * @param elementId BPMN element id of the waiting task
     * @param request   variables captured on the form, may be absent
     * @return the task that was completed
     */
    @PostMapping("/cases/{key}/tasks/{elementId}/completion")
    public JourneyTask completeStep(
            @PathVariable long key,
            @PathVariable String elementId,
            @RequestBody(required = false) VariablesRequest request) {
        return journey.completeStep(key, elementId, variablesOf(request));
    }

    /**
     * Completes a task by engine key.
     *
     * @param taskKey user task key
     * @param request variables captured on the form, may be absent
     */
    @PostMapping("/tasks/{taskKey}/completion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void completeTask(
            @PathVariable long taskKey,
            @RequestBody(required = false) VariablesRequest request) {
        journey.completeTask(taskKey, variablesOf(request));
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

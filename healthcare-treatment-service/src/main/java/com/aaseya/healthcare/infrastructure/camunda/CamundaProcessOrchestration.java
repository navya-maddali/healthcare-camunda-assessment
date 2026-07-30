package com.aaseya.healthcare.infrastructure.camunda;

import com.aaseya.healthcare.application.ProcessOrchestrationPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.command.ModifyProcessInstanceCommandStep1;
import io.camunda.client.api.response.EvaluateDecisionResponse;
import io.camunda.client.api.response.EvaluatedDecision;
import io.camunda.client.api.response.MatchedDecisionRule;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.search.enums.ElementInstanceState;
import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.client.api.search.response.Incident;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.UserTask;
import io.camunda.client.api.search.response.Variable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Camunda adapter implementing the {@link ProcessOrchestrationPort} outbound port.
 *
 * <p>Every engine type is converted to a port record here so nothing above the infrastructure
 * layer imports {@code io.camunda}.
 */
@Component
public class CamundaProcessOrchestration implements ProcessOrchestrationPort {

    private static final int SEARCH_LIMIT = 200;

    private final CamundaClient client;
    private final ObjectMapper objectMapper;

    /**
     * @param client       Camunda client configured from {@code camunda.client.*}
     * @param objectMapper mapper used to parse engine variable payloads
     */
    public CamundaProcessOrchestration(CamundaClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public StartedInstance startJourney(String processDefinitionId, Map<String, Object> variables) {
        ProcessInstanceEvent event = client.newCreateInstanceCommand()
                .bpmnProcessId(processDefinitionId)
                .latestVersion()
                .variables(variables)
                .execute();

        Object caseId = variables.get("caseId");
        return new StartedInstance(
                caseId == null ? null : caseId.toString(),
                event.getProcessInstanceKey(),
                event.getProcessDefinitionKey(),
                event.getVersion());
    }

    @Override
    public Optional<InstanceState> instanceState(long processInstanceKey) {
        // The engine indexes asynchronously, so an instance started moments ago may not be
        // queryable yet. That is an expected empty result, not a fault.
        try {
            ProcessInstance pi = client.newProcessInstanceGetRequest(processInstanceKey).execute();
            return Optional.of(new InstanceState(
                    pi.getProcessInstanceKey(),
                    pi.getProcessDefinitionId(),
                    pi.getProcessDefinitionVersion(),
                    name(pi.getState()),
                    Boolean.TRUE.equals(pi.getHasIncident()),
                    pi.getStartDate(),
                    pi.getEndDate()));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<JourneyTask> activeTasks(long processInstanceKey) {
        List<UserTask> tasks = client.newUserTaskSearchRequest()
                .filter(f -> f.processInstanceKey(processInstanceKey).state(UserTaskState.CREATED))
                .sort(s -> s.creationDate().asc())
                .page(p -> p.limit(SEARCH_LIMIT))
                .execute()
                .items();

        List<JourneyTask> out = new ArrayList<>(tasks.size());
        for (UserTask t : tasks) {
            out.add(new JourneyTask(
                    t.getUserTaskKey(),
                    t.getElementId(),
                    t.getName(),
                    name(t.getState()),
                    t.getElementInstanceKey(),
                    t.getAssignee(),
                    t.getCreationDate()));
        }
        return out;
    }

    @Override
    public void completeTask(long userTaskKey, Map<String, Object> variables) {
        client.newCompleteUserTaskCommand(userTaskKey)
                .variables(variables == null ? Map.of() : variables)
                .execute();
    }

    @Override
    public Map<String, Object> variables(long processInstanceKey) {
        List<Variable> found = client.newVariableSearchRequest()
                .filter(f -> f.processInstanceKey(processInstanceKey))
                .page(p -> p.limit(SEARCH_LIMIT))
                .withFullValues()
                .execute()
                .items();

        Map<String, Object> out = new LinkedHashMap<>();
        for (Variable v : found) {
            out.put(v.getName(), parse(v.getValue()));
        }
        return out;
    }

    @Override
    public List<JourneyIncident> incidents(long processInstanceKey) {
        List<Incident> found = client.newIncidentSearchRequest()
                .filter(f -> f.processInstanceKey(processInstanceKey))
                .page(p -> p.limit(SEARCH_LIMIT))
                .execute()
                .items();

        List<JourneyIncident> out = new ArrayList<>(found.size());
        for (Incident i : found) {
            out.add(new JourneyIncident(
                    i.getIncidentKey(),
                    i.getElementId(),
                    i.getElementInstanceKey(),
                    name(i.getErrorType()),
                    i.getErrorMessage(),
                    name(i.getState())));
        }
        return out;
    }

    @Override
    public List<ElementRef> activeElements(long processInstanceKey) {
        List<ElementInstance> found = client.newElementInstanceSearchRequest()
                .filter(f -> f.processInstanceKey(processInstanceKey).state(ElementInstanceState.ACTIVE))
                .page(p -> p.limit(SEARCH_LIMIT))
                .execute()
                .items();

        List<ElementRef> out = new ArrayList<>(found.size());
        for (ElementInstance e : found) {
            out.add(new ElementRef(
                    e.getElementInstanceKey(),
                    e.getElementId(),
                    e.getElementName(),
                    name(e.getType()),
                    name(e.getState())));
        }
        return out;
    }

    @Override
    public void publishMessage(String messageName, String correlationKey, Map<String, Object> variables) {
        client.newPublishMessageCommand()
                .messageName(messageName)
                .correlationKey(correlationKey)
                .variables(variables == null ? Map.of() : variables)
                .execute();
    }

    @Override
    public void moveTo(
            long processInstanceKey,
            String targetElementId,
            List<Long> terminateInstanceKeys,
            Map<String, Object> variables) {

        ModifyProcessInstanceCommandStep1.ModifyProcessInstanceCommandStep3 step =
                client.newModifyProcessInstanceCommand(processInstanceKey)
                        .activateElement(targetElementId);

        if (variables != null && !variables.isEmpty()) {
            // Deliberately unscoped. Passing the target element id would make these local to that
            // element, and a later step in a different scope — the archive worker, for one — would
            // not see them. Unscoped variables land on the process instance and stay visible for
            // the rest of the journey.
            step = step.withVariables(variables);
        }

        ModifyProcessInstanceCommandStep1.ModifyProcessInstanceCommandStep2 command = step;
        if (terminateInstanceKeys != null) {
            for (Long key : terminateInstanceKeys) {
                if (key != null) {
                    command = command.and().terminateElement(key);
                }
            }
        }
        command.execute();
    }

    @Override
    public void cancel(long processInstanceKey) {
        client.newCancelInstanceCommand(processInstanceKey).execute();
    }

    @Override
    public DecisionOutcome evaluateDecision(String decisionId, Map<String, Object> variables) {
        EvaluateDecisionResponse response = client.newEvaluateDecisionCommand()
                .decisionId(decisionId)
                .variables(variables == null ? Map.of() : variables)
                .execute();

        List<String> matched = new ArrayList<>();
        for (EvaluatedDecision decision : response.getEvaluatedDecisions()) {
            for (MatchedDecisionRule rule : decision.getMatchedRules()) {
                matched.add(rule.getRuleId());
            }
        }

        return new DecisionOutcome(
                response.getDecisionId(),
                response.getDecisionVersion(),
                parse(response.getDecisionOutput()),
                matched);
    }

    /**
     * Converts an engine JSON payload into a value the API can render as real JSON rather than an
     * escaped string. Non-JSON payloads are returned verbatim.
     */
    private Object parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (Exception ex) {
            return raw;
        }
    }

    /** Null-safe enum rendering; the client returns enums that may be absent on older records. */
    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}

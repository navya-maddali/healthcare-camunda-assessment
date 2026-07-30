package com.aaseya.healthcare.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Outbound port for driving and inspecting a treatment journey in the workflow engine.
 *
 * <p>The workers in {@code infrastructure.worker} are the engine calling <em>into</em> this
 * service. This port is the opposite direction: the service calling <em>out</em> to the engine to
 * start a journey, advance a human step, or inspect where an instance has reached. Both directions
 * are needed for the service to own the journey end to end rather than only reacting to it.
 *
 * <p>Nothing here exposes a Camunda type. The adapter in {@code infrastructure.camunda} translates,
 * so the application layer stays independent of the client library — the same reason
 * {@link PatientCaseArchive} hides Spring Data.
 */
public interface ProcessOrchestrationPort {

    /**
     * A newly started journey.
     *
     * @param caseId               business key carried by every downstream step
     * @param processInstanceKey   engine key for the instance
     * @param processDefinitionKey engine key for the deployed definition
     * @param version              version of the definition that was started
     */
    record StartedInstance(
            String caseId,
            long processInstanceKey,
            long processDefinitionKey,
            int version) {
    }

    /**
     * Lifecycle state of a journey.
     *
     * @param processInstanceKey engine key for the instance
     * @param processDefinitionId BPMN process id
     * @param version            definition version
     * @param state              {@code ACTIVE}, {@code COMPLETED} or {@code TERMINATED}
     * @param hasIncident        whether an unresolved incident is attached
     * @param startDate          when the instance started
     * @param endDate            when it finished, or {@code null} while running
     */
    record InstanceState(
            long processInstanceKey,
            String processDefinitionId,
            Integer version,
            String state,
            boolean hasIncident,
            OffsetDateTime startDate,
            OffsetDateTime endDate) {
    }

    /**
     * A human step waiting to be completed.
     *
     * @param userTaskKey        engine key used to complete the task
     * @param elementId          BPMN element id, stable across instances
     * @param name               human-readable task name
     * @param state              task state, normally {@code CREATED}
     * @param elementInstanceKey key of the element instance hosting the task
     * @param assignee           current assignee, or {@code null}
     * @param creationDate       when the task appeared
     */
    record JourneyTask(
            long userTaskKey,
            String elementId,
            String name,
            String state,
            long elementInstanceKey,
            String assignee,
            OffsetDateTime creationDate) {
    }

    /**
     * A fault raised by the engine against one element.
     *
     * @param incidentKey        engine key for the incident
     * @param elementId          BPMN element that failed
     * @param elementInstanceKey key of the failing element instance
     * @param errorType          engine error classification
     * @param errorMessage       failure detail
     * @param state              {@code ACTIVE} or {@code RESOLVED}
     */
    record JourneyIncident(
            long incidentKey,
            String elementId,
            long elementInstanceKey,
            String errorType,
            String errorMessage,
            String state) {
    }

    /**
     * A live element instance within a journey.
     *
     * @param elementInstanceKey engine key, needed to terminate the element
     * @param elementId          BPMN element id
     * @param elementName        human-readable element name
     * @param type               BPMN element type
     * @param state              element instance state
     */
    record ElementRef(
            long elementInstanceKey,
            String elementId,
            String elementName,
            String type,
            String state) {
    }

    /**
     * Outcome of a standalone decision evaluation.
     *
     * @param decisionId     evaluated decision id
     * @param version        decision version
     * @param output         decision output, parsed from JSON where possible
     * @param matchedRuleIds ids of the rules that matched, in evaluation order
     */
    record DecisionOutcome(
            String decisionId,
            int version,
            Object output,
            List<String> matchedRuleIds) {
    }

    /**
     * Starts a journey.
     *
     * @param processDefinitionId BPMN process id to start
     * @param variables           initial process variables, including {@code caseId}
     * @return keys identifying the new instance
     */
    StartedInstance startJourney(String processDefinitionId, Map<String, Object> variables);

    /**
     * @param processInstanceKey instance to look up
     * @return the instance state, or empty when the engine has not indexed it yet
     */
    Optional<InstanceState> instanceState(long processInstanceKey);

    /**
     * @param processInstanceKey instance to inspect
     * @return user tasks in {@code CREATED} state, oldest first
     */
    List<JourneyTask> activeTasks(long processInstanceKey);

    /**
     * Completes a human step.
     *
     * @param userTaskKey task to complete
     * @param variables   variables to merge into the instance
     */
    void completeTask(long userTaskKey, Map<String, Object> variables);

    /**
     * @param processInstanceKey instance to inspect
     * @return every variable visible on the instance, values parsed from JSON where possible
     */
    Map<String, Object> variables(long processInstanceKey);

    /**
     * @param processInstanceKey instance to inspect
     * @return incidents raised against the instance, resolved ones included
     */
    List<JourneyIncident> incidents(long processInstanceKey);

    /**
     * @param processInstanceKey instance to inspect
     * @return currently active element instances
     */
    List<ElementRef> activeElements(long processInstanceKey);

    /**
     * Correlates a message into a running journey.
     *
     * @param messageName    BPMN message name
     * @param correlationKey value matching the message subscription's correlation key
     * @param variables      variables to carry into the catching scope
     */
    void publishMessage(String messageName, String correlationKey, Map<String, Object> variables);

    /**
     * Moves a journey to a different element, optionally terminating where it currently sits.
     *
     * <p>Used to step past a blocked element without cancelling the instance. The engine applies
     * both instructions in one atomic modification.
     *
     * @param processInstanceKey    instance to modify
     * @param targetElementId       element to activate
     * @param terminateInstanceKeys element instances to terminate, may be empty
     * @param variables             variables to set on the activated scope
     */
    void moveTo(
            long processInstanceKey,
            String targetElementId,
            List<Long> terminateInstanceKeys,
            Map<String, Object> variables);

    /**
     * Cancels a journey outright.
     *
     * @param processInstanceKey instance to cancel
     */
    void cancel(long processInstanceKey);

    /**
     * Evaluates a decision without running a process.
     *
     * @param decisionId decision id as deployed
     * @param variables  decision inputs
     * @return the decision output and the rules that matched
     */
    DecisionOutcome evaluateDecision(String decisionId, Map<String, Object> variables);
}

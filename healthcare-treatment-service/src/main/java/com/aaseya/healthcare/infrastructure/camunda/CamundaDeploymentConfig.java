package com.aaseya.healthcare.infrastructure.camunda;

import io.camunda.client.annotation.Deployment;
import org.springframework.context.annotation.Configuration;

/**
 * Pushes the BPMN, DMN and forms to the cluster on startup.
 *
 * <p>All eleven resources go up in one deployment, which is what the process requires: it
 * references the forms and decisions by id, so a partial deployment leaves dangling references.
 *
 * <p>This also removes a whole class of failure. Editing the BPMN by hand and forgetting to
 * redeploy leaves the cluster running the previous version indefinitely — instances stay bound to
 * the version they started on. The symptom is quiet and misleading: a user task whose deployed
 * definition is missing {@code <zeebe:userTask />} still shows as an ACTIVE element of type
 * USER_TASK, but the engine creates no user-task entity, so the task list comes back empty and
 * Tasklist shows nothing.
 *
 * <p>The glob patterns are flat by design — {@code dmn/} and {@code forms/} are siblings of
 * {@code processes/}, not nested inside it. Camunda checksums each resource, so restarting without
 * an edit does not create a new version.
 */
@Configuration
@Deployment(resources = {
        "classpath*:processes/*.bpmn",
        "classpath*:dmn/*.dmn",
        "classpath*:forms/*.form"
})
public class CamundaDeploymentConfig {
}

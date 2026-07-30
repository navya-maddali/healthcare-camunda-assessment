package com.aaseya.healthcare.architecture;

import com.aaseya.camunda.framework.test.archunit.ArchitectureRules;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Runs the framework's architecture rules over our packages.
 *
 * <p>This is the regression guard for the package layout. The one that actually bites is
 * {@link ArchitectureRules#ONLY_INFRASTRUCTURE_CAMUNDA_MAY_IMPORT_CAMUNDA_CLIENT}: the job workers
 * and the engine exception advice all need {@code io.camunda.client}, which is why they live under
 * {@code infrastructure.camunda} rather than in packages of their own. Move one back out and this
 * test fails.
 */
@AnalyzeClasses(packages = "com.aaseya.healthcare")
class HealthcareArchitectureTest {

    @ArchTest
    static final ArchRule layering =
            ArchitectureRules.WEB_AND_WORKERS_MUST_NOT_ACCESS_INFRASTRUCTURE;

    @ArchTest
    static final ArchRule camundaBoundary =
            ArchitectureRules.ONLY_INFRASTRUCTURE_CAMUNDA_MAY_IMPORT_CAMUNDA_CLIENT;

    @ArchTest
    static final ArchRule domainIsolation =
            ArchitectureRules.DOMAIN_MUST_NOT_IMPORT_SPRING_WEB_OR_CAMUNDA;

    @ArchTest
    static final ArchRule txOnControllers =
            ArchitectureRules.REST_CONTROLLERS_MUST_NOT_BE_TRANSACTIONAL;

    @ArchTest
    static final ArchRule entityExposure =
            ArchitectureRules.CONTROLLER_METHODS_MUST_NOT_EXPOSE_ENTITIES;

    @ArchTest
    static final ArchRule constructorInjection =
            ArchitectureRules.USE_CONSTRUCTOR_INJECTION;
}

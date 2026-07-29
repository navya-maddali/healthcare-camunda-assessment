package com.aaseya.healthcare.infrastructure.config;

import com.aaseya.camunda.framework.core.idempotency.IdempotencyGuard;
import com.aaseya.camunda.framework.core.idempotency.JdbcIdempotencyGuard;
import com.aaseya.camunda.framework.core.process.CamundaProcessService;
import com.aaseya.camunda.framework.core.process.ProcessService;
import com.aaseya.camunda.framework.starter.camunda.FrameworkCamundaProperties;
import com.aaseya.healthcare.application.port.PatientCaseArchive;
import com.aaseya.healthcare.application.port.ProcessOrchestrationPort;
import com.aaseya.healthcare.application.service.ArchiveCaseUseCase;
import com.aaseya.healthcare.application.service.LabOrderingUseCase;
import com.aaseya.healthcare.application.service.LabResultIngestionUseCase;
import com.aaseya.healthcare.application.service.TreatmentJourneyUseCase;
import com.aaseya.healthcare.application.service.VitalsMonitoringUseCase;
import io.camunda.client.CamundaClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the framework infrastructure beans and the application use cases.
 *
 * <p><strong>Why the framework beans are re-declared here.</strong>
 * {@code FrameworkCamundaAutoConfiguration} already defines {@code IdempotencyGuard} and
 * {@code ProcessService}, but it carries no {@code @AutoConfigureAfter}, so its
 * {@code @ConditionalOnBean} guards are evaluated before {@code JdbcTemplateAutoConfiguration}
 * and the Camunda client have registered their beans. The conditions fail and both beans are
 * silently skipped — the application then fails to start with
 * {@code No qualifying bean of type 'IdempotencyGuard'}. Declaring them here sidesteps the
 * ordering entirely; the framework's own definitions back off via {@code @ConditionalOnMissingBean}.
 * This is a workaround for a framework defect, kept local so the framework stays unmodified.
 *
 * <p>Use cases are plain constructor-injected objects rather than annotated components, keeping
 * the application layer free of Spring stereotypes.
 */
@Configuration
public class WorkerBeansConfig {

    /**
     * JDBC-backed idempotency guard used by {@code BaseWorker} to short-circuit replayed jobs.
     *
     * @param jdbcTemplate template bound to the service datasource
     * @return guard backed by the framework's {@code worker_execution} table
     */
    @Bean
    public IdempotencyGuard idempotencyGuard(JdbcTemplate jdbcTemplate) {
        return new JdbcIdempotencyGuard(jdbcTemplate);
    }

    /**
     * Outbound port for engine calls; vitals monitoring publishes {@code VitalsAlert} through it.
     *
     * @param client Camunda client configured from {@code camunda.client.*}
     * @param props  framework properties supplying the multi-tenant flag
     * @return the engine-backed {@link ProcessService}
     */
    @Bean
    public ProcessService processService(CamundaClient client, FrameworkCamundaProperties props) {
        return new CamundaProcessService(client, props.isMultiTenant());
    }

    /** @return use case for placing diagnostic orders */
    @Bean
    public LabOrderingUseCase labOrderingUseCase() {
        return new LabOrderingUseCase();
    }

    /** @return use case for ingesting diagnostic results */
    @Bean
    public LabResultIngestionUseCase labResultIngestionUseCase() {
        return new LabResultIngestionUseCase();
    }

    /**
     * @param processService port used to correlate the alert message back into the process
     * @return use case for vitals monitoring
     */
    @Bean
    public VitalsMonitoringUseCase vitalsMonitoringUseCase(ProcessService processService) {
        return new VitalsMonitoringUseCase(processService);
    }

    /**
     * @param archive outbound port to the case store
     * @return use case for archiving discharged cases
     */
    @Bean
    public ArchiveCaseUseCase archiveCaseUseCase(PatientCaseArchive archive) {
        return new ArchiveCaseUseCase(archive);
    }

    /**
     * Use case behind the REST API, driving the journey from the service side.
     *
     * @param orchestration outbound port to the workflow engine
     * @param archive       outbound port to the case store
     * @return use case for starting and steering a treatment journey
     */
    @Bean
    public TreatmentJourneyUseCase treatmentJourneyUseCase(
            ProcessOrchestrationPort orchestration, PatientCaseArchive archive) {
        return new TreatmentJourneyUseCase(orchestration, archive);
    }
}

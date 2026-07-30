package com.aaseya.healthcare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Healthcare Treatment Journey service (assessment use case 2).
 *
 * <p>Built on the Aaseya Camunda Process Framework, which is consumed as a published
 * artifact and never modified. Job workers extend the framework's {@code BaseWorker} so
 * that variable binding, idempotency, MDC context and {@code framework_job_*} metrics are
 * handled by the framework rather than re-implemented here.
 *
 * <p>{@code @EnableScheduling} is required for the framework's {@code JdbcOutboxRelay}
 * {@code @Scheduled} poller to fire.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class HealthcareTreatmentApplication {

    /**
     * Launches the Spring Boot application.
     *
     * @param args command-line arguments passed through to {@link SpringApplication}
     */
    public static void main(String[] args) {
        SpringApplication.run(HealthcareTreatmentApplication.class, args);
    }
}

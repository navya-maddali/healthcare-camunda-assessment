package com.aaseya.healthcare.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI is at /swagger-ui/index.html. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI healthcareTreatmentOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Healthcare Treatment Journey API")
                .version("1.0.0")
                .description("""
                        Inpatient admission-to-discharge journey on Camunda 8.9.
                        POST /api/v1/cases admits a patient and starts the journey; the human tasks
                        (registration, cardiology workup, treatment plan, specialist consults,
                        treatment administration, discharge sign-off) can be completed either here
                        or in Camunda Tasklist."""));
    }
}

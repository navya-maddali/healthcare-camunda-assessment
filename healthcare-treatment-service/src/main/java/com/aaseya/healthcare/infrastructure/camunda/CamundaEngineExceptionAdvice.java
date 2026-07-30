package com.aaseya.healthcare.infrastructure.camunda;

import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ClientStatusException;
import io.camunda.client.api.command.ProblemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders engine rejections as HTTP responses.
 *
 * <p>This lives in {@code infrastructure.camunda} rather than in {@code web} for a structural
 * reason: it is the only advice that needs {@code io.camunda.client} types, and the architecture
 * test forbids those outside this package. Keeping it here is what lets the web layer stay free of
 * engine imports.
 *
 * <p>The engine's own status is passed straight through. Cancelling an instance that has already
 * completed, for one, is a 404 from the engine and is a 404 here — reporting it as a server error
 * would blame this service for the caller addressing a finished instance.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class CamundaEngineExceptionAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(CamundaEngineExceptionAdvice.class);

    /**
     * Engine rejection over the v2 REST API, carrying an RFC 7807 problem detail.
     *
     * @param ex rejection raised by the Camunda client
     * @return the engine's status, or 502 when it did not supply one
     */
    @ExceptionHandler(ProblemException.class)
    public ResponseEntity<org.springframework.http.ProblemDetail> onProblem(ProblemException ex) {
        ProblemDetail detail = ex.details();
        HttpStatus status = HttpStatus.BAD_GATEWAY;
        String message = ex.getMessage();

        if (detail != null) {
            if (detail.getStatus() != null) {
                HttpStatus resolved = HttpStatus.resolve(detail.getStatus());
                if (resolved != null) {
                    status = resolved;
                }
            }
            if (detail.getDetail() != null && !detail.getDetail().isBlank()) {
                message = detail.getDetail();
            }
        }

        LOG.warn("Engine rejected command ({}): {}", status.value(), message);
        return problem(status, "Engine rejected the command", message);
    }

    /**
     * @param ex command rejected by the engine over gRPC
     * @return 502, with the engine's own message preserved
     */
    @ExceptionHandler(ClientStatusException.class)
    public ResponseEntity<org.springframework.http.ProblemDetail> onEngineRejection(ClientStatusException ex) {
        LOG.warn("Engine rejected command: {}", ex.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "Engine rejected the command", ex.getMessage());
    }

    private static ResponseEntity<org.springframework.http.ProblemDetail> problem(
            HttpStatus status, String title, String detail) {

        org.springframework.http.ProblemDetail body =
                org.springframework.http.ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        return ResponseEntity.status(status).body(body);
    }
}

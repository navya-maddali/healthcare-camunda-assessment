package com.aaseya.healthcare.infrastructure.web;

import com.aaseya.healthcare.application.service.TreatmentJourneyUseCase.ElementNotActiveException;
import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ClientStatusException;
import io.camunda.client.api.command.ProblemException;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates failures into a consistent JSON error body.
 *
 * <p>Engine faults matter most here. A {@code ClientStatusException} carries the real reason a
 * command was rejected — a stale task key, an element that cannot be activated — and that detail is
 * worth passing through, because a bare 500 makes an ordinary sequencing mistake look like an
 * outage.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Error body returned for every handled failure.
     *
     * @param timestamp when the failure was rendered
     * @param status    HTTP status code
     * @param error     short classification
     * @param message   human-readable detail
     */
    public record ApiError(OffsetDateTime timestamp, int status, String error, String message) {

        static ApiError of(HttpStatus status, String message) {
            return new ApiError(OffsetDateTime.now(), status.value(), status.getReasonPhrase(), message);
        }
    }

    /**
     * @param ex raised when a caller names an element that is not currently waiting
     * @return 409, since the request is well-formed but the journey is not at that step
     */
    @ExceptionHandler(ElementNotActiveException.class)
    public ResponseEntity<ApiError> onElementNotActive(ElementNotActiveException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT, ex.getMessage()));
    }

    /**
     * @param ex bean-validation failure on a request body
     * @return 400 naming the offending field
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onInvalid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("Request body failed validation");
        return ResponseEntity.badRequest().body(ApiError.of(HttpStatus.BAD_REQUEST, detail));
    }

    /**
     * Engine rejection over the v2 REST API.
     *
     * <p>The engine's own status is passed straight through. Cancelling an instance that has
     * already completed, for one, is a 404 from the engine and is a 404 here — reporting it as a
     * server error would blame this service for the caller addressing a finished instance.
     *
     * @param ex rejection carrying an RFC 7807 problem detail
     * @return the engine's status, or 502 when it did not supply one
     */
    @ExceptionHandler(ProblemException.class)
    public ResponseEntity<ApiError> onProblem(ProblemException ex) {
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
        return ResponseEntity.status(status).body(ApiError.of(status, message));
    }

    /**
     * @param ex command rejected by the engine over gRPC
     * @return 502, with the engine's own message preserved
     */
    @ExceptionHandler(ClientStatusException.class)
    public ResponseEntity<ApiError> onEngineRejection(ClientStatusException ex) {
        LOG.warn("Engine rejected command: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of(HttpStatus.BAD_GATEWAY, "Engine rejected the command: " + ex.getMessage()));
    }

    /**
     * @param ex anything not handled above
     * @return 500 with the exception message, logged with a stack trace
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> onUnexpected(Exception ex) {
        LOG.error("Unhandled failure serving request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, String.valueOf(ex.getMessage())));
    }
}

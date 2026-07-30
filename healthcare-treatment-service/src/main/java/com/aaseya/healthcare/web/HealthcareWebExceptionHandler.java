package com.aaseya.healthcare.web;

import com.aaseya.healthcare.application.TreatmentJourneyUseCase.AmbiguousTaskException;
import com.aaseya.healthcare.application.TreatmentJourneyUseCase.ElementNotActiveException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Fills the gaps the framework's {@code GlobalExceptionHandler} leaves open.
 *
 * <p>That handler already renders bean-validation failures and the framework exception types, but
 * everything else falls through to its {@code Exception} catch-all and comes back as a 500. Several
 * of those are plainly the caller's mistake — an unmapped path, the wrong HTTP method, an
 * unsupported content type, a body Jackson cannot parse — and a 500 tells them to raise a ticket
 * instead of fixing the request.
 *
 * <p>Ordered ahead of the framework advice so these types are claimed here; everything else still
 * falls through to it unchanged. Engine rejections are handled separately by
 * {@code CamundaEngineExceptionAdvice}, which lives in {@code infrastructure.camunda} because it
 * needs Camunda types this layer must not import.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RestControllerAdvice
public class HealthcareWebExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(HealthcareWebExceptionHandler.class);

    /**
     * @param ex raised when a caller names an element that is not currently waiting
     * @return 409, since the request is well-formed but the journey is not at that step
     */
    @ExceptionHandler(ElementNotActiveException.class)
    public ResponseEntity<ProblemDetail> onElementNotActive(ElementNotActiveException ex) {
        return problem(HttpStatus.CONFLICT, "Element not active", ex.getMessage());
    }

    /**
     * @param ex raised when "complete the waiting task" is asked of a journey waiting on several
     * @return 409, for the same reason as above — the request is well-formed, the journey is not in
     *         a state that can satisfy it. The detail names the tasks in contention so the caller
     *         can pick one.
     */
    @ExceptionHandler(AmbiguousTaskException.class)
    public ResponseEntity<ProblemDetail> onAmbiguousTask(AmbiguousTaskException ex) {
        return problem(HttpStatus.CONFLICT, "Ambiguous task", ex.getMessage());
    }

    /**
     * Spring MVC failures that already carry their own status.
     *
     * <p>An unmapped path raises {@link NoResourceFoundException}, which is a 404 by nature. Left to
     * the framework catch-all it would be reported as a 500, making a caller's typo look like an
     * outage.
     *
     * <p>The declared types share no common superclass — most extend {@code ServletException} — so
     * the parameter binds {@link ErrorResponse}, the interface they all implement and the one
     * carrying the resolved status. {@link HttpMediaTypeException} is the abstract parent of both
     * the 415 and the 406 cases, so naming it covers each.
     *
     * @param ex an MVC failure carrying the status the framework already resolved
     * @return that status, preserved
     */
    @ExceptionHandler({
        ErrorResponseException.class,
        NoResourceFoundException.class,
        HttpRequestMethodNotSupportedException.class,
        HttpMediaTypeException.class
    })
    public ResponseEntity<ProblemDetail> onErrorResponse(ErrorResponse ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        // The detail is absent for an unmapped path; the reason phrase is a better answer there
        // than echoing the internal resource path back to the caller.
        String detail = ex.getBody().getDetail();
        if (detail == null || detail.isBlank()) {
            detail = status.getReasonPhrase();
        }

        LOG.warn("Request failed ({}): {}", status.value(), detail);
        return problem(status, status.getReasonPhrase(), detail);
    }

    /**
     * Unparseable request body — malformed JSON, or a value of the wrong shape for its field.
     *
     * <p>It extends {@code NestedRuntimeException} and carries no status of its own, so without
     * this it reached the catch-all as a 500. The underlying message names Jackson internals and
     * echoes the caller's payload, so only the classification is returned; the detail goes to the
     * log.
     *
     * @param ex a body the message converters could not read
     * @return 400, since the fault is in the request
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> onUnreadableBody(HttpMessageNotReadableException ex) {
        LOG.warn("Unreadable request body: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Malformed request body",
                "Request body could not be parsed as JSON");
    }

    /**
     * @param ex a path or query parameter that would not convert — in practice a process instance
     *           key that is not a number
     * @return 400 naming the parameter and the type it needed
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> onTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String required = ex.getRequiredType() == null
                ? "the expected type"
                : ex.getRequiredType().getSimpleName();
        return problem(HttpStatus.BAD_REQUEST, "Invalid request parameter",
                "Parameter '" + ex.getName() + "' must be a valid " + required);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        return ResponseEntity.status(status).body(body);
    }
}

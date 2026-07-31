package com.aaseya.healthcare.domain;

/**
 * The ingested outcome of a previously placed {@link LabOrder}.
 *
 * <p>One of these is produced per multi-instance diagnostics branch and written to that branch's
 * own {@code testResult} variable. The sub-process declares no {@code outputCollection}, so the
 * results are not gathered into a single list — each stays scoped to the branch that produced it.
 *
 * @param orderId  the order this result belongs to
 * @param testType the test that was run
 * @param status   {@code COMPLETED}
 * @param summary  short human-readable finding shown in Tasklist
 */
public record LabResult(String orderId, String testType, String status, String summary) {

    /** Result status for a test whose findings are within normal limits. */
    public static final String COMPLETED = "COMPLETED";
}

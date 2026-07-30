package com.aaseya.healthcare.domain;

/**
 * The ingested outcome of a previously placed {@link LabOrder}.
 *
 * <p>One of these is produced per multi-instance diagnostics branch and collected by the
 * sub-process into the {@code labResults} output collection, giving the physician every
 * result in one place on the treatment-plan task.
 *
 * @param orderId  the order this result belongs to
 * @param testType the test that was run
 * @param status   {@code COMPLETED} or {@code ABNORMAL}
 * @param summary  short human-readable finding shown in Tasklist
 */
public record LabResult(String orderId, String testType, String status, String summary) {

    /** Result status for a test whose findings are within normal limits. */
    public static final String COMPLETED = "COMPLETED";

    /** Result status for a test whose findings need clinician attention. */
    public static final String ABNORMAL = "ABNORMAL";
}

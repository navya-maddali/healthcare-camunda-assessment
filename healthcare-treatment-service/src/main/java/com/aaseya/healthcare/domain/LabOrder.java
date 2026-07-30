package com.aaseya.healthcare.domain;

/**
 * A diagnostic test ordered for one instance of the parallel diagnostics sub-process.
 *
 * @param orderId  identifier issued by the diagnostics system
 * @param testType the ordered test, one element of the triage decision's {@code diagnosticTests}
 * @param status   lifecycle state; {@code ORDERED} on creation, {@code CANCELLED} once compensated
 */
public record LabOrder(String orderId, String testType, String status) {

    /** Status assigned to a freshly placed order. */
    public static final String ORDERED = "ORDERED";

    /**
     * Status assigned when the order is withdrawn by compensation.
     *
     * <p>An order that was placed but whose branch never produced a result has to be withdrawn,
     * or the diagnostics department keeps a slot reserved and may still run the test on a patient
     * whose care has moved on.
     */
    public static final String CANCELLED = "CANCELLED";
}

package com.aaseya.healthcare.domain;

/**
 * A diagnostic test ordered for one instance of the parallel diagnostics sub-process.
 *
 * @param orderId  identifier issued by the diagnostics system
 * @param testType the ordered test, one element of the triage decision's {@code diagnosticTests}
 * @param status   lifecycle state; {@code ORDERED} on creation
 */
public record LabOrder(String orderId, String testType, String status) {

    /** Status assigned to a freshly placed order. */
    public static final String ORDERED = "ORDERED";
}

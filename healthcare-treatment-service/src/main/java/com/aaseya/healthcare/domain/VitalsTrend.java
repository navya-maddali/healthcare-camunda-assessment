package com.aaseya.healthcare.domain;

/**
 * Direction of a patient's vitals across successive observations.
 *
 * <p>These constants are the input domain of the {@code discharge-readiness} DMN table;
 * the names must stay in step with that table's {@code vitalsTrend} input entries.
 */
public enum VitalsTrend {

    /** Vitals moving away from safe ranges — blocks discharge outright. */
    DETERIORATING,

    /** Vitals recovering after a revised treatment plan, not yet at baseline. */
    IMPROVING,

    /** Vitals within safe ranges. */
    STABLE
}

package com.aaseya.healthcare.domain.service;

import com.aaseya.healthcare.domain.model.VitalsReading;
import com.aaseya.healthcare.domain.model.VitalsTrend;

/**
 * The single authority on what counts as a vitals threshold breach.
 *
 * <p>Previously these thresholds were inlined in the vitals job worker <em>and</em> duplicated
 * on the reading record, and the two had already drifted — the worker had silently dropped the
 * bradycardia check, so a patient with a dangerously low heart rate would not have raised an
 * alert. Centralising the rule here removes that class of bug and keeps clinical policy out of
 * the Camunda adapter layer.
 *
 * <p>Deliberately free of Spring, Camunda and JPA so it can be unit-tested in isolation.
 */
public final class VitalsAssessment {

    /** Below this heart rate the patient is bradycardic. */
    static final double HEART_RATE_MIN = 40.0;

    /** Above this heart rate the patient is tachycardic. */
    static final double HEART_RATE_MAX = 130.0;

    /** Below this oxygen saturation the patient is hypoxic. */
    static final double SPO2_MIN = 90.0;

    /** Above this core temperature the patient is febrile to a reportable degree. */
    static final double TEMPERATURE_MAX = 39.5;

    private VitalsAssessment() {
        // utility
    }

    /**
     * Decides whether a reading breaches any monitored threshold.
     *
     * @param reading the observation to evaluate; must not be {@code null}
     * @return {@code true} when at least one vital sign is outside its safe range
     */
    public static boolean isBreached(VitalsReading reading) {
        return reading.heartRate() < HEART_RATE_MIN
                || reading.heartRate() > HEART_RATE_MAX
                || reading.spO2() < SPO2_MIN
                || reading.temperature() > TEMPERATURE_MAX;
    }

    /**
     * Derives the reportable trend for a reading.
     *
     * <p>A breach is always {@link VitalsTrend#DETERIORATING}. A clean reading is reported as
     * {@link VitalsTrend#IMPROVING} while the patient is still on a revised plan (that is, at
     * least one discharge attempt has already been rejected) and {@link VitalsTrend#STABLE}
     * once they have held that state. This keeps every branch of the {@code discharge-readiness}
     * table reachable instead of leaving a third of its rules as dead code.
     *
     * @param reading         the observation to evaluate
     * @param priorAttempts   how many discharge-readiness checks have already failed
     * @return the trend to publish to the process
     */
    public static VitalsTrend trendFor(VitalsReading reading, int priorAttempts) {
        if (isBreached(reading)) {
            return VitalsTrend.DETERIORATING;
        }
        return priorAttempts >= 2 ? VitalsTrend.STABLE : VitalsTrend.IMPROVING;
    }
}

package com.aaseya.healthcare.application;

import com.aaseya.camunda.framework.core.exception.BusinessException;
import com.aaseya.healthcare.domain.LabOrder;

import java.util.UUID;

/**
 * Places a diagnostic order for one branch of the parallel diagnostics sub-process.
 *
 * <p>Throws the framework's {@link BusinessException} when the diagnostics system is
 * unavailable. Per the framework's error-handling convention, {@code BaseWorker} translates
 * that into a BPMN error carrying the exception's error code, which the
 * {@code DIAGNOSTIC_SYSTEM_UNAVAILABLE} boundary event catches and escalates to the physician.
 * Technical faults are deliberately <em>not</em> raised this way — those stay as incidents so
 * Camunda's retry budget applies.
 */
public class LabOrderingUseCase {

    /** BPMN error code matched by the boundary event on the diagnostics sub-process. */
    public static final String DIAGNOSTIC_SYSTEM_UNAVAILABLE = "DIAGNOSTIC_SYSTEM_UNAVAILABLE";

    /**
     * Places an order for a single test.
     *
     * @param testType   the test to order
     * @param systemDown when {@code true}, simulates the diagnostics system being unreachable;
     *                   sourced from the {@code diagnosticSystemDown} process variable so the
     *                   exception path can be demonstrated without restarting the service
     * @return the placed order
     * @throws BusinessException when {@code systemDown} is {@code true}
     */
    public LabOrder placeOrder(String testType, boolean systemDown) {
        if (systemDown) {
            throw new BusinessException(DIAGNOSTIC_SYSTEM_UNAVAILABLE,
                    "Diagnostics system unavailable while ordering " + testType);
        }

        String orderId = "ORD-" + testType + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        return new LabOrder(orderId, testType, LabOrder.ORDERED);
    }

    /**
     * Withdraws an order that was placed but never yielded a result.
     *
     * <p>This is the compensating action for {@link #placeOrder}. It runs when the diagnostics
     * sub-process is interrupted after one or more branches have already booked their tests — the
     * analyser accepting an order and then going down, for one. Without it the department holds a
     * slot for a test nobody will read, and may still run it on a patient whose care has moved on.
     *
     * <p>Deliberately tolerant: compensation must not fail. An order the diagnostics system has
     * already forgotten is nothing to worry about, so an unknown {@code orderId} is reported as
     * cancelled rather than raised as an error.
     *
     * @param orderId  the order to withdraw
     * @param testType the test it was booked for
     * @return the withdrawn order
     */
    public LabOrder cancelOrder(String orderId, String testType) {
        return new LabOrder(orderId, testType, LabOrder.CANCELLED);
    }
}
